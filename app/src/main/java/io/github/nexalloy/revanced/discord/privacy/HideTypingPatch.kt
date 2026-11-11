package io.github.nexalloy.revanced.discord.privacy

import io.github.nexalloy.patch
import io.github.nexalloy.revanced.discord.core.DiscordRequests
import io.github.nexalloy.revanced.discord.core.Requests

private const val POST = "POST"
private const val TYPING = "/typing"

val HideTyping = patch(
    name = "Hide typing indicator",
    description = "Others don't see when you're typing. You still see when they are.",
) {
    Requests.blockers += { method, url -> method.equals(POST, true) && Requests.path(url).endsWith(TYPING) }
    dependsOn(DiscordRequests)
}
