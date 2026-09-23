package app.ytdlp.gui

import android.app.Application
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import java.io.File

class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        try {
            YoutubeDL.getInstance().init(this)
            FFmpeg.getInstance().init(this)
        } catch (e: Exception) {
            Log.e("MainApplication", "failed to initialize youtubedl-android, attempting recovery", e)
            // Only wipe binaries if initialization genuinely fails (corrupted binary)
            val baseDir = File(noBackupFilesDir, "youtubedl-android")
            if (baseDir.exists()) {
                baseDir.deleteRecursively()
            }
            try {
                YoutubeDL.getInstance().init(this)
                FFmpeg.getInstance().init(this)
            } catch (recoveryEx: Exception) {
                Log.e("MainApplication", "Recovery initialization failed", recoveryEx)
            }
        }

    }
}
