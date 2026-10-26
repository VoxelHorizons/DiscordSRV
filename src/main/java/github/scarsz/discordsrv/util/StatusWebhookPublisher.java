/*
 * DiscordSRV - https://github.com/DiscordSRV/DiscordSRV
 * Copyright (C) 2016 - 2026 DiscordSRV contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 or any later version.
 */
package github.scarsz.discordsrv.util;

import github.scarsz.discordsrv.DiscordSRV;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Icon;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.TextChannel;
import net.dv8tion.jda.api.entities.Webhook;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.apache.commons.lang3.StringUtils;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * One bot-owned webhook and one editable message for the server's public status.
 * All potentially blocking Discord requests are called from background threads.
 * Only the channel and message IDs are saved; webhook tokens are never persisted.
 */
public final class StatusWebhookPublisher {
    private static final String WEBHOOK_NAME_DEFAULT = "VH Server Status";
    private static final int MAX_AVATAR_BYTES = 4 * 1024 * 1024;
    private final DiscordSRV plugin;
    private final File stateFile;

    private String messageId;
    private String messageChannelId;
    private String webhookChannelId;
    private String webhookUrl;
    // Remembers which bot avatar was applied to which managed webhook across restarts.
    private String syncedAvatarKey;
    private boolean replaceMessageAfterAvatarUpdate;

    public StatusWebhookPublisher(DiscordSRV plugin) {
        this.plugin = plugin;
        this.stateFile = new File(plugin.getDataFolder(), "status-webhook-state.yml");
        YamlConfiguration state = YamlConfiguration.loadConfiguration(stateFile);
        messageId = state.getString("message-id");
        messageChannelId = state.getString("channel-id");
        syncedAvatarKey = state.getString("avatar-key");
    }

    public boolean enabled() {
        return DiscordSRV.config().getBooleanElse("StatusWebhookEnabled", false);
    }

    /**
     * Called on DiscordSRV's presence updater thread. Re-edit even unchanged
     * statuses so manually removed messages are eventually recreated.
     */
    public synchronized void publishOnline(String status, OnlineStatus onlineStatus) {
        if (!enabled() || plugin.getJda() == null || !plugin.isEnabled()) return;
        TextChannel channel = resolveChannel();
        if (channel == null) return;
        String text = format(configString("StatusWebhookOnlineMessage",
                "🟢 **Server Online**\n🎮 %status%\n👥 %online%/%max% players"),
                status, onlineStatus != null ? onlineStatus.name() : "ONLINE", false);
        upsert(channel, buildPayload(text, false));
    }

    /** Called on the Bukkit thread while DiscordSRV starts shutting down. */
    public String renderShutdownMessage() {
        return format(configString("StatusWebhookOfflineMessage", "🔴 **Server Offline**"),
                "", "OFFLINE", true);
    }

    /** Called on the existing DiscordSRV shutdown worker before JDA is closed. */
    public synchronized void publishShutdown(String offlineText) {
        if (!enabled() || plugin.getJda() == null) return;
        String action = configString("StatusWebhookShutdownAction", "EDIT").trim().toUpperCase(java.util.Locale.ROOT);
        if ("DELETE".equals(action) || "DELETE_WEBHOOK".equals(action)) {
            deleteTrackedMessage("DELETE_WEBHOOK".equals(action));
        } else {
            TextChannel channel = resolveChannel();
            if (channel != null) upsert(channel, buildPayload(offlineText, true));
        }
    }

    /** Invoked by /discord reload on a background thread. */
    public synchronized void onConfigReload() {
        webhookChannelId = null;
        webhookUrl = null;
        // Turning the feature off removes the managed message instead of leaving stale "online" information.
        if (!enabled()) {
            if (plugin.getJda() != null) deleteTrackedMessage(false);
            return;
        }
        // Apply edited templates/channel selection immediately; do not wait for the next presence rotation.
        github.scarsz.discordsrv.objects.threads.PresenceUpdater updater = plugin.getPresenceUpdater();
        publishOnline(updater == null ? null : updater.getLastStatus(),
                updater == null ? null : updater.getLastOnlineStatus());
    }

    private TextChannel resolveChannel() {
        String id = configString("StatusWebhookChannelId", "").trim();
        if (!id.isEmpty()) {
            if (!id.matches("[0-9]{17,20}")) {
                DiscordSRV.warning("StatusWebhookChannelId must be a numerical Discord text channel ID.");
                return null;
            }
            return plugin.getJda().getTextChannelById(id);
        }
        // Existing DiscordSRV status channel mapping, with the usual global fallback.
        return plugin.getOptionalTextChannel("status");
    }

    private String configString(String key, String fallback) {
        String configured = DiscordSRV.config().getString(key);
        return configured == null ? fallback : configured;
    }

    private String format(String template, String status, String onlineStatus, boolean shutdown) {
        String formatted = template
                .replace("%status%", status == null ? "" : status)
                .replace("%online_status%", onlineStatus)
                .replace("%online%", shutdown ? "0" : Integer.toString(PlayerUtil.getOnlinePlayers(true).size()))
                .replace("%max%", Integer.toString(Bukkit.getMaxPlayers()));
        return PlaceholderUtil.replacePlaceholdersToDiscord(formatted);
    }

    private JSONObject buildPayload(String message, boolean offline) {
        JSONObject json = new JSONObject();
        json.put("allowed_mentions", new JSONObject().put("parse", new JSONArray()));
        // A blank override means "use the DiscordSRV bot's current avatar" (not Discord's
        // generic default). Also use it for new webhook messages while the managed
        // webhook's persistent profile picture is being synchronized.
        String avatarUrl = avatarUrl();
        if (StringUtils.isNotBlank(avatarUrl)) json.put("avatar_url", avatarUrl);
        if (DiscordSRV.config().getBooleanElse("StatusWebhookEmbedEnabled", true)) {
            String color = configString(offline ? "StatusWebhookEmbedOfflineColor" : "StatusWebhookEmbedOnlineColor",
                    offline ? "ED4245" : "57F287");
            JSONObject embed = new JSONObject()
                    .put("title", truncate(configString("StatusWebhookEmbedTitle", "VoxelHorizons • Server Status"), 256))
                    .put("description", truncate(StringUtils.isBlank(message) ? " " : message, 4096))
                    .put("color", parseColor(color, offline ? 0xED4245 : 0x57F287));
            json.put("embeds", new JSONArray().put(embed));
            json.put("content", "");
        } else {
            json.put("content", truncate(StringUtils.isBlank(message) ? " " : message, 2000));
            json.put("embeds", new JSONArray());
        }
        return json;
    }

    private static int parseColor(String hex, int fallback) {
        try {
            return Integer.parseInt(hex.replace("#", "").trim(), 16) & 0xFFFFFF;
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private static String truncate(String text, int limit) {
        return text.length() > limit ? text.substring(0, limit) : text;
    }

    private String webhookName() {
        String configured = configString("StatusWebhookName", WEBHOOK_NAME_DEFAULT).trim();
        return StringUtils.isBlank(configured) ? WEBHOOK_NAME_DEFAULT : truncate(configured, 80);
    }

    private String avatarUrl() {
        String override = configString("StatusWebhookAvatarUrl", "").trim();
        if (StringUtils.isNotBlank(override)) return override;
        return plugin.getJda() == null ? "" : plugin.getJda().getSelfUser().getEffectiveAvatarUrl();
    }

    /**
     * Update the actual webhook avatar (the profile image in Discord's webhook
     * popover), not just the per-message avatar_url override. Do this at most
     * once per avatar change, including for pre-existing generic-avatar hooks.
     */
    private void syncWebhookAvatar(Webhook webhook) {
        String url = avatarUrl();
        if (StringUtils.isBlank(url)) return;
        String key = webhook.getId() + ":" + url;
        if (key.equals(syncedAvatarKey) && webhook.getDefaultUser().getAvatarId() != null) return;

        // Never fetch arbitrary plaintext URLs for avatar uploads.
        if (!url.startsWith("https://")) {
            DiscordSRV.warning("StatusWebhookAvatarUrl must be an HTTPS image URL.");
            return;
        }

        try {
            Request request = new Request.Builder().url(url)
                    .header("User-Agent", "DiscordSRV/" + plugin.getDescription().getVersion())
                    .build();
            byte[] bytes;
            try (Response response = plugin.getJda().getHttpClient().newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null)
                    throw new IOException("Avatar image unavailable (HTTP " + response.code() + ")");
                if (!response.header("Content-Type", "").toLowerCase(java.util.Locale.ROOT).startsWith("image/"))
                    throw new IOException("Avatar URL did not return an image");
                try (InputStream input = response.body().byteStream();
                     ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
                    byte[] chunk = new byte[8192];
                    int read;
                    while ((read = input.read(chunk)) != -1) {
                        if (buffer.size() + read > MAX_AVATAR_BYTES)
                            throw new IOException("Avatar exceeds " + MAX_AVATAR_BYTES + " bytes");
                        buffer.write(chunk, 0, read);
                    }
                    bytes = buffer.toByteArray();
                }
            }
            if (bytes.length == 0) throw new IOException("Avatar image was empty");
            webhook.getManager().setAvatar(Icon.from(bytes))
                    .reason("DiscordSRV: synchronize status webhook profile picture").complete();
            syncedAvatarKey = key;
            // Existing messages can retain a snapshot of their old avatar.
            // Replace the one managed message once so it also displays the new icon.
            replaceMessageAfterAvatarUpdate = messageId != null
                    && webhook.getChannel().getId().equals(messageChannelId);
            persist();
        } catch (Exception e) {
            // Avatar failure must never prevent the status text itself from updating.
            DiscordSRV.warning("Could not synchronize status webhook avatar: " + e.getClass().getSimpleName());
        }
    }


    private String findOrCreateWebhook(TextChannel channel) {
        // Re-resolve a webhook when the bot's avatar or explicit override changes,
        // including changes made while the server is running.
        String avatar = avatarUrl();
        if (channel.getId().equals(webhookChannelId) && webhookUrl != null
                && syncedAvatarKey != null && syncedAvatarKey.endsWith(":" + avatar)) return webhookUrl;
        webhookChannelId = null;
        webhookUrl = null;
        Member self = channel.getGuild().getSelfMember();
        if (!self.hasPermission(channel, Permission.MANAGE_WEBHOOKS)) {
            DiscordSRV.warning("Status webhook requires Manage Webhooks in #" + channel.getName());
            return null;
        }
        try {
            List<Webhook> hooks = channel.retrieveWebhooks().complete();
            Webhook selected = null;
            for (Webhook hook : hooks) {
                if (!hook.getName().equals(webhookName()) || hook.getOwner() == null
                        || !hook.getOwner().getId().equals(self.getId())) continue;
                if (selected == null) {
                    selected = hook;
                } else {
                    // Only remove duplicates that belong to this bot and use our exact reserved name.
                    hook.delete().reason("DiscordSRV: duplicate server status webhook").complete();
                }
            }
            if (selected == null) {
                selected = channel.createWebhook(webhookName())
                        .reason("DiscordSRV: persistent server status").complete();
            }
            syncWebhookAvatar(selected);
            webhookChannelId = channel.getId();
            webhookUrl = selected.getUrl();
            return webhookUrl;
        } catch (Exception ex) {
            DiscordSRV.warning("Could not find or create server status webhook: " + ex.getMessage());
            return null;
        }
    }

    private void upsert(TextChannel channel, JSONObject payload) {
        // A changed channel must not attempt to edit a message from the old channel.
        if (!channel.getId().equals(messageChannelId)) {
            // Best-effort removal from the previous channel before moving the status.
            if (messageChannelId != null && messageId != null) deleteTrackedMessage(false);
            messageChannelId = null;
            messageId = null;
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            String url = findOrCreateWebhook(channel);
            if (url == null) return;
            if (replaceMessageAfterAvatarUpdate && messageId != null) {
                ApiResult deletion = request("DELETE", url + "/messages/" + messageId, null);
                if (deletion.code == 204 || deletion.code == 404) {
                    messageId = null;
                    replaceMessageAfterAvatarUpdate = false;
                    persist();
                } else {
                    // Retry on the next status update instead of creating duplicates.
                    warnApi("replacing old-avatar", deletion);
                }
            }
            if (messageId != null) {
                ApiResult edit = request("PATCH", url + "/messages/" + messageId, payload);
                if (edit.code == 200) return;
                if (edit.code == 404) {
                    // Could be a deleted message or a removed webhook.
                    messageId = null;
                    persist();
                    webhookUrl = null;
                    webhookChannelId = null;
                    continue;
                }
                warnApi("editing", edit);
                return;
            }
            ApiResult post = request("POST", url + "?wait=true", payload);
            if (post.code == 200) {
                try {
                    String newMessageId = new JSONObject(post.body).getString("id");
                    if (!newMessageId.matches("[0-9]+")) throw new IllegalArgumentException("Invalid message id");
                    messageId = newMessageId;
                    messageChannelId = channel.getId();
                    persist();
                } catch (Exception e) {
                    DiscordSRV.warning("Status webhook message sent but its ID could not be recorded: " + e.getMessage());
                }
                return;
            }
            if (post.code == 404 && attempt == 0) {
                webhookUrl = null;
                webhookChannelId = null;
                continue;
            }
            warnApi("creating", post);
            return;
        }
        // Last attempt after a 404 may only have invalidated a missing message: recreate once.
        String url = findOrCreateWebhook(channel);
        if (url == null) return;
        ApiResult post = request("POST", url + "?wait=true", payload);
        if (post.code == 200) {
            try {
                messageId = new JSONObject(post.body).getString("id");
                messageChannelId = channel.getId();
                persist();
            } catch (Exception e) {
                DiscordSRV.warning("Status webhook message sent but its ID could not be recorded: " + e.getMessage());
            }
        } else {
            warnApi("recreating", post);
        }
    }

    private void deleteTrackedMessage(boolean deleteWebhook) {
        if (messageChannelId == null || plugin.getJda() == null) return;
        TextChannel channel = plugin.getJda().getTextChannelById(messageChannelId);
        if (channel == null) return;
        String url = findOrCreateWebhook(channel);
        if (url == null) return;
        if (messageId != null) {
            ApiResult deletion = request("DELETE", url + "/messages/" + messageId, null);
            if (deletion.code != 204 && deletion.code != 404) {
                warnApi("deleting", deletion);
                return;
            }
        }
        if (deleteWebhook) {
            try {
                Member self = channel.getGuild().getSelfMember();
                for (Webhook hook : channel.retrieveWebhooks().complete()) {
                    if (hook.getName().equals(webhookName()) && hook.getOwner() != null
                            && hook.getOwner().getId().equals(self.getId())) {
                        hook.delete().reason("DiscordSRV: status webhook shutdown action").complete();
                    }
                }
                webhookUrl = null;
                webhookChannelId = null;
            } catch (Exception e) {
                DiscordSRV.warning("Unable to delete server status webhook: " + e.getMessage());
            }
        }
        messageId = null;
        messageChannelId = null;
        persist();
    }

    private ApiResult request(String method, String url, JSONObject payload) {
        Request.Builder builder = new Request.Builder().url(url)
                .header("User-Agent", "DiscordSRV/" + plugin.getDescription().getVersion());
        switch (method) {
            case "POST":
                builder.post(RequestBody.create(MediaType.get("application/json; charset=utf-8"), payload.toString()));
                break;
            case "PATCH":
                builder.patch(RequestBody.create(MediaType.get("application/json; charset=utf-8"), payload.toString()));
                break;
            case "DELETE":
                builder.delete();
                break;
            default:
                throw new IllegalArgumentException("Unsupported webhook operation");
        }
        try (Response response = plugin.getJda().getHttpClient().newCall(builder.build()).execute()) {
            return new ApiResult(response.code(), response.body() == null ? "" : response.body().string());
        } catch (IOException e) {
            DiscordSRV.warning("Server status webhook request failed: " + e.getMessage());
            return new ApiResult(-1, "");
        }
    }

    private void warnApi(String action, ApiResult result) {
        // Never print webhook URLs, tokens or entire Discord responses.
        DiscordSRV.warning("Error " + action + " server status webhook message (HTTP " + result.code + ").");
    }

    private void persist() {
        YamlConfiguration state = new YamlConfiguration();
        state.set("channel-id", messageChannelId);
        state.set("message-id", messageId);
        state.set("avatar-key", syncedAvatarKey);
        try {
            File folder = stateFile.getParentFile();
            if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Could not create plugin data folder");
            state.save(stateFile);
        } catch (IOException e) {
            DiscordSRV.warning("Failed to persist server status webhook message ID: " + e.getMessage());
        }
    }

    private static final class ApiResult {
        final int code;
        final String body;
        ApiResult(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }
}
