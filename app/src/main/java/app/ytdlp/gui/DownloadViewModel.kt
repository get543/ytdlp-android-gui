package app.ytdlp.gui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Represents all possible states of the download pipeline. */
sealed class DownloadState {
    object Idle : DownloadState()
    data class Downloading(val progress: Float, val eta: String) : DownloadState()
    data class Done(val message: String) : DownloadState()
    data class Error(val message: String) : DownloadState()
}

/**
 * Manages download state by delegating execution to the Foreground DownloadService.
 * Observes the static state and logs exposed by DownloadService.
 */
class DownloadViewModel(application: Application) : AndroidViewModel(application) {

    val state: LiveData<DownloadState> = DownloadService.state
    val newLogLine: LiveData<String> = DownloadService.newLogLine

    /** Returns the entire accumulated log from the service. */
    fun getFullLog(): String = DownloadService.logBuilder.toString()

    /**
     * Kicks off the download via the Foreground Service.
     */
    fun startDownload(url: String, mode: DownloadMode, embedOptions: EmbedOptions) {
        if (state.value is DownloadState.Downloading) return  // Prevent concurrent downloads
        DownloadService.start(getApplication(), url.trim(), mode, embedOptions)
    }

    /** Stops an active background download process. */
    fun stopDownload() {
        DownloadService.stop(getApplication())
    }

    /** Reset to Idle. */
    fun reset() {
        DownloadService.state.value = DownloadState.Idle
        DownloadService.logBuilder.clear()
    }

    /**
     * Updates yt-dlp binary to the latest release channel.
     */
    fun updateEngine(onComplete: (Boolean, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                appendLog("🔄 Checking for yt-dlp updates...")
                val status = YoutubeDL.getInstance().updateYoutubeDL(
                    getApplication(),
                    YoutubeDL.UpdateChannel.STABLE
                )
                val newVersion = YoutubeDL.getInstance().version(getApplication())
                val message = "yt-dlp is up to date ($status): $newVersion"
                appendLog("✓ $message")
                withContext(Dispatchers.Main) {
                    onComplete(true, message)
                }
            } catch (e: Exception) {
                Log.e("DownloadViewModel", "Failed to update yt-dlp", e)
                val errorMsg = "Update failed: ${e.message}"
                appendLog("✗ $errorMsg")
                withContext(Dispatchers.Main) {
                    onComplete(false, errorMsg)
                }
            }
        }
    }

    /** Clears the accumulated internal log. */
    fun clearLog() {
        DownloadService.logBuilder.clear()
    }

    /** Clears all files inside the app's internal cache directory recursively. */
    fun clearCache(onComplete: (Boolean, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cacheDir = getApplication<Application>().cacheDir
                if (cacheDir.exists()) {
                    cacheDir.listFiles()?.forEach { file ->
                        file.deleteRecursively()
                    }
                }
                withContext(Dispatchers.Main) {
                    onComplete(true, "Cache cleared successfully")
                }
            } catch (e: Exception) {
                Log.e("DownloadViewModel", "Failed to clear cache", e)
                withContext(Dispatchers.Main) {
                    onComplete(false, "Failed to clear cache: ${e.message}")
                }
            }
        }
    }

    private fun appendLog(line: String) {
        if (DownloadService.logBuilder.isNotEmpty()) {
            DownloadService.logBuilder.append("\n")
        }
        DownloadService.logBuilder.append(line)
        DownloadService.newLogLine.postValue(line)
    }
}
