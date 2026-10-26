![](https://lol.scarsz.me/AiKvTS/Logo-filled-stroke.png "DiscordSRV Logo")

[![](https://discord.com/api/guilds/135634590575493120/widget.png)](https://discordsrv.com/discord "Discord")
[![](https://img.shields.io/github/release/DiscordSRV/DiscordSRV.svg)](https://github.com/DiscordSRV/DiscordSRV/releases/latest "Latest release")
[![](https://img.shields.io/github/downloads/DiscordSRV/DiscordSRV/total.svg)](https://github.com/DiscordSRV/DiscordSRV/releases/latest "GitHub downloads")
![](https://img.shields.io/github/languages/code-size/DiscordSRV/DiscordSRV.svg "Code size in bytes")
[![](https://img.shields.io/github/contributors/DiscordSRV/DiscordSRV.svg)](https://github.com/DiscordSRV/DiscordSRV/graphs/contributors "GitHub contributors")
[![](https://img.shields.io/github/license/DiscordSRV/DiscordSRV.svg)](https://github.com/DiscordSRV/DiscordSRV/blob/master/LICENSE "License")

A Minecraft to Discord and back link plugin

[![](https://bstats.org/signatures/bukkit/DiscordSRV.svg)](https://bstats.org/plugin/bukkit/DiscordSRV/387 "DiscordSRV on bStats")

## VoxelHorizons fork: Persistent status webhook

The fork can maintain **one editable Discord server-status message** instead of
posting a separate status notification on every Minecraft start and shutdown.
The normal DiscordSRV startup/shutdown messages are unaffected until enabled.

Add these settings to `plugins/DiscordSRV/config.yml`:

```yaml
Channels: {"global": "YOUR_CHAT_CHANNEL_ID", "status": "YOUR_SERVER_STATUS_CHANNEL_ID"}
StatusWebhookEnabled: true
StatusWebhookChannelId: "" # Or an explicit target Discord channel ID
StatusWebhookName: "VH Server Status"
StatusWebhookAvatarUrl: "" # Automatically reuse the DiscordSRV bot's avatar
StatusWebhookOnlineMessage: "🟢 **Server Online**\\n🎮 %status%\\n👥 %online%/%max% players"
StatusWebhookOfflineMessage: "🔴 **Server Offline**"
StatusWebhookShutdownAction: "EDIT"
StatusWebhookEmbedEnabled: true
StatusWebhookEmbedTitle: "VoxelHorizons • Server Status"
StatusWebhookEmbedOnlineColor: "57F287"
StatusWebhookEmbedOfflineColor: "ED4245"
```

Use `%status%` to display the currently rotated `DiscordGameStatus` value;
`%online%`, `%max%`, `%online_status%`, and PlaceholderAPI placeholders are
also available. The status message follows `StatusUpdateRateInMinutes`
(default two minutes) and updates after `/discord reload`.

When `StatusWebhookAvatarUrl` is blank, the status webhook itself and its
messages automatically display the DiscordSRV bot's current profile picture
(instead of the default Discord icon). An explicit HTTPS image URL overrides
this. The first avatar synchronization may replace the managed message once
because Discord can retain the old avatar on existing webhook messages.

The DiscordSRV bot needs **Manage Webhooks** permission in the target text
channel. It creates or reuses an *owned* dedicated webhook; **do not provide
or store a webhook token**. Only the channel and message IDs are persisted to
`status-webhook-state.yml` for editing across restarts. If you delete the
managed message or webhook in Discord, it is re-created on the next update.

Shutdown actions: `EDIT` makes the message show "offline"; `DELETE` deletes
the message but retains the managed webhook; `DELETE_WEBHOOK` removes both
the managed message and its bot-owned webhook. When this feature is turned
off with `/discord reload`, DiscordSRV deletes the tracked message.

**Limitations:** Normal clean shutdowns update the status. Power loss,
forced kills, and crashes cannot send an offline notification; use external
uptime monitoring if detecting those events is required. If several Minecraft
instances share one Discord channel, give each one a distinct
`StatusWebhookName` so they do not edit each other's status.

## VoxelHorizons: secure account-link channel

Map `"link": "YOUR_DISCORD_CHANNEL_ID"` in `Channels`. The bot must have
**Manage Messages** in the link channel, and its application must have the
`applications.commands` scope to register the `/link` slash command.

```yaml
MinecraftDiscordAccountLinkChannelDeleteMessages: true
MinecraftDiscordAccountLinkChannelAllowLegacyCodes: false
MinecraftDiscordAccountLinkChannelEnablePrivateSlashCommand: true
```

* All **new ordinary messages** in the mapped link channel (including failed/invalid
  codes and bot messages) are deleted, regardless of whether they can be processed.
  Messages sent by **webhooks are never deleted**; your instructional webhook remains.
* Use **`/link code:1234`** for actual linking. The Discord interaction and its
  reply are **ephemeral (only visible to the invoking user, dismissible)**, so
  the code never appears as a public chat message.
* If someone posts a normal message, it is deleted first and the bot sends
  private instructions by DM (if the user allows DMs). Discord **cannot** give
  ephemeral replies to normal messages. To keep legacy text-code linking,
  explicitly set `MinecraftDiscordAccountLinkChannelAllowLegacyCodes: true`;
  only successfully deleted messages will be processed and replies are sent
  privately by DM rather than published to the channel.
* Edit `LinkChannelPrivateInstructions`, `LinkChannelPrivateResultFormat`,
  `LinkChannelPrivateOutsideChannel`, and `LinkChannelPrivateUnavailable`
  in `messages.yml`. `%result%` uses existing configurable account-link
  result messages.
* This cleans up **new** messages, not historical channel history. Deletion
  is not instant and cannot guarantee secrecy for an already-posted plain
  message; use slash commands and/or deny Send Messages permission to users.

## Thanks to:
<table>
    <tr>
        <td align="center" width="50%">
            <a href="https://www.jetbrains.com/idea/"><img src="https://resources.jetbrains.com/storage/products/company/brand/logos/jetbrains.svg" alt="Jetbrains" width="400px"></a>
            <p><strong>IntelliJ IDEA - The Leading Java and Kotlin IDE</strong></p>
        </td>
        <td align="center" width="50%">
            <a href="http://www.ej-technologies.com/products/jprofiler/overview.html"><img src="https://www.ej-technologies.com/images/product_banners/jprofiler_large.png" alt="JProfiler" width="150px"></img></a>
            <p><strong>JProfiler's intuitive UI helps you resolve performance bottlenecks, pin down memory leaks and understand threading issues.</strong></p>
        </td>
    </tr>
</table>
