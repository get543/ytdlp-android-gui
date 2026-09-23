package app.ytdlp.gui

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDLRequest

/**
 * Builds a YoutubeDLRequest from user selections.
 * This is a pure function — no side effects, easy to unit test.
 *
 * Output paths:
 *  - Single / Video: ~/Downloads/%(title)s.%(ext)s
 *  - Playlist:       ~/Downloads/%(playlist)s/%(playlist_index)s - %(title)s.%(ext)s
 */
fun buildYtDlpRequest(
    context: Context,
    url: String,
    mode: DownloadMode,
    embedOptions: EmbedOptions
): YoutubeDLRequest {

    val request = YoutubeDLRequest(url)

    // ── Output directory and filename template ──────────────────────────────
    // 🛡️ FIX: Download to the app's private cache first.
    // We will move it to the public Downloads folder after it finishes.
    val downloadsDir = context.cacheDir.absolutePath

    val outputTemplate = if (mode.isPlaylist) {
        // Playlist: group by playlist name, prefix with track index
        "$downloadsDir/%(playlist)s/%(playlist_index)s - %(title)s.%(ext)s"
    } else {
        // Single file: flat in Downloads
        "$downloadsDir/%(title)s.%(ext)s"
    }

    request.addOption("-o", outputTemplate)

    // ── Mode-specific format flags ──────────────────────────────────────────
    when (mode) {

        is DownloadMode.Video -> {
            applyVideoQuality(request, mode.resolution)
        }

        is DownloadMode.VideoPlaylist -> {
            request.addOption("--yes-playlist")
            request.addOption("--ignore-errors")
            applyVideoQuality(request, mode.resolution)
        }

        is DownloadMode.MusicPlaylist -> {
            request.addOption("--yes-playlist")
            request.addOption("--ignore-errors")
            applyAudioFormat(request, mode.format)
        }


        is DownloadMode.OneMusic -> {
            request.addOption("--no-playlist")
            applyAudioFormat(request, mode.format)
        }
    }

    // ── Optional embed flags (user-controlled) ─────────────────────────────
    // 🛑 TEMPORARILY DISABLED FOR DEBUGGING:
    // These often cause silent crashes on Android due to missing 'mutagen' or 'AtomicParsley' binaries.
    if (embedOptions.embedChapters) request.addOption("--embed-chapters")
    if (embedOptions.embedThumbnail) {
        request.addOption("--embed-thumbnail")
        request.addOption("--convert-thumbnails", "jpg")
    }
    if (embedOptions.embedSubs) request.addOption("--embed-subs")
    if (embedOptions.embedMetadata) request.addOption("--embed-metadata")

    // Force overwrite broken/leftover files in the cache folder
    request.addOption("--force-overwrites")

    return request
}

/** Applies audio extraction flags based on the chosen AudioFormat. */
private fun applyAudioFormat(request: YoutubeDLRequest, format: AudioFormat) {
    when (format) {
        AudioFormat.M4A -> {
            // Extract audio natively as m4a (no re-encoding for best quality)
            request.addOption("-x")
            request.addOption("--audio-format", "m4a")
            request.addOption("-f", "bestaudio[ext=m4a]/bestaudio")
        }
        AudioFormat.MP3 -> {
            // Extract and re-encode to mp3
            request.addOption("-x")
            request.addOption("--audio-format", "mp3")
        }
        AudioFormat.WEBM -> {
            // Keep the original best-quality audio stream as webm — no re-encoding
            request.addOption("-f", "bestaudio")
        }
        AudioFormat.OPUS -> {
            // Extracts audio and remuxes to .opus without re-encoding
            request.addOption("-x")
            request.addOption("--audio-format", "opus")
            request.addOption("-f", "bestaudio")
        }
    }
}

private fun applyVideoQuality(request: YoutubeDLRequest, resolution: VideoResolution) {
    // Always request the best separate video+audio streams
    request.addOption("-f", "bestvideo+bestaudio/best")

    // Tell yt-dlp to merge video and audio into an MP4 container
    request.addOption("--merge-output-format", "mp4")

    // Format sorting rules (-S)
    // "res:X" picks the closest resolution up to X (e.g., 1080p or 720p).
    // "ext" prefers mp4 containers.
    // "codec:h264" forces H.264 video, permanently fixing the VLC black screen!
    val sortRule = if (resolution.heightLimit != null) {
        "res:${resolution.heightLimit},ext"
    } else {
        "ext"
    }

    request.addOption("-S", sortRule)
}
