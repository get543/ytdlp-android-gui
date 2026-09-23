package app.ytdlp.gui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object AppUpdater {
    private const val TAG = "AppUpdater"
    
    // ⚠️ TODO: REPLACE WITH YOUR ACTUAL GITHUB OWNER AND REPOSITORY NAME
    private const val GITHUB_OWNER = "get543"
    private const val GITHUB_REPO = "ytdlp-android-gui"
    private const val API_URL = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

    fun checkForUpdates(activity: Activity) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val jsonResponse = fetchLatestReleaseJson() ?: return@launch
                val tagName = jsonResponse.optString("tag_name", "")
                val remoteVersion = tagName.replace("v", "").trim()
                val currentVersion = BuildConfig.VERSION_NAME.trim()

                val downloadUrl = extractApkDownloadUrl(jsonResponse)

                if (remoteVersion.isNotEmpty() && remoteVersion != currentVersion && !downloadUrl.isNullOrEmpty()) {
                    withContext(Dispatchers.Main) {
                        showUpdateDialog(activity, remoteVersion, downloadUrl)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to complete update check pipeline", e)
            }
        }
    }

    private fun fetchLatestReleaseJson(): JSONObject? {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(API_URL)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "ytdlp-gui-updater")
                connectTimeout = 10000
                readTimeout = 10000
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                JSONObject(response)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch update JSON from GitHub", e)
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun extractApkDownloadUrl(json: JSONObject): String? {
        val assets = json.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (asset.optString("name", "").endsWith(".apk")) {
                return asset.optString("browser_download_url", "")
            }
        }
        return null
    }

    private fun showUpdateDialog(activity: Activity, newVersion: String, downloadUrl: String) {
        if (activity.isFinishing || activity.isDestroyed) return

        AlertDialog.Builder(activity)
            .setTitle("New Update Available")
            .setMessage("Version $newVersion is available. Would you like to download and install it now?")
            .setPositiveButton("Download Now") { dialog, _ ->
                dialog.dismiss()
                startApkDownload(activity, downloadUrl)
            }
            .setNegativeButton("Later") { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }

    private fun startApkDownload(activity: Activity, downloadUrl: String) {
        // Create a programmatic Material 3 progress dialog layout
        val padding = (16 * activity.resources.displayMetrics.density).toInt()
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val statusText = TextView(activity).apply {
            text = "Downloading update... 0%"
            textSize = 14f
            setPadding(0, 0, 0, padding / 2)
        }

        val progressIndicator = LinearProgressIndicator(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            isIndeterminate = false
            max = 100
            progress = 0
        }

        container.addView(statusText)
        container.addView(progressIndicator)

        val progressDialog = AlertDialog.Builder(activity)
            .setTitle("Downloading Update")
            .setView(container)
            .setCancelable(false)
            .create()

        progressDialog.show()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL(downloadUrl)
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 15000
                }

                val fileLength = connection.contentLength
                val cacheFile = File(activity.cacheDir, "update.apk")
                
                connection.inputStream.use { input ->
                    cacheFile.outputStream().use { output ->
                        val data = ByteArray(4096)
                        var total: Long = 0
                        var count: Int
                        while (input.read(data).also { count = it } != -1) {
                            total += count
                            if (fileLength > 0) {
                                val progressPercent = (total * 100 / fileLength).toInt()
                                withContext(Dispatchers.Main) {
                                    progressIndicator.progress = progressPercent
                                    statusText.text = "Downloading update... $progressPercent%"
                                }
                            }
                            output.write(data, 0, count)
                        }
                    }
                }
                connection.disconnect()

                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    launchPackageInstaller(activity, cacheFile)
                }

            } catch (e: Exception) {
                Log.e(TAG, "APK download failed", e)
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    AlertDialog.Builder(activity)
                        .setTitle("Download Failed")
                        .setMessage("Could not download the update APK. Please try again later.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    private fun launchPackageInstaller(context: Context, apkFile: File) {
        try {
            val authority = "${context.packageName}.fileprovider"
            val apkUri = FileProvider.getUriForFile(context, authority, apkFile)
            
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch package installer", e)
        }
    }
}
