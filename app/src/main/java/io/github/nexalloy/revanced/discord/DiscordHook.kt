package io.github.nexalloy.revanced.discord

import io.github.nexalloy.Patch
import io.github.nexalloy.revanced.discord.privacy.DisableTracking
import io.github.nexalloy.revanced.discord.privacy.HideTyping

val DiscordPatches = arrayOf<Patch>(
    DisableTracking,
    HideTyping,
)
