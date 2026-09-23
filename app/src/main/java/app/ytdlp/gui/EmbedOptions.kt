package app.ytdlp.gui

import java.io.Serializable

/**
 * Holds the three optional embed flags the user can toggle via FilterChips.
 * Defaults all to true so they're pre-checked on first launch.
 */
data class EmbedOptions(
    val embedChapters: Boolean = true,
    val embedThumbnail: Boolean = true,
    val embedSubs: Boolean = true,
    val embedMetadata: Boolean = true
) : Serializable