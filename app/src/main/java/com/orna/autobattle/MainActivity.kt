package com.orna.autobattle

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var btnOverlay: Button
    private lateinit var btnAccessibility: Button
    private lateinit var btnCapture: Button
    private lateinit var btnLaunch: Button
    private lateinit var btnDownload: Button
    private lateinit var btnCheckNew: Button
    private lateinit var tvDownloadProgress: TextView

    private var captureResultCode = Activity.RESULT_CANCELED
    private var captureData: Intent? = null

    private val SCREEN_CAPTURE_REQUEST = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        btnOverlay = findViewById(R.id.btnOverlayPerm)
        btnAccessibility = findViewById(R.id.btnAccessibility)
        btnCapture = findViewById(R.id.btnScreenCapture)
        btnLaunch = findViewById(R.id.btnLaunchOverlay)
        btnDownload = findViewById(R.id.btnDownloadSprites)
        btnCheckNew = findViewById(R.id.btnCheckNew)
        tvDownloadProgress = findViewById(R.id.tvDownloadProgress)

        btnOverlay.setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
        }

        btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnCapture.setOnClickListener {
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mgr.createScreenCaptureIntent(), SCREEN_CAPTURE_REQUEST)
        }

        btnLaunch.setOnClickListener {
            captureData?.let { data ->
                OverlayControlService.start(this, captureResultCode, data)
                // Launch Orna RPG so the overlay is immediately over the game.
                // FLAG_ACTIVITY_REORDER_TO_FRONT brings it forward if already running.
                val ornaIntent = packageManager.getLaunchIntentForPackage("playorna.com.orna")
                    ?: packageManager.getLaunchIntentForPackage("orna.rpg.mobile")
                if (ornaIntent != null) {
                    ornaIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(ornaIntent)
                } else {
                    android.widget.Toast.makeText(this, "Orna RPG not found — open it manually", android.widget.Toast.LENGTH_LONG).show()
                }
                finish()
            }
        }

        btnDownload.setOnClickListener {
            setDownloadBtnsEnabled(false)
            lifecycleScope.launch {
                CodexFetcher.fetchAll(
                    ctx = this@MainActivity,
                    onProgress = { p ->
                        tvDownloadProgress.text =
                            if (p.total > 0) "${p.done}/${p.total} — ${p.current}"
                            else p.current
                    },
                    onDone = { downloaded, total ->
                        tvDownloadProgress.text = "Done: $downloaded/$total sprites saved"
                        setDownloadBtnsEnabled(true)
                        TemplateManager.init(this@MainActivity)
                    }
                )
            }
        }

        btnCheckNew.setOnClickListener {
            setDownloadBtnsEnabled(false)
            tvDownloadProgress.text = "Checking for new monsters…"
            lifecycleScope.launch {
                val newMonsters = CodexFetcher.checkForNew(this@MainActivity)
                if (newMonsters.isEmpty()) {
                    tvDownloadProgress.text = "No new monsters found"
                    setDownloadBtnsEnabled(true)
                } else {
                    tvDownloadProgress.text = "${newMonsters.size} new — downloading…"
                    CodexFetcher.downloadNew(
                        ctx = this@MainActivity,
                        newMonsters = newMonsters,
                        onProgress = { p ->
                            tvDownloadProgress.text = "${p.done}/${p.total} — ${p.current}"
                        },
                        onDone = { downloaded, total ->
                            tvDownloadProgress.text = "Done: +$downloaded new sprites"
                            setDownloadBtnsEnabled(true)
                            TemplateManager.init(this@MainActivity)
                        }
                    )
                }
            }
        }

        // Runtime permissions (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(
                android.Manifest.permission.POST_NOTIFICATIONS,
                android.Manifest.permission.READ_MEDIA_IMAGES
            ), 200)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE), 201)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshUI()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SCREEN_CAPTURE_REQUEST && resultCode == Activity.RESULT_OK && data != null) {
            captureResultCode = resultCode
            captureData = data
            refreshUI()
        }
    }

    private fun setDownloadBtnsEnabled(enabled: Boolean) {
        btnDownload.isEnabled = enabled
        btnCheckNew.isEnabled = enabled
    }

    private fun refreshUI() {
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasAccessibility = AutoBattleAccessibilityService.isRunning()
        val hasCapture = captureData != null

        btnOverlay.isEnabled = !hasOverlay
        btnOverlay.text = if (hasOverlay) "1. Overlay ✓" else "1. Grant Overlay Permission"

        btnAccessibility.isEnabled = !hasAccessibility
        btnAccessibility.text = if (hasAccessibility) "2. Accessibility ✓" else "2. Enable Accessibility Service"

        btnCapture.isEnabled = !hasCapture
        btnCapture.text = if (hasCapture) "3. Screen Capture ✓" else "3. Grant Screen Capture"

        val allReady = hasOverlay && hasAccessibility && hasCapture
        btnLaunch.isEnabled = allReady
        tvStatus.text = when {
            allReady -> "Ready to launch!"
            !hasOverlay -> "Step 1: Grant overlay permission"
            !hasAccessibility -> "Step 2: Enable accessibility service"
            !hasCapture -> "Step 3: Grant screen capture"
            else -> "Setup complete"
        }
    }
}
