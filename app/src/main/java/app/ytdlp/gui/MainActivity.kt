package app.ytdlp.gui

import android.Manifest
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.activity.enableEdgeToEdge
import app.ytdlp.gui.databinding.ActivityMainBinding


class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: DownloadViewModel by viewModels()

    // ── Storage permission launcher (Android < 10 only) ──────────────────
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            triggerDownload()
        } else {
            Toast.makeText(this, getString(R.string.err_storage_denied), Toast.LENGTH_LONG).show()
        }
    }

    // ── Notification permission launcher (Android 13+) ──────────────────
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        checkStorageAndDownload()
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Handle edge-to-edge insets
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setSupportActionBar(binding.toolbar)

        // ── Wire up UI interactions ────────────────────────────────────
        setupModeSelector()
        setupPasteButton()
        setupDownloadButton()
        
        binding.btnStopDownload.setOnClickListener {
            viewModel.stopDownload()
        }

        // ── Restore log if the screen was rotated during a download ────
        val existingLog = viewModel.getFullLog()
        if (existingLog.isNotEmpty()) {
            binding.tvLog.text = existingLog
            binding.logScrollView.post { binding.logScrollView.fullScroll(View.FOCUS_DOWN) }
        }

        // ── Observe ViewModel state ────────────────────────────────────
        observeDownloadState()
        observeLog()

        // ── Check for app updates via GitHub releases ──────────────────
        AppUpdater.checkForUpdates(this)
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu?): Boolean {
        menu?.add(0, 1001, 0, "Update yt-dlp")?.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER)
        menu?.add(0, 1002, 0, "Clear log")?.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER)
        menu?.add(0, 1003, 0, "Clear cache")?.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            1001 -> {
                Toast.makeText(this, "Updating yt-dlp...", Toast.LENGTH_SHORT).show()
                viewModel.updateEngine { _, msg ->
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
                true
            }
            1002 -> {
                viewModel.clearLog()
                binding.tvLog.text = ""
                Toast.makeText(this, "Log cleared", Toast.LENGTH_SHORT).show()
                true
            }
            1003 -> {
                Toast.makeText(this, "Clearing cache...", Toast.LENGTH_SHORT).show()
                viewModel.clearCache { _, msg ->
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }



    // ─────────────────────────────────────────────────────────────────────
    // UI Setup
    // ─────────────────────────────────────────────────────────────────────

    /** Toggle which format RadioGroup is visible based on the selected mode. */
    private fun setupModeSelector() {
        binding.radioGroupMode.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.radioVideo -> showVideoFormats()
                R.id.radioMusicPlaylist, R.id.radioOneMusic -> showAudioFormats()
            }
        }
        // Default: video mode
        showVideoFormats()
    }

    private fun showVideoFormats() {
        binding.labelFormat.text = getString(R.string.label_resolution)
        binding.radioGroupVideo.visibility = View.VISIBLE
        binding.radioGroupAudio.visibility = View.GONE
    }

    private fun showAudioFormats() {
        binding.labelFormat.text = getString(R.string.label_audio_format)
        binding.radioGroupVideo.visibility = View.GONE
        binding.radioGroupAudio.visibility = View.VISIBLE
    }

    /** Reads clipboard and pastes into the URL field. */
    private fun setupPasteButton() {
        binding.btnPaste.setOnClickListener {
            // Safe cast prevents crashes if the system service is temporarily unavailable
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
            if (clipboard == null) {
                Toast.makeText(this, "Clipboard service unavailable", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val item = clip.getItemAt(0)
                // Safely grab the text property first, fallback to coerceToText only if necessary
                val text = item.text?.toString() ?: item.coerceToText(this).toString()

                if (text.isNotEmpty()) {
                    binding.urlEditText.setText(text)
                    // Move cursor to end
                    binding.urlEditText.setSelection(text.length)
                } else {
                    Toast.makeText(this, getString(R.string.err_clipboard_empty), Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, getString(R.string.err_clipboard_empty), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupDownloadButton() {
        binding.btnDownload.setOnClickListener {
            val url = binding.urlEditText.text?.toString()?.trim() ?: ""
            if (url.isEmpty()) {
                binding.urlInputLayout.error = getString(R.string.err_url_empty)
                return@setOnClickListener
            }
            binding.urlInputLayout.error = null

            // Notification permission on Android 13+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                checkStorageAndDownload()
            }
        }
    }

    private fun checkStorageAndDownload() {
        // Request storage permission on Android 9 and below
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            triggerDownload()
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Download trigger
    // ─────────────────────────────────────────────────────────────────────

    private fun triggerDownload() {
        val url = binding.urlEditText.text?.toString()?.trim() ?: return
        val mode = buildSelectedMode()
        val embedOptions = buildEmbedOptions()

        viewModel.startDownload(url, mode, embedOptions)
    }

    /** Reads the RadioGroups and constructs the appropriate DownloadMode. */
    private fun buildSelectedMode(): DownloadMode {
        return when (binding.radioGroupMode.checkedRadioButtonId) {
            R.id.radioMusicPlaylist -> DownloadMode.MusicPlaylist(selectedAudioFormat())
            R.id.radioOneMusic      -> DownloadMode.OneMusic(selectedAudioFormat())
            R.id.radioVideoPlaylist -> DownloadMode.VideoPlaylist(selectedVideoResolution())
            else /* radioVideo */   -> DownloadMode.Video(selectedVideoResolution())
        }
    }

    private fun selectedVideoResolution(): VideoResolution {
        return when (binding.radioGroupVideo.checkedRadioButtonId) {
            R.id.radioV1080 -> VideoResolution.P1080
            R.id.radioV1440 -> VideoResolution.P1440
            R.id.radioV2160 -> VideoResolution.P2160
            R.id.radioVBest -> VideoResolution.BEST
            else            -> VideoResolution.P720   // default
        }
    }

    private fun selectedAudioFormat(): AudioFormat {
        return when (binding.radioGroupAudio.checkedRadioButtonId) {
            R.id.radioAMp3  -> AudioFormat.MP3
            R.id.radioAWebm -> AudioFormat.WEBM
            R.id.radioAOpus -> AudioFormat.OPUS
            else            -> AudioFormat.M4A   // default
        }
    }

    /** Reads the chip states and returns an EmbedOptions. */
    private fun buildEmbedOptions(): EmbedOptions {
        return EmbedOptions(
            embedChapters = binding.chipChapters.isChecked,
            embedThumbnail = binding.chipThumbnail.isChecked,
            embedSubs = binding.chipSubs.isChecked,
            embedMetadata = binding.chipMetadata.isChecked
        )
    }

    // ─────────────────────────────────────────────────────────────────────
    // Observers
    // ─────────────────────────────────────────────────────────────────────

    private fun observeDownloadState() {
        viewModel.state.observe(this) { state ->
            when (state) {
                is DownloadState.Idle -> {
                    binding.btnDownload.isEnabled = true
                    binding.btnDownload.text = getString(R.string.btn_download)
                    binding.cardProgress.visibility = View.GONE
                }

                is DownloadState.Downloading -> {
                    binding.btnDownload.isEnabled = false
                    binding.btnDownload.text = getString(R.string.btn_downloading)
                    binding.cardProgress.visibility = View.VISIBLE
                    binding.tvStatus.text = getString(R.string.status_downloading)
                    binding.tvEta.text = state.eta
                    binding.progressBar.progress = state.progress.toInt()
                }

                is DownloadState.Done -> {
                    binding.btnDownload.isEnabled = true
                    binding.btnDownload.text = getString(R.string.btn_download)
                    binding.tvStatus.text = state.message
                    binding.tvEta.text = ""
                    binding.progressBar.progress = 100
                    // Re-enable after showing success
                }

                is DownloadState.Error -> {
                    binding.btnDownload.isEnabled = true
                    binding.btnDownload.text = getString(R.string.btn_download)
                    binding.tvStatus.text = getString(R.string.status_error)
                    binding.tvEta.text = ""
                    Toast.makeText(this, state.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun observeLog() {
        viewModel.newLogLine.observe(this) { newLine ->
            if (binding.tvLog.text.isNullOrEmpty()) {
                binding.tvLog.text = newLine
            } else {
                binding.tvLog.append("\n$newLine")
            }

            // Detect if the user has manually scrolled up. If they are looking at older logs,
            // we do NOT force scroll to the bottom. If they are already at the bottom, auto-scroll with new lines.
            val scrollY = binding.logScrollView.scrollY
            val maxScrollY = (binding.tvLog.height - binding.logScrollView.height).coerceAtLeast(0)
            
            // Allow a small threshold (e.g. 50 pixels) in case of precise scroll position calculations
            val isAtBottom = scrollY >= (maxScrollY - 50)

            if (isAtBottom) {
                binding.logScrollView.post {
                    binding.logScrollView.fullScroll(View.FOCUS_DOWN)
                }
            }
        }
    }
}