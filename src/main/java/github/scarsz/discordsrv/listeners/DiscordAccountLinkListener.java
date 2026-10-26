/*
 * DiscordSRV - https://github.com/DiscordSRV/DiscordSRV
 *
 * Copyright (C) 2016 - 2024 Austin "Scarsz" Shapiro
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 */

package github.scarsz.discordsrv.listeners;

import github.scarsz.discordsrv.Debug;
import github.scarsz.discordsrv.DiscordSRV;
import github.scarsz.discordsrv.api.events.DiscordPrivateMessageReceivedEvent;
import github.scarsz.discordsrv.api.commands.PluginSlashCommand;
import github.scarsz.discordsrv.api.commands.SlashCommand;
import github.scarsz.discordsrv.api.commands.SlashCommandProvider;
import github.scarsz.discordsrv.util.LangUtil;
import github.scarsz.discordsrv.util.DiscordUtil;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.events.interaction.SlashCommandEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.TextChannel;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.events.message.guild.GuildMessageReceivedEvent;
import net.dv8tion.jda.api.events.message.priv.PrivateMessageReceivedEvent;
import net.dv8tion.jda.api.exceptions.ErrorHandler;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.requests.RestAction;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;

public class DiscordAccountLinkListener extends ListenerAdapter implements SlashCommandProvider {

    private static final String PREFIX = "MinecraftDiscordAccountLinkChannel";
    private final Set<String> warnedMissingDeletePermission = ConcurrentHashMap.newKeySet();

    private boolean enabled(String name, boolean fallback) {
        return DiscordSRV.config().getBooleanElse(PREFIX + name, fallback);
    }

    private String translated(LangUtil.Message key, String fallback) {
        String value = key.toString();
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    private String clean(String input) {
        // Never echo raw linking codes back to the public channel.
        return input.length() > 2000 ? input.substring(0, 2000) : input;
    }

    @Override
    public Set<PluginSlashCommand> getSlashCommands() {
        if (!enabled("EnablePrivateSlashCommand", true)) return Collections.emptySet();
        // Only publish a /link command if this server actually has an explicitly mapped
        // account-link channel. Avoid adding commands to unrelated Discord guilds.
        String channelId = DiscordSRV.getPlugin().getChannels().get("link");
        if (channelId == null || channelId.trim().isEmpty() || DiscordSRV.getPlugin().getJda() == null)
            return Collections.emptySet();
        TextChannel configuredChannel = DiscordSRV.getPlugin().getJda().getTextChannelById(channelId);
        if (configuredChannel == null) {
            DiscordSRV.warning("Secure linking needs a valid mapped text channel: Channels.link");
            return Collections.emptySet();
        }

        CommandData command = new CommandData("link", "Privately link your Minecraft account")
                .addOption(OptionType.STRING, "code", "Your four-digit Minecraft account linking code", true);
        PluginSlashCommand registration = new PluginSlashCommand(DiscordSRV.getPlugin(), command);
        return Collections.singleton(registration.addGuildFilter(
                configuredChannel.getGuild()));
    }

    @SlashCommand(path = "link", deferReply = true, deferEphemeral = true)
    public void onPrivateLinkCommand(SlashCommandEvent event) {
        TextChannel linkChannel = DiscordSRV.getPlugin().getDestinationTextChannelForGameChannelName("link");
        if (linkChannel == null || event.getChannel() == null || !event.getChannel().getId().equals(linkChannel.getId())) {
            event.getHook().sendMessage(clean(translated(LangUtil.Message.LINK_CHANNEL_OUTSIDE,
                    "Please use this command in the account-link channel."))).queue();
            return;
        }
        OptionMapping option = event.getOption("code");
        String code = option == null ? "" : option.getAsString().trim();
        if (!code.matches("[0-9]{4}")) {
            event.getHook().sendMessage(clean(translated(LangUtil.Message.INVALID_CODE,
                    "Your link code must be exactly four digits."))).queue();
            return;
        }
        if (DiscordSRV.getPlugin().getAccountLinkManager() == null) {
            event.getHook().sendMessage(clean(translated(LangUtil.Message.LINK_CHANNEL_UNAVAILABLE,
                    "Account linking is temporarily unavailable. Please try again later."))).queue();
            return;
        }
        try {
            String result = DiscordSRV.getPlugin().getAccountLinkManager().process(code, event.getUser().getId());
            if (result == null) result = translated(LangUtil.Message.LINK_CHANNEL_UNAVAILABLE,
                    "Account linking is temporarily unavailable. Please try again later.");
            String format = translated(LangUtil.Message.LINK_CHANNEL_PRIVATE_RESULT, "%result%");
            event.getHook().sendMessage(clean(format.replace("%result%", result))).queue();
        } catch (Exception failure) {
            DiscordSRV.warning("Private account link request could not be completed (" + failure.getClass().getSimpleName() + ")");
            event.getHook().sendMessage(clean(translated(LangUtil.Message.LINK_CHANNEL_UNAVAILABLE,
                    "Account linking is temporarily unavailable. Please try again later."))).queue();
        }
    }


    @Override
    public void onPrivateMessageReceived(PrivateMessageReceivedEvent event) {
        // don't process messages sent by the bot
        if (event.getAuthor().getId().equals(event.getJDA().getSelfUser().getId())) return;

        DiscordSRV.api.callEvent(new DiscordPrivateMessageReceivedEvent(event));

        // don't link accounts if config option is disabled
        if (!DiscordSRV.config().getBoolean("MinecraftDiscordAccountLinkedUsePM")) return;

        String reply = DiscordSRV.getPlugin().getAccountLinkManager().process(event.getMessage().getContentRaw(), event.getAuthor().getId());
        if (reply != null) event.getMessage().reply(reply).queue();
    }


    private final ErrorHandler ignoreFailedToDeleteMessage = new ErrorHandler()
            .ignore(ErrorResponse.UNKNOWN_MESSAGE)
            .ignore(ErrorResponse.MISSING_ACCESS);

    @Override
    public void onGuildMessageReceived(GuildMessageReceivedEvent event) {
        TextChannel linkChannel = DiscordSRV.getPlugin().getDestinationTextChannelForGameChannelName("link");
        if (linkChannel == null || !event.getChannel().equals(linkChannel)) return;

        Message receivedMessage = event.getMessage();
        // Permanent instructions may be posted by webhooks. NEVER delete or process
        // webhook messages, regardless of who owns them.
        if (receivedMessage.isWebhookMessage()) return;

        // The whole channel is protected, including arbitrary messages from bots.
        if (!enabled("DeleteMessages", true)) return;
        if (!event.getGuild().getSelfMember().hasPermission(linkChannel, Permission.MESSAGE_MANAGE)) {
            if (warnedMissingDeletePermission.add(linkChannel.getId())) {
                DiscordSRV.warning("SECURITY: Cannot automatically remove link codes in #" + linkChannel.getName()
                        + ". Grant the bot Manage Messages permission; use /link meanwhile.");
            }
            return;
        }
        warnedMissingDeletePermission.remove(linkChannel.getId());

        // Delete FIRST: do not wait on account-link processing, even if the message
        // contains no code or linking is currently unavailable.
        receivedMessage.delete().queue(ignored -> {
            if (event.getAuthor().isBot()) return;

            if (!enabled("AllowLegacyCodes", false)) {
                sendPrivateNotice(event, translated(LangUtil.Message.LINK_CHANNEL_INSTRUCTIONS,
                        "For your privacy, use /link with your four-digit code. Only you can see the response."));
                return;
            }

            if (DiscordSRV.getPlugin().getAccountLinkManager() == null) {
                sendPrivateNotice(event, translated(LangUtil.Message.LINK_CHANNEL_UNAVAILABLE,
                        "Account linking is temporarily unavailable. Please try again later."));
                return;
            }
            try {
                String result = DiscordSRV.getPlugin().getAccountLinkManager()
                        .process(receivedMessage.getContentRaw(), event.getAuthor().getId());
                if (result != null) {
                    String format = translated(LangUtil.Message.LINK_CHANNEL_PRIVATE_RESULT, "%result%");
                    sendPrivateNotice(event, clean(format.replace("%result%", result)));
                }
            } catch (Exception failure) {
                DiscordSRV.warning("Legacy account-link request failed (" + failure.getClass().getSimpleName() + ")");
                sendPrivateNotice(event, translated(LangUtil.Message.LINK_CHANNEL_UNAVAILABLE,
                        "Account linking is temporarily unavailable. Please try again later."));
            }
        }, ignoreFailedToDeleteMessage.handle(ErrorResponse.MISSING_PERMISSIONS,
                failure -> DiscordSRV.warning("Cannot remove messages from account-link channel: grant the bot Manage Messages permission.")));
    }

    private void sendPrivateNotice(GuildMessageReceivedEvent event, String content) {
        // Plain text messages cannot receive ephemeral replies. DM users instead,
        // never publish account link results in the channel.
        event.getAuthor().openPrivateChannel().queue(dm -> dm.sendMessage(clean(content)).queue(
                sent -> {}, failed -> DiscordSRV.debug(Debug.ACCOUNT_LINKING,
                        "Unable to DM account-link instructions (user may have DMs disabled)")
        ), failure -> DiscordSRV.debug(Debug.ACCOUNT_LINKING,
                "Unable to open DM channel for account-link instructions"));
    }

    @Override
    public void onGuildMemberJoin(GuildMemberJoinEvent event) {
        Member member = event.getMember();

        if (DiscordSRV.getPlugin().getAccountLinkManager() == null) {
            DiscordSRV.debug(Debug.ACCOUNT_LINKING, "AccountLinkManager is null, not processing join event");
            return;
        }

        // add linked role and nickname back to people when they rejoin the server
        UUID uuid = DiscordSRV.getPlugin().getAccountLinkManager().getUuid(event.getUser().getId());
        if (uuid != null) {
            Role roleToAdd = DiscordUtil.resolveRole(DiscordSRV.config().getString("MinecraftDiscordAccountLinkedRoleNameToAddUserTo"));
            if (roleToAdd == null || roleToAdd.getGuild().equals(member.getGuild())) {
                if (roleToAdd != null) DiscordUtil.addRoleToMember(member, roleToAdd);
                else DiscordSRV.debug(Debug.GROUP_SYNC, "Couldn't add user to null role");
            } else {
                DiscordSRV.debug(Debug.GROUP_SYNC, "Not adding role to member upon guild join due to the guild being different! (" + roleToAdd.getGuild() + " / " + member.getGuild() + ")");
            }

            if (DiscordSRV.config().getBoolean("NicknameSynchronizationEnabled")) {
                OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
                DiscordSRV.getPlugin().getNicknameUpdater().setNickname(member, player);
            }
        }
    }

}
