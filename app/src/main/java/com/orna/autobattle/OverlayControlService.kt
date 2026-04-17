package com.orna.autobattle

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.Log
import android.view.*
import android.view.WindowManager.LayoutParams.*
import android.widget.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

private const val TAG = "OverlayService"
private const val NOTIF_ID = 1
private const val CHANNEL_ID = "orna_auto"
private const val CAPTURE_INTERVAL_MS = 800L

class OverlayControlService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        fun start(ctx: Context, resultCode: Int, data: Intent) {
            val i = Intent(ctx, OverlayControlService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, OverlayControlService::class.java))
        }
    }

    private val engine = AutoBattleEngine()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var captureJob: Job? = null
    private var running = false

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var overlayParams: WindowManager.LayoutParams

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    // UI refs
    private lateinit var tvStatusDot: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvStats: TextView
    private lateinit var tvTemplateCount: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnSettings: Button
    private lateinit var expandedPanel: View
    private lateinit var btnCapture: Button
    private lateinit var cbAutoBuy: CheckBox
    private lateinit var cbBoosterExp: CheckBox
    private lateinit var cbBoosterAffinity: CheckBox
    private lateinit var cbBoosterLuckyCoin: CheckBox
    private lateinit var cbBoosterLuckySilver: CheckBox
    private lateinit var cbBoosterOccult: CheckBox
    private lateinit var cbBoosterHallowed: CheckBox
    private lateinit var cbBoosterDowse: CheckBox
    private lateinit var cbBoosterTorch: CheckBox

    private var captureNextFrame = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification("Overlay ready"))

        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

        if (resultData != null && resultCode == Activity.RESULT_OK) {
            setupMediaProjection(resultCode, resultData)
        }

        TemplateManager.init(this)

        try {
            setupOverlay()
        } catch (e: Exception) {
            Log.e(TAG, "setupOverlay failed: ${e.message}")
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun setupMediaProjection(resultCode: Int, data: Intent) {
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mgr.getMediaProjection(resultCode, data)
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection!!.createVirtualDisplay(
            "OrnaCapture",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, null
        )
        Log.d(TAG, "MediaProjection ready ${screenWidth}x${screenHeight}")
    }

    private fun setupOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_controls, null)

        overlayParams = WindowManager.LayoutParams(
            WRAP_CONTENT, WRAP_CONTENT,
            TYPE_APPLICATION_OVERLAY,
            FLAG_NOT_FOCUSABLE or FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 16; y = 200
        }

        tvStatusDot    = overlayView.findViewById(R.id.tvStatusDot)
        tvStatus       = overlayView.findViewById(R.id.tvOverlayStatus)
        tvStats        = overlayView.findViewById(R.id.tvStats)
        tvTemplateCount= overlayView.findViewById(R.id.tvTemplateCount)
        btnToggle      = overlayView.findViewById(R.id.btnToggle)
        btnSettings    = overlayView.findViewById(R.id.btnSettings)
        expandedPanel  = overlayView.findViewById(R.id.expandedPanel)
        btnCapture     = overlayView.findViewById(R.id.btnCapture)
        cbAutoBuy           = overlayView.findViewById(R.id.cbAutoBuy)
        cbBoosterExp        = overlayView.findViewById(R.id.cbBoosterExp)
        cbBoosterAffinity   = overlayView.findViewById(R.id.cbBoosterAffinity)
        cbBoosterLuckyCoin  = overlayView.findViewById(R.id.cbBoosterLuckyCoin)
        cbBoosterLuckySilver= overlayView.findViewById(R.id.cbBoosterLuckySilver)
        cbBoosterOccult     = overlayView.findViewById(R.id.cbBoosterOccult)
        cbBoosterHallowed   = overlayView.findViewById(R.id.cbBoosterHallowed)
        cbBoosterDowse      = overlayView.findViewById(R.id.cbBoosterDowse)
        cbBoosterTorch      = overlayView.findViewById(R.id.cbBoosterTorch)

        val spinner = overlayView.findViewById<Spinner>(R.id.spinnerStrategy)
        spinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, BattleStrategy.labels()
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                engine.strategy = BattleStrategy.fromIndex(pos)
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) = Unit
        }

        cbAutoBuy.setOnCheckedChangeListener { _, checked -> engine.autoBuy = checked }

        val boosterListener = CompoundButton.OnCheckedChangeListener { _, _ -> rebuildBoosterSettings() }
        listOf(cbBoosterExp, cbBoosterAffinity, cbBoosterLuckyCoin, cbBoosterLuckySilver,
               cbBoosterOccult, cbBoosterHallowed, cbBoosterDowse, cbBoosterTorch)
            .forEach { it.setOnCheckedChangeListener(boosterListener) }
        rebuildBoosterSettings()

        btnToggle.setOnClickListener { if (running) stopBattle() else startBattle() }

        btnSettings.setOnClickListener {
            expandedPanel.visibility =
                if (expandedPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            windowManager.updateViewLayout(overlayView, overlayParams)
        }

        btnCapture.setOnClickListener { captureNextFrame = true; tvStatus.text = "Capturing…" }

        refreshTemplateCount()

        // Drag via left area of bar
        var initX = 0; var initY = 0; var initTouchX = 0f; var initTouchY = 0f
        overlayView.findViewById<View>(R.id.dragArea).setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = overlayParams.x; initY = overlayParams.y
                    initTouchX = ev.rawX; initTouchY = ev.rawY; true
                }
                MotionEvent.ACTION_MOVE -> {
                    overlayParams.x = initX + (ev.rawX - initTouchX).toInt()
                    overlayParams.y = initY + (ev.rawY - initTouchY).toInt()
                    windowManager.updateViewLayout(overlayView, overlayParams); true
                }
                else -> false
            }
        }

        windowManager.addView(overlayView, overlayParams)
    }

    private fun rebuildBoosterSettings() {
        val enabled = mutableSetOf<BoosterItem>()
        if (cbBoosterExp.isChecked)         enabled.add(BoosterItem.EXP_POTION)
        if (cbBoosterAffinity.isChecked)    enabled.add(BoosterItem.AFFINITY_CANDLE)
        if (cbBoosterLuckyCoin.isChecked)   enabled.add(BoosterItem.LUCKY_COIN)
        if (cbBoosterLuckySilver.isChecked) enabled.add(BoosterItem.LUCKY_SILVER)
        if (cbBoosterOccult.isChecked)      enabled.add(BoosterItem.OCCULT_CANDLE)
        if (cbBoosterHallowed.isChecked)    enabled.add(BoosterItem.HALLOWED_CANDLE)
        if (cbBoosterDowse.isChecked)       enabled.add(BoosterItem.DOWSING_ROD)
        if (cbBoosterTorch.isChecked)       enabled.add(BoosterItem.TORCH)
        engine.boosterSettings = BoosterSettings(enabled, engine.boosterSettings.reapplyEvery)
    }

    // ── Battle loop ───────────────────────────────────────────────────────────

    private fun startBattle() {
        if (!AutoBattleAccessibilityService.isRunning()) {
            tvStatus.text = "Enable Accessibility!"
            tvStatusDot.setTextColor(Color.parseColor("#FF4444"))
            return
        }
        running = true
        btnToggle.text = "■"
        tvStatus.text = "Running"
        tvStatusDot.setTextColor(Color.parseColor("#44FF44"))
        updateNotification("Auto-battle running")
        engine.requestBoosterApply()

        captureJob = serviceScope.launch {
            while (running) {
                try {
                    captureAndProcess()
                } catch (e: Exception) {
                    Log.e(TAG, "captureAndProcess error: ${e.message}")
                }
                delay(CAPTURE_INTERVAL_MS)
            }
        }
    }

    private fun stopBattle() {
        running = false
        captureJob?.cancel()
        captureJob = null
        btnToggle.text = "▶"
        tvStatus.text = "Stopped"
        tvStatusDot.setTextColor(Color.parseColor("#888888"))
        updateNotification("Overlay active — stopped")
    }

    // ── Capture + analysis ────────────────────────────────────────────────────

    private suspend fun captureAndProcess() {
        val bmp = withContext(Dispatchers.IO) { latestBitmap() } ?: return

        if (captureNextFrame) {
            captureNextFrame = false
            val saved = withContext(Dispatchers.Default) {
                val monsters = ScreenAnalyzer.findMonsters(bmp, maxTargets = 1)
                val cropX = monsters.firstOrNull()?.x ?: (screenWidth / 2)
                val cropY = monsters.firstOrNull()?.y ?: (screenHeight / 2)
                TemplateManager.captureFromFrame(this@OverlayControlService, bmp, cropX, cropY, size = 28)
            }
            bmp.recycle()
            tvStatus.text = if (saved != null) "Saved: ${saved.name}" else "Capture failed"
            refreshTemplateCount()
            return
        }

        val action = withContext(Dispatchers.Default) {
            engine.tick(bmp, screenWidth, screenHeight)
        }
        bmp.recycle()

        tvStatus.text = when (engine.state) {
            AutoBattleEngine.State.HUNTING            -> "Hunting"
            AutoBattleEngine.State.HEALING            -> "Healing"
            AutoBattleEngine.State.BATTLE             -> "Battle"
            AutoBattleEngine.State.BATTLE_USING_ITEM  -> "Battle: healing"
            AutoBattleEngine.State.BATTLE_FLEEING     -> "Fleeing"
            AutoBattleEngine.State.PRE_BATTLE         -> "Pre-battle"
            AutoBattleEngine.State.PRE_BATTLE_BERSERK -> "Berserk!"
            AutoBattleEngine.State.VICTORY            -> "Victory"
            AutoBattleEngine.State.CHECKING_BUFFS     -> "Checking buffs"
            AutoBattleEngine.State.APPLYING_BOOSTERS  -> "Boosting"
            AutoBattleEngine.State.FINDING_SHOP       -> "Finding shop"
            AutoBattleEngine.State.IN_SHOP            -> "In shop"
            AutoBattleEngine.State.IN_BUY_DIALOG      -> "Buying potions"
            AutoBattleEngine.State.DISMISSING         -> "Dismissing"
        }
        tvStats.text = "W:${engine.battlesWon}  L:${engine.battlesLost}"

        val svc = AutoBattleAccessibilityService.instance
        when (action) {
            is AutoBattleEngine.Action.Tap ->
                svc?.performTap(action.x, action.y)
            is AutoBattleEngine.Action.LongPress ->
                svc?.performTap(action.x, action.y, action.ms)
            is AutoBattleEngine.Action.Swipe ->
                svc?.performSwipe(action.x1, action.y1, action.x2, action.y2, action.durationMs)
            is AutoBattleEngine.Action.Wait -> Unit
            is AutoBattleEngine.Action.Nothing -> Unit
        }
    }

    private fun refreshTemplateCount() {
        val n = TemplateManager.count()
        tvTemplateCount.text = if (n == 0) "Sprites: 0 (fallback)" else "Sprites: $n"
    }

    private fun latestBitmap(): Bitmap? {
        val reader = imageReader ?: return null
        val image = reader.acquireLatestImage() ?: return null
        return try {
            val plane = image.planes[0]
            val bmp = Bitmap.createBitmap(
                plane.rowStride / plane.pixelStride, screenHeight, Bitmap.Config.ARGB_8888
            )
            bmp.copyPixelsFromBuffer(plane.buffer)
            if (bmp.width != screenWidth)
                Bitmap.createBitmap(bmp, 0, 0, screenWidth, screenHeight)
            else bmp
        } finally {
            image.close()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopBattle()
        serviceScope.cancel()
        if (::overlayView.isInitialized) {
            try { windowManager.removeView(overlayView) } catch (_: Exception) {}
        }
        virtualDisplay?.release()
        mediaProjection?.stop()
        imageReader?.close()
    }

    override fun onBind(intent: Intent?) = null

    private fun buildNotification(text: String = "Overlay active"): Notification {
        val chan = NotificationChannel(CHANNEL_ID, "Auto Battle", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(chan)
        // Tap notification to bring app back
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Orna Auto Battle")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(text))
    }
}
