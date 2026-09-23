package app.ytdlp.gui

import android.app.*
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.MutableLiveData
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.*
import java.io.File

class DownloadService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var notificationManager: NotificationManager

    companion object {
        const val CHANNEL_ID = "download_channel"
        const val NOTIFICATION_ID = 1
        const val COMPLETE_NOTIFICATION_ID = 2

        const val EXTRA_URL = "extra_url"
        const val EXTRA_MODE = "extra_mode"
        const val EXTRA_EMBED = "extra_embed"

        val state = MutableLiveData<DownloadState>(DownloadState.Idle)
        val logBuilder = StringBuilder()
        val newLogLine = MutableLiveData<String>()

        private var isRunning = false

        fun start(context: Context, url: String, mode: DownloadMode, embedOptions: EmbedOptions) {
            val intent = Intent(context, DownloadService::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_MODE, mode)
                putExtra(EXTRA_EMBED, embedOptions)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = "ACTION_STOP"
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "ACTION_STOP") {
            YoutubeDL.getInstance().destroyProcessById("ytdlp_process")
            serviceScope.cancel()
            state.postValue(DownloadState.Idle)
            appendLog("✗ Download canceled by user.")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            isRunning = false
            return START_NOT_STICKY
        }

        val url = intent?.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra(EXTRA_MODE, DownloadMode::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra(EXTRA_MODE) as? DownloadMode
        } ?: return START_NOT_STICKY

        val embed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra(EXTRA_EMBED, EmbedOptions::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra(EXTRA_EMBED) as? EmbedOptions
        } ?: return START_NOT_STICKY

        if (isRunning) return START_NOT_STICKY
        isRunning = true

        val notification = createProgressNotification("Starting...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        serviceScope.launch {
            try {
                performDownload(url, mode, embed)
            } finally {
                isRunning = false
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }

        return START_STICKY
    }

    private suspend fun performDownload(url: String, mode: DownloadMode, embed: EmbedOptions) {
        logBuilder.clear()
        state.postValue(DownloadState.Downloading(0f, "—"))

        try {
            ensureEngineIsUpdated()
            val request = buildYtDlpRequest(this, url, mode, embed)
            
            val downloadedFiles = mutableSetOf<String>()
            var caughtException: Exception? = null

            try {
                YoutubeDL.getInstance().execute(request, "ytdlp_process") { progress, eta, line ->
                    val progressInt = progress.toInt()
                    state.postValue(DownloadState.Downloading(progress, eta.toString()))
                    appendLog(line)
                    updateNotification(progressInt, "Downloading... $progressInt% (ETA: $eta)")

                    when {
                        line.contains("Merging formats into") -> {
                            Regex("Merging formats into \"(.+)\"").find(line)?.groupValues?.get(1)?.let { downloadedFiles.add(it) }
                        }
                        line.contains("[ExtractAudio] Destination:") -> {
                            Regex("\\[ExtractAudio\\] Destination: (.+)").find(line)?.groupValues?.get(1)?.let { downloadedFiles.add(it) }
                        }
                        line.contains("[download] Destination:") -> {
                            Regex("\\[download\\] Destination: (.+)").find(line)?.groupValues?.get(1)?.let { downloadedFiles.add(it) }
                        }
                    }
                }
            } catch (e: Exception) {
                caughtException = e
            }

            if (caughtException != null && downloadedFiles.isEmpty()) {
                throw caughtException
            }

            val publicUris = mutableListOf<Uri>()
            downloadedFiles.forEach { path ->
                val sourceFile = File(path)
                if (sourceFile.exists()) {
                    val publicUri = publishToPublicDownloads(this, sourceFile)
                    publicUri?.let { publicUris.add(it) }
                }
            }

            cleanupCache(this)

            state.postValue(DownloadState.Done("Download complete ✓"))
            appendLog("✓ Finished successfully and saved to Downloads.")
            
            showCompletionNotification(mode, publicUris)

        } catch (e: Exception) {
            val msg = e.message ?: e.toString()
            state.postValue(DownloadState.Error(msg))
            appendLog("✗ Error: $msg")
            showErrorNotification(msg)
        }
    }

    private suspend fun ensureEngineIsUpdated() {
        var currentVersion = YoutubeDL.getInstance().version(this)
        if (currentVersion == null) {
            appendLog("🔄 Auto-updating yt-dlp...")
            try {
                YoutubeDL.getInstance().updateYoutubeDL(this, YoutubeDL.UpdateChannel.STABLE)
                currentVersion = YoutubeDL.getInstance().version(this)
                appendLog("✓ Engine updated: $currentVersion")
            } catch (e: Exception) {
                appendLog("⚠ Auto-update failed, using bundled version.")
            }
        }
    }

    private fun appendLog(line: String) {
        if (logBuilder.isNotEmpty()) logBuilder.append("\n")
        logBuilder.append(line)
        newLogLine.postValue(line)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Downloads",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createProgressNotification(content: String, progress: Int = 0): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification(progress: Int, content: String) {
        notificationManager.notify(NOTIFICATION_ID, createProgressNotification(content, progress))
    }

    private fun showCompletionNotification(mode: DownloadMode, publicUris: List<Uri>) {
        val isPlaylist = mode.isPlaylist
        val intent = if (isPlaylist || publicUris.isEmpty()) {
            Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
        } else {
            val fileUri = publicUris.first()
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(fileUri, getMimeTypeFromUri(fileUri))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val pendingIntent = PendingIntent.getActivity(
            this, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Download Finished")
            .setContentText(if (isPlaylist) "Playlist saved to Downloads" else "File saved to Downloads")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(COMPLETE_NOTIFICATION_ID, notification)
    }

    private fun showErrorNotification(message: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Download Failed")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(COMPLETE_NOTIFICATION_ID, notification)
    }

    /**
     * Move downloaded files from the cache folder to the Downloads folder.
     * File TargerSubfolder : ~/Downloads/yt-dlp GUI/<downloaded file>
     */
    private fun publishToPublicDownloads(context: Context, sourceFile: File): Uri? {
        val resolver = context.contentResolver
        val cacheDirPath = context.cacheDir.absolutePath
        val relativePath = sourceFile.absolutePath.replace(cacheDirPath, "").trimStart(File.separatorChar)
        
        // Append "yt-dlp GUI" subfolder to the master Environment Downloads directory
        val baseTargetSubfolder = Environment.DIRECTORY_DOWNLOADS + File.separator + "yt-dlp GUI"
        
        val targetDir = if (relativePath.contains(File.separatorChar)) {
            baseTargetSubfolder + File.separator + relativePath.substringBeforeLast(File.separatorChar)
        } else {
            baseTargetSubfolder
        }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, sourceFile.name)
                    put(MediaStore.Downloads.MIME_TYPE, getMimeType(sourceFile.name))
                    put(MediaStore.Downloads.RELATIVE_PATH, targetDir)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                uri?.let {
                    resolver.openOutputStream(it)?.use { out -> sourceFile.inputStream().use { inp -> inp.copyTo(out) } }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(it, values, null, null)
                    sourceFile.delete()
                    it
                }
            } else {
                val publicDownloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val targetAppFolder = File(publicDownloadsDir, "yt-dlp GUI")
                if (!targetAppFolder.exists()) {
                    targetAppFolder.mkdirs()
                }
                
                val finalFolder = if (relativePath.contains(File.separatorChar)) {
                    File(targetAppFolder, relativePath.substringBeforeLast(File.separatorChar)).apply { mkdirs() }
                } else {
                    targetAppFolder
                }
                
                val destFile = File(finalFolder, sourceFile.name)
                sourceFile.copyTo(destFile, overwrite = true)
                val intent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
                intent.data = Uri.fromFile(destFile)
                context.sendBroadcast(intent)
                sourceFile.delete()
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", destFile)
            }
        } catch (e: Exception) {
            Log.e("DownloadService", "Publish failed", e)
            null
        }
    }

    private fun cleanupCache(context: Context) {
        val cacheDir = context.cacheDir
        if (cacheDir.exists()) {
            cacheDir.listFiles()?.forEach { file ->
                val targetExtensions = listOf("opus", "webp", "vtt", "mp4", "webm", "m4a", "mp3", "mkv", "part", "ytdl", "temp", "description", "json")
                if (file.extension.lowercase() in targetExtensions) file.delete()
            }
        }
    }

    private fun getMimeType(fileName: String) = when {
        fileName.endsWith(".mp4", true) -> "video/mp4"
        fileName.endsWith(".webm", true) -> "video/webm"
        fileName.endsWith(".m4a", true) -> "audio/mp4"
        fileName.endsWith(".mp3", true) -> "audio/mpeg"
        fileName.endsWith(".opus", true) -> "audio/opus"
        else -> "application/octet-stream"
    }

    private fun getMimeTypeFromUri(uri: Uri): String {
        return contentResolver.getType(uri) ?: "video/*"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
