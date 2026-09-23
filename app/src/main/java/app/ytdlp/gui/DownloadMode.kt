package app.ytdlp.gui

import java.io.Serializable

/**
 * Represents the three download modes the user can pick.
 * Each mode carries its relevant sub-option (resolution or audio format).
 */
sealed class DownloadMode : Serializable {

    /** Download a single video in MP4 at a chosen resolution. */
    data class Video(val resolution: VideoResolution) : DownloadMode()

    /** Download an entire playlist as a video files in MP4. */
    data class VideoPlaylist(val resolution: VideoResolution) : DownloadMode()

    /** Download an entire playlist as audio-only files. */
    data class MusicPlaylist(val format: AudioFormat) : DownloadMode()

    /** Download a single video as audio-only (no playlist). */
    data class OneMusic(val format: AudioFormat) : DownloadMode()

    /** True if this mode downloads a playlist (affects output filename template). */
    val isPlaylist: Boolean
        get() = when (this) {
            is VideoPlaylist, is MusicPlaylist -> true
            is Video, is OneMusic -> false
        }
}

/** Resolution presets for video downloads. */
enum class VideoResolution(val label: String, val heightLimit: Int?) {
    P720("720p / HD (Highest FPS)", 720),
    P1080("1080p / FHD (Highest FPS)", 1080),
    P1440("1440p / QHD / 2K (Highest FPS)", 1440),
    P2160("2160p / UHD / 4K (Highest FPS)", 2160),
    BEST("Best Quality (bestvideo+bestaudio)", null)   // null = no height cap
}

/** Audio format presets for music/playlist downloads. */
enum class AudioFormat(val label: String, val ext: String) {
    M4A(".m4a (native AAC)", "m4a"),
    MP3(".mp3 (converted)", "mp3"),
    WEBM(".webm (best audio, no conversion)", "webm"),
    OPUS(".opus (160kbps audio)", "opus")
}
