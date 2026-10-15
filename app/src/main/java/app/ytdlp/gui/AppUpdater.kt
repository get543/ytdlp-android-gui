package app.ytdlp.gui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import androidx.core.net.toUri

object AppUpdater {
    private const val TAG = "AppUpdater"

    private const val GITHUB_OWNER = "get543"
    private const val GITHUB_REPO = "ytdlp-android-gui"
    private const val API_URL = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

    fun checkForUpdates(activity: Activity) {
        val cacheFile = File(activity.cacheDir, "update.apk")

        // If an update APK was already downloaded and permission is now granted, offer to install it immediately
        if (cacheFile.exists() && (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || activity.packageManager.canRequestPackageInstalls())) {
            activity.runOnUiThread {
                AlertDialog.Builder(activity)
                    .setTitle("Install Downloaded Update")
                    .setMessage("An update APK has already been downloaded. Would you like to install it now?")
                    .setPositiveButton("Install") { dialog, _ ->
                        dialog.dismiss()
                        installApk(activity, cacheFile)
                    }
                    .setNegativeButton("Delete & Re-check") { dialog, _ ->
                        dialog.dismiss()
                        cacheFile.delete()
                        checkForUpdates(activity)
                    }
                    .show()
            }
            return
        }

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
                    installApk(activity, cacheFile)
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

    private fun installApk(activity: Activity, apkFile: File) {
        if (!apkFile.exists()) return

        // On Android 8.0+ (API 26+), verify if 'Install unknown apps' permission is enabled for this app
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(activity)
                .setTitle("Install Permission Required")
                .setMessage("To install the update, please allow 'Install unknown apps' for ${activity.getString(R.string.app_name)} in Settings.")
                .setPositiveButton("Open Settings") { dialog, _ ->
                    dialog.dismiss()
                    val intent = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        "package:${activity.packageName}".toUri()
                    )
                    activity.startActivity(intent)
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        launchPackageInstaller(activity, apkFile)
    }

    private fun launchPackageInstaller(activity: Activity, apkFile: File) {
        try {
            val authority = "${activity.packageName}.fileprovider"
            val apkUri = FileProvider.getUriForFile(activity, authority, apkFile)

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch package installer", e)
            AlertDialog.Builder(activity)
                .setTitle("Installation Failed")
                .setMessage("Could not launch package installer: ${e.localizedMessage}")
                .setPositiveButton("OK", null)
                .show()
        }
    }
}
