package com.ultracam.app

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.MediaActionSound
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.text.InputType
import android.util.Range
import android.util.Size
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraFilter
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.exp
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

@SuppressLint("ClickableViewAccessibility", "SetTextI18n")
class MainActivity : AppCompatActivity() {

    companion object {
        const val ZOOM_MAX = 150f

        /** CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION (Android 13+) */
        private const val STAB_PREVIEW = 2
    }

    private class Ext(val label: String, val mode: Int)
    private class Chip(val tv: TextView, val photo: Boolean, val video: Boolean)
    private class Wb(val label: String, val mode: Int)
    private class VFmt(val label: String, val res: Int, val fps: Int)

    /** Возможности именно той камеры, что сейчас выбрана. */
    private class Caps {
        var isoMin = 100
        var isoMax = 800
        var expMin = 125_000L
        var expMax = 1_000_000_000L
        var minFocus = 0f
        var awbModes = IntArray(0)
        var eisModes = IntArray(0)
        var oisModes = IntArray(0)
        var fps: Array<Range<Int>> = emptyArray()
        var manualSensor = false
    }

    // ---------- views ----------
    private lateinit var previewView: PreviewView
    private lateinit var previewContainer: FrameLayout
    private lateinit var ivFreeze: ImageView
    private lateinit var scopeView: ScopeView
    private lateinit var gridView: GridOverlayView
    private lateinit var levelView: LevelView
    private lateinit var focusRing: View
    private lateinit var tvZoom: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvTimer: TextView
    private lateinit var tvRec: TextView
    private lateinit var tvHud: TextView
    private lateinit var tvLock: TextView
    private lateinit var proPanel: LinearLayout
    private lateinit var seekExposure: SeekBar
    private lateinit var seekZoom: SeekBar
    private lateinit var btnFlash: TextView
    private lateinit var btnTimer: TextView
    private lateinit var btnGrid: TextView
    private lateinit var btnAi: TextView
    private lateinit var btnMore: TextView
    private lateinit var zoomRow: LinearLayout
    private lateinit var optionsRow: LinearLayout
    private lateinit var btnPhoto: TextView
    private lateinit var btnVideo: TextView
    private lateinit var shutter: FrameLayout
    private lateinit var shutterInner: View
    private lateinit var ivGallery: ImageView
    private lateinit var btnSwitch: TextView
    private lateinit var prefs: SharedPreferences

    // chips
    private lateinit var chipPro: TextView
    private lateinit var chipBurst: TextView
    private lateinit var chipLook: TextView
    private lateinit var chipStab: TextView
    private lateinit var chipVfmt: TextView
    private lateinit var chipFrame: TextView
    private val chips = mutableListOf<Chip>()
    private val extChips = mutableListOf<Pair<Ext, TextView>>()

    // pro panel
    private lateinit var seekEv: SeekBar
    private lateinit var tvEv: TextView
    private lateinit var seekIso: SeekBar
    private lateinit var tvIso: TextView
    private lateinit var seekShut: SeekBar
    private lateinit var tvShut: TextView
    private lateinit var seekFocus: SeekBar
    private lateinit var tvFocus: TextView
    private lateinit var chipMan: TextView
    private lateinit var chipMf: TextView
    private lateinit var chipWb: TextView
    private lateinit var chipAeL: TextView
    private lateinit var chipAwbL: TextView

    // ---------- camera ----------
    private var cameraProvider: ProcessCameraProvider? = null
    private var extensionsManager: ExtensionsManager? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var zoomLive: LiveData<ZoomState>? = null
    private var stateLive: LiveData<CameraState>? = null
    private var caps = Caps()
    private val wbList = mutableListOf<Wb>()

    // ---------- state ----------
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var isVideoMode = false
    private var extMode = ExtensionMode.NONE
    private var flashMode = 0 // 0 выкл, 1 авто, 2 вкл, 3 фонарь
    private var timerSec = 0

    // zoom
    private var effZoom = 1f
    private var swZoom = 1f            // только цифровая часть (для обрезки фото)
    private var hwMin = 1f
    private var hwMax = 1f
    private var mainMin = 1f           // минимальный зум основной камеры (если < 1 — 0.5× даёт сама камера)
    private var uwCandidate = false    // нашли отдельную сверхширокую камеру
    private var wideFactor = 0.5f
    private var useWideLens = false
    private var lensBusy = false
    private var pendingZoom = 1f
    private var zoomAnim: ValueAnimator? = null
    private var seekTouch = false
    private var lastActivePill = -1
    private var freezeActive = false
    private var freezeAt = 0L
    private var horizonSm = 0f

    private var expLower = 0
    private var expStep = 1f
    private var busy = false
    private var pinched = false
    private var lastUri: Uri? = null

    // settings
    private var enhanceOn = true
    private var burstMode = 0          // 0 один кадр, 1 умный (4), 2 стек (8)
    private var lookIdx = 0
    private var stabLevel = 1          // 0 выкл, 1 обычная, 2 супер
    private var stabFallbackDone = false
    private var levelOn = true
    private var gridMode = 0
    private var guideIdx = 0
    private var zebraOn = false
    private var peakOn = false
    private var histOn = false
    private var falseOn = false
    private var hudOn = true
    private var shutterSound = true
    private var steadyOn = false
    private var volZoom = false
    private var horizonOn = false
    private var vfmtIdx = 4
    private var aiStrengthIdx = 0

    // pro
    private var proOpen = false
    private var manualExp = false
    private var isoVal = 100
    private var expNs = 10_000_000L
    private var manualFocus = false
    private var focusDiopt = 0f
    private var wbIdx = 0
    private var aeLock = false
    private var awbLock = false
    private var tapLocked = false

    // live values from camera
    @Volatile private var liveIso = 100
    @Volatile private var liveExp = 10_000_000L
    @Volatile private var liveFocus = -1f
    @Volatile private var liveVs = -1
    @Volatile private var liveOis = -1

    @Volatile private var upscaler: TfliteUpscaler? = null
    @Volatile private var aiInfo = "модель не загружена"

    private val zoomPresets = floatArrayOf(0.5f, 1f, 2f, 5f, 10f, 50f, 100f, 150f)
    private val zoomPills = mutableListOf<TextView>()
    private val stabNames = arrayOf("Выкл", "Обычная", "Супер")
    private val burstNames = arrayOf("Один кадр", "Умный ×4", "Стек ×8")
    private val guideNames = arrayOf("Рамка: выкл", "1:1", "4:5", "9:16", "2.39")
    private val guideRatios = floatArrayOf(0f, 1f, 0.8f, 0.5625f, 0.4184f)
    private val aiStrengths = floatArrayOf(1f, 0.7f, 0.4f)
    private val videoFormats = listOf(
        VFmt("4K · 30", 0, 30), VFmt("4K · 60", 0, 60), VFmt("4K · 24", 0, 24),
        VFmt("1080p · 60", 1, 60), VFmt("1080p · 30", 1, 30), VFmt("1080p · 24", 1, 24),
        VFmt("720p · 30", 2, 30)
    )

    private val ui = Handler(Looper.getMainLooper())
    private val hideSeek = Runnable { seekExposure.visibility = View.GONE }
    private val hideZoomSeek = Runnable { seekZoom.visibility = View.GONE }
    private val hideFocus = Runnable { focusRing.visibility = View.GONE }
    private val freezeTimeout = Runnable { releaseFreeze() }
    private val yellow = Color.parseColor("#FFD600")

    private val motion by lazy { MotionTracker(this) }
    private val sound by lazy {
        MediaActionSound().apply {
            load(MediaActionSound.SHUTTER_CLICK)
            load(MediaActionSound.START_VIDEO_RECORDING)
            load(MediaActionSound.STOP_VIDEO_RECORDING)
        }
    }

    // ---------- observers / callbacks ----------
    private val zoomObserver = Observer<ZoomState> { zs ->
        if (zs.minZoomRatio != hwMin || zs.maxZoomRatio != hwMax) {
            hwMin = zs.minZoomRatio
            hwMax = zs.maxZoomRatio
            if (!useWideLens) mainMin = hwMin
            applyZoom(effZoom)
        }
    }

    private val camStateObserver = Observer<CameraState> { st ->
        val err = st.error
        if (err != null && isVideoMode && stabLevel == 2 && !stabFallbackDone) {
            stabFallbackDone = true
            stabLevel = 1
            prefs.edit().putInt("stab", 1).apply()
            refreshChipTexts()
            toast("Супер-стабилизация не запустилась — включил обычную")
            bindCamera()
        }
    }

    private val captureCb = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult
        ) {
            liveIso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: liveIso
            liveExp = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: liveExp
            liveFocus = result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: liveFocus
            liveVs = result.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE) ?: -1
            liveOis = result.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE) ?: -1
        }
    }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.CAMERA] == true) startCamera() else {
            toast("Без доступа к камере приложение не работает")
            finish()
        }
    }

    private val pickModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) installModel(uri)
    }

    private val orientationListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val rot = when (orientation) {
                    in 45..134 -> Surface.ROTATION_270
                    in 135..224 -> Surface.ROTATION_180
                    in 225..314 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                imageCapture?.targetRotation = rot
                videoCapture?.targetRotation = rot
            }
        }
    }

    private val hudTick = object : Runnable {
        override fun run() {
            updateHud()
            ui.postDelayed(this, 250)
        }
    }

    private val horizonTick = object : Runnable {
        override fun run() {
            val active = horizonOn && !isVideoMode && lensFacing == CameraSelector.LENS_FACING_BACK
            var target = 0f
            if (active) {
                val r = motion.rollDeg()
                if (abs(r) <= 20f) target = -r
            }
            horizonSm += (target - horizonSm) * 0.25f
            if (target == 0f && abs(horizonSm) < 0.05f) horizonSm = 0f
            applyViewTransform()
            ui.postDelayed(this, 33)
        }
    }

    private var scopeLoopOn = false
    private var scopeBusy = false
    private val scopeTick = object : Runnable {
        override fun run() {
            if (scopesActive()) {
                if (!scopeBusy) grabScope()
                ui.postDelayed(this, 200)
            } else {
                scopeLoopOn = false
            }
        }
    }

    // =====================================================================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("ultracam", MODE_PRIVATE)
        enhanceOn = prefs.getBoolean("enhance", true)
        burstMode = prefs.getInt("burst", 0).coerceIn(0, 2)
        lookIdx = prefs.getInt("look", 0).coerceIn(0, Look.ALL.size - 1)
        stabLevel = prefs.getInt("stab", 1).coerceIn(0, 2)
        levelOn = prefs.getBoolean("level", true)
        gridMode = prefs.getInt("gridMode", 0).coerceIn(0, 4)
        guideIdx = prefs.getInt("guide", 0).coerceIn(0, guideRatios.size - 1)
        zebraOn = prefs.getBoolean("zebra", false)
        peakOn = prefs.getBoolean("peak", false)
        histOn = prefs.getBoolean("hist", false)
        falseOn = prefs.getBoolean("false", false)
        hudOn = prefs.getBoolean("hud", true)
        shutterSound = prefs.getBoolean("sound", true)
        steadyOn = prefs.getBoolean("steady", false)
        volZoom = prefs.getBoolean("volzoom", false)
        horizonOn = prefs.getBoolean("horizon", false)
        vfmtIdx = prefs.getInt("vfmt", 4).coerceIn(0, videoFormats.size - 1)
        aiStrengthIdx = prefs.getInt("aistr", 0).coerceIn(0, aiStrengths.size - 1)

        bindViews()
        buildZoomRow()
        buildOptionsRow()
        buildProPanel()
        setupTouch()
        setupButtons()
        loadAiModel()

        previewView.previewStreamState.observe(this) { st ->
            if (st == PreviewView.StreamState.STREAMING && freezeActive) {
                val wait = max(0L, 160L - (SystemClock.uptimeMillis() - freezeAt))
                ui.postDelayed({ releaseFreeze() }, wait)
            }
        }
        refreshChipTexts()
        ensureScopeLoop()

        if (granted(Manifest.permission.CAMERA)) startCamera()
        else permLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }

    override fun onResume() {
        super.onResume()
        orientationListener.enable()
        motion.start()
        ui.post(hudTick)
        ui.post(horizonTick)
    }

    override fun onPause() {
        orientationListener.disable()
        motion.stop()
        ui.removeCallbacks(hudTick)
        ui.removeCallbacks(horizonTick)
        super.onPause()
    }

    override fun onDestroy() {
        zoomAnim?.cancel()
        cameraExecutor.shutdown()
        upscaler?.close()
        try { sound.release() } catch (_: Throwable) {}
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (volZoom) {
                zoomAnim?.cancel()
                val f = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) 1.04f else 1f / 1.04f
                setZoomNow(effZoom * f)
            } else if (event?.repeatCount == 0) {
                onShutter()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun bindViews() {
        previewView = findViewById(R.id.previewView)
        previewContainer = findViewById(R.id.previewContainer)
        ivFreeze = findViewById(R.id.ivFreeze)
        scopeView = findViewById(R.id.scopeView)
        gridView = findViewById(R.id.gridView)
        levelView = findViewById(R.id.levelView)
        focusRing = findViewById(R.id.focusRing)
        tvZoom = findViewById(R.id.tvZoom)
        tvStatus = findViewById(R.id.tvStatus)
        tvTimer = findViewById(R.id.tvTimer)
        tvRec = findViewById(R.id.tvRec)
        tvHud = findViewById(R.id.tvHud)
        tvLock = findViewById(R.id.tvLock)
        proPanel = findViewById(R.id.proPanel)
        seekExposure = findViewById(R.id.seekExposure)
        seekZoom = findViewById(R.id.seekZoom)
        btnFlash = findViewById(R.id.btnFlash)
        btnTimer = findViewById(R.id.btnTimer)
        btnGrid = findViewById(R.id.btnGrid)
        btnAi = findViewById(R.id.btnAi)
        btnMore = findViewById(R.id.btnMore)
        zoomRow = findViewById(R.id.zoomRow)
        optionsRow = findViewById(R.id.optionsRow)
        btnPhoto = findViewById(R.id.btnPhoto)
        btnVideo = findViewById(R.id.btnVideo)
        shutter = findViewById(R.id.shutter)
        shutterInner = findViewById(R.id.shutterInner)
        ivGallery = findViewById(R.id.ivGallery)
        btnSwitch = findViewById(R.id.btnSwitch)

        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
        gridView.gridMode = gridMode
        gridView.guideRatio = guideRatios[guideIdx]
        btnGrid.alpha = if (gridMode == 0) 0.5f else 1f
        levelView.enabledLevel = levelOn
        ivGallery.background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(Color.parseColor("#333333"))
        }
        ivGallery.clipToOutline = true
        tintSeek(seekExposure)
        tintSeek(seekZoom)
    }

    // =====================================================================
    // UI helpers
    // =====================================================================
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun toastUi(s: String) = runOnUiThread { toast(s) }
    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    private fun f1(v: Float) = String.format(Locale.US, "%.1f", v)

    private fun tintSeek(s: SeekBar) {
        s.progressTintList = ColorStateList.valueOf(yellow)
        s.thumbTintList = ColorStateList.valueOf(yellow)
    }

    private fun showStatus(t: String) {
        tvStatus.text = t
        tvStatus.visibility = View.VISIBLE
    }

    private fun hideStatus() {
        tvStatus.visibility = View.GONE
    }

    private fun styleChip(tv: TextView, on: Boolean) {
        tv.background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(if (on) yellow else Color.parseColor("#55FFFFFF"))
        }
        tv.setTextColor(if (on) Color.BLACK else Color.WHITE)
    }

    private fun makeChip(text: String, onClick: () -> Unit): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 13f
        tv.gravity = Gravity.CENTER
        tv.setPadding(dp(12), dp(6), dp(12), dp(6))
        tv.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(6) }
        tv.setOnClickListener { onClick() }
        styleChip(tv, false)
        return tv
    }

    private fun addChip(label: String, photo: Boolean, video: Boolean, onClick: () -> Unit): TextView {
        val tv = makeChip(label, onClick)
        chips.add(Chip(tv, photo, video))
        optionsRow.addView(tv)
        return tv
    }

    private fun addToggleChip(
        label: String, photo: Boolean, video: Boolean, initial: Boolean, onChange: (Boolean) -> Unit
    ): TextView {
        var state = initial
        val tv = addChip(label, photo, video) {}
        styleChip(tv, state)
        tv.setOnClickListener {
            state = !state
            styleChip(tv, state)
            onChange(state)
        }
        return tv
    }

    private fun buildOptionsRow() {
        val defs = listOf(
            Ext("Обычный", ExtensionMode.NONE),
            Ext("HDR", ExtensionMode.HDR),
            Ext("Ночь", ExtensionMode.NIGHT),
            Ext("Портрет", ExtensionMode.BOKEH),
            Ext("Красота", ExtensionMode.FACE_RETOUCH)
        )
        for (e in defs) {
            val tv = makeChip(e.label) {
                extMode = e.mode
                bindCamera()
            }
            extChips.add(Pair(e, tv))
            optionsRow.addView(tv)
        }
        chipPro = addChip("🎛 PRO", true, true) { toggleProPanel() }
        addToggleChip("✨ Улучшение", true, false, enhanceOn) {
            enhanceOn = it
            prefs.edit().putBoolean("enhance", it).apply()
        }
        chipBurst = addChip("", true, false) {
            burstMode = (burstMode + 1) % 3
            prefs.edit().putInt("burst", burstMode).apply()
            refreshChipTexts()
            if (burstMode == 2) toast("Стек работает с цифрового зума от ~3×; ниже — берётся лучший кадр")
        }
        chipLook = addChip("", true, false) {
            lookIdx = (lookIdx + 1) % Look.ALL.size
            prefs.edit().putInt("look", lookIdx).apply()
            refreshChipTexts()
        }
        addToggleChip("🧭 Горизонт-лок", true, false, horizonOn) {
            horizonOn = it
            prefs.edit().putBoolean("horizon", it).apply()
            if (it) toast("Горизонт выравнивается в превью и в фото (до ±20°)")
        }
        chipStab = addChip("", true, true) { cycleStab() }
        chipVfmt = addChip("", false, true) { showVideoFormatDialog() }
        addChip("📊 Скопы", true, true) { showScopesDialog() }
        chipFrame = addChip("", true, true) {
            guideIdx = (guideIdx + 1) % guideRatios.size
            prefs.edit().putInt("guide", guideIdx).apply()
            gridView.guideRatio = guideRatios[guideIdx]
            refreshChipTexts()
            if (guideIdx != 0 && !isVideoMode) toast("Фото будут обрезаны по рамке")
        }
        refreshChips()
    }

    private fun refreshChipTexts() {
        chipBurst.text = "🎞 " + burstNames[burstMode]
        chipLook.text = "🎨 " + Look.ALL[lookIdx].name
        chipStab.text = "〰 Стаб: " + stabNames[stabLevel]
        chipVfmt.text = "🎬 " + videoFormats[vfmtIdx].label
        chipFrame.text = "▭ " + guideNames[guideIdx]
        styleChip(chipPro, proOpen)
        styleChip(chipStab, stabLevel > 0)
        styleChip(chipBurst, burstMode > 0)
        styleChip(chipLook, lookIdx > 0)
        styleChip(chipFrame, guideIdx > 0)
    }

    private fun refreshChips() {
        val em = extensionsManager
        val sel = baseSelector()
        for ((e, tv) in extChips) {
            val avail = !isVideoMode && (e.mode == ExtensionMode.NONE || (em != null && safeAvail(em, sel, e.mode)))
            tv.visibility = if (avail) View.VISIBLE else View.GONE
            styleChip(tv, e.mode == extMode)
        }
        for (c in chips) {
            c.tv.visibility = if ((c.photo && !isVideoMode) || (c.video && isVideoMode)) View.VISIBLE else View.GONE
        }
    }

    private fun safeAvail(em: ExtensionsManager, sel: CameraSelector, mode: Int): Boolean =
        try { em.isExtensionAvailable(sel, mode) } catch (e: Exception) { false }

    private fun cycleStab() {
        stabLevel = (stabLevel + 1) % 3
        stabFallbackDone = false
        if (stabLevel == 2 && isVideoMode && !(Build.VERSION.SDK_INT >= 33 && caps.eisModes.contains(STAB_PREVIEW))) {
            toast("«Супер» на этом телефоне = обычная (нужен Android 13+ и поддержка камеры)")
        }
        prefs.edit().putInt("stab", stabLevel).apply()
        refreshChipTexts()
        if (isVideoMode) bindCamera() else applyCamera2Options()
    }

    // =====================================================================
    // Zoom row
    // =====================================================================
    private fun presetLabel(z: Float): String = if (z < 1f) "0.5" else if (z == 1f) "1×" else "${z.toInt()}"

    private fun fmtZoom(z: Float): String =
        if (z < 10f) String.format(Locale.US, "%.1f×", z) else String.format(Locale.US, "%.0f×", z)

    private fun buildZoomRow() {
        for (z in zoomPresets) {
            val tv = TextView(this)
            tv.text = presetLabel(z)
            tv.textSize = 12f
            tv.gravity = Gravity.CENTER
            tv.minWidth = dp(34)
            tv.setPadding(dp(5), 0, dp(5), 0)
            tv.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(34)
            ).apply {
                marginStart = dp(2)
                marginEnd = dp(2)
            }
            tv.setOnClickListener { onPreset(z) }
            zoomPills.add(tv)
            zoomRow.addView(tv)
        }
        updateZoomUi()
    }

    private fun onPreset(z: Float) {
        if (z < 0.99f && minTotal() > 0.99f) {
            toast("0.5× недоступен: телефон не отдаёт сверхширокую камеру. Смотри ⚙ → О камерах")
            return
        }
        animateZoomTo(z)
    }

    private fun updateZoomUi() {
        tvZoom.text = fmtZoom(effZoom) + (if (swZoom > 1.01f) "  цифровой" else "")
        var active = 0
        for (i in zoomPresets.indices) if (effZoom >= zoomPresets[i] * 0.97f) active = i
        for (i in zoomPresets.indices) {
            val z = zoomPresets[i]
            zoomPills[i].text =
                if (i == active && abs(ln(effZoom / z)) > 0.03f) fmtZoom(effZoom) else presetLabel(z)
        }
        if (active != lastActivePill) {
            lastActivePill = active
            for (i in zoomPresets.indices) {
                zoomPills[i].background = GradientDrawable().apply {
                    cornerRadius = dp(17).toFloat()
                    setColor(if (i == active) yellow else Color.parseColor("#66000000"))
                }
                zoomPills[i].setTextColor(if (i == active) Color.BLACK else Color.WHITE)
            }
        }
        zoomPills[0].alpha = if (minTotal() > 0.99f) 0.4f else 1f
        if (!seekTouch) seekZoom.progress = zoomToProgress(effZoom)
    }

    private fun zoomToProgress(z: Float): Int {
        val lo = minTotal()
        if (lo >= ZOOM_MAX) return 0
        return (ln(max(z, lo) / lo) / ln(ZOOM_MAX / lo) * 1000f).roundToInt().coerceIn(0, 1000)
    }

    private fun progressToZoom(p: Int): Float {
        val lo = minTotal()
        return lo * (ZOOM_MAX / lo).pow(p / 1000f)
    }

    // =====================================================================
    // Buttons
    // =====================================================================
    private fun setupButtons() {
        btnFlash.setOnClickListener { cycleFlash() }
        btnTimer.setOnClickListener {
            timerSec = when (timerSec) { 0 -> 3; 3 -> 10; else -> 0 }
            btnTimer.text = if (timerSec == 0) "⏱" else "⏱$timerSec"
        }
        btnGrid.setOnClickListener {
            gridMode = (gridMode + 1) % 5
            gridView.gridMode = gridMode
            btnGrid.alpha = if (gridMode == 0) 0.5f else 1f
            prefs.edit().putInt("gridMode", gridMode).apply()
            toast(arrayOf("Сетка выкл", "Сетка 3×3", "Золотое сечение", "Сетка 4×4", "Крест")[gridMode])
        }
        btnAi.setOnClickListener { showAiDialog() }
        btnMore.setOnClickListener { showSettings() }
        btnPhoto.setOnClickListener { setVideoMode(false) }
        btnVideo.setOnClickListener { setVideoMode(true) }
        shutter.setOnClickListener { onShutter() }
        btnSwitch.setOnClickListener { switchCamera() }
        ivGallery.setOnClickListener { openLast() }
        seekExposure.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    camera?.cameraControl?.setExposureCompensationIndex(p + expLower)
                    ui.removeCallbacks(hideSeek)
                    ui.postDelayed(hideSeek, 3000)
                    if (::seekEv.isInitialized) {
                        seekEv.progress = p
                        tvEv.text = evText(p + expLower)
                    }
                }
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        seekZoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    zoomAnim?.cancel()
                    setZoomNow(progressToZoom(p))
                    ui.removeCallbacks(hideZoomSeek)
                    ui.postDelayed(hideZoomSeek, 4000)
                }
            }
            override fun onStartTrackingTouch(s: SeekBar) { seekTouch = true }
            override fun onStopTrackingTouch(s: SeekBar) { seekTouch = false }
        })
        tvZoom.setOnClickListener {
            val show = seekZoom.visibility != View.VISIBLE
            seekZoom.visibility = if (show) View.VISIBLE else View.GONE
            ui.removeCallbacks(hideZoomSeek)
            if (show) ui.postDelayed(hideZoomSeek, 4000)
        }
        tvZoom.setOnLongClickListener {
            showZoomInput()
            true
        }
        styleMode()
    }

    private fun styleMode() {
        btnPhoto.setTextColor(if (!isVideoMode) yellow else Color.WHITE)
        btnVideo.setTextColor(if (isVideoMode) yellow else Color.WHITE)
        shutterInner.backgroundTintList = ColorStateList.valueOf(if (isVideoMode) Color.parseColor("#FF5252") else Color.WHITE)
    }

    private fun setupTouch() {
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
                zoomAnim?.cancel()
                return true
            }

            override fun onScale(d: ScaleGestureDetector): Boolean {
                setZoomNow(effZoom * d.scaleFactor.toDouble().pow(1.4).toFloat())
                return true
            }
        })
        val tap = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (tapLocked || aeLock || awbLock) unlockAll()
                focusAt(e.x, e.y, false)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                previewContainer.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                focusAt(e.x, e.y, true)
            }
        })
        previewContainer.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) pinched = false
            scale.onTouchEvent(ev)
            if (scale.isInProgress) pinched = true
            if (!pinched && ev.pointerCount == 1) tap.onTouchEvent(ev)
            true
        }
    }

    // =====================================================================
    // Pro panel (ручные настройки как в Blackmagic Camera)
    // =====================================================================
    private fun proSeekRow(label: String, onChange: (Int) -> Unit): Triple<SeekBar, TextView, LinearLayout> {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val lb = TextView(this)
        lb.text = label
        lb.setTextColor(Color.WHITE)
        lb.textSize = 12f
        lb.layoutParams = LinearLayout.LayoutParams(dp(34), LinearLayout.LayoutParams.WRAP_CONTENT)
        val sb = SeekBar(this)
        sb.max = 1000
        sb.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        tintSeek(sb)
        val tv = TextView(this)
        tv.setTextColor(yellow)
        tv.textSize = 12f
        tv.gravity = Gravity.END
        tv.layoutParams = LinearLayout.LayoutParams(dp(64), LinearLayout.LayoutParams.WRAP_CONTENT)
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) onChange(p)
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        row.addView(lb)
        row.addView(sb)
        row.addView(tv)
        proPanel.addView(row)
        return Triple(sb, tv, row)
    }

    private fun proChip(text: String, onClick: () -> Unit): TextView {
        val tv = makeChip(text, onClick)
        tv.textSize = 12f
        tv.setPadding(dp(9), dp(4), dp(9), dp(4))
        return tv
    }

    private fun buildProPanel() {
        val ev = proSeekRow("EV") { p ->
            camera?.cameraControl?.setExposureCompensationIndex(p + expLower)
            tvEv.text = evText(p + expLower)
            seekExposure.progress = p
        }
        seekEv = ev.first
        tvEv = ev.second

        val iso = proSeekRow("ISO") { p ->
            if (!caps.manualSensor) {
                toast("Ручная экспозиция не поддерживается этой камерой")
                return@proSeekRow
            }
            ensureManualExp()
            isoVal = progressToLog(p, caps.isoMin.toDouble(), caps.isoMax.toDouble()).roundToInt()
                .coerceIn(caps.isoMin, caps.isoMax)
            tvIso.text = isoVal.toString()
            refreshProUi()
            applyCamera2Options()
        }
        seekIso = iso.first
        tvIso = iso.second

        val sh = proSeekRow("S") { p ->
            if (!caps.manualSensor) {
                toast("Ручная экспозиция не поддерживается этой камерой")
                return@proSeekRow
            }
            ensureManualExp()
            expNs = progressToLog(p, expMinCap().toDouble(), max(expMinCap(), expMaxCap()).toDouble()).toLong()
            tvShut.text = fmtShutter(expNs)
            refreshProUi()
            applyCamera2Options()
        }
        seekShut = sh.first
        tvShut = sh.second

        val fo = proSeekRow("F") { p ->
            if (caps.minFocus <= 0f) {
                toast("Ручной фокус не поддерживается этой камерой")
            } else {
                manualFocus = true
                focusDiopt = caps.minFocus * p / 1000f
                refreshProUi()
                applyCamera2Options()
            }
        }
        seekFocus = fo.first
        tvFocus = fo.second

        val chipsRow = LinearLayout(this)
        chipsRow.orientation = LinearLayout.HORIZONTAL
        chipsRow.gravity = Gravity.CENTER_VERTICAL
        chipMan = proChip("AUTO") { setManualExp(!manualExp) }
        chipMf = proChip("AF") {
            if (!manualFocus && caps.minFocus <= 0f) {
                toast("Ручной фокус не поддерживается этой камерой")
            } else {
                manualFocus = !manualFocus
                if (manualFocus) focusDiopt = if (liveFocus >= 0f) min(liveFocus, caps.minFocus) else 0f
                refreshProUi()
                applyCamera2Options()
            }
        }
        chipWb = proChip("WB: Авто") {
            if (wbList.size > 1) {
                wbIdx = (wbIdx + 1) % wbList.size
                refreshProUi()
                applyCamera2Options()
            }
        }
        chipAeL = proChip("🔒AE") {
            aeLock = !aeLock
            refreshProUi()
            applyCamera2Options()
            updateLockBadge()
        }
        chipAwbL = proChip("🔒WB") {
            awbLock = !awbLock
            refreshProUi()
            applyCamera2Options()
            updateLockBadge()
        }
        val reset = proChip("↺") { resetPro() }
        for (v in listOf(chipMan, chipMf, chipWb, chipAeL, chipAwbL, reset)) chipsRow.addView(v)
        val hs = android.widget.HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        hs.addView(chipsRow)
        proPanel.addView(hs)
    }

    private fun toggleProPanel() {
        proOpen = !proOpen
        proPanel.visibility = if (proOpen) View.VISIBLE else View.GONE
        refreshChipTexts()
        refreshProUi()
    }

    private fun progressToLog(p: Int, lo: Double, hi: Double): Double = lo * (hi / lo).pow(p / 1000.0)
    private fun logToProgress(v: Double, lo: Double, hi: Double): Int =
        (ln(v / lo) / ln(hi / lo) * 1000.0).roundToInt().coerceIn(0, 1000)

    private fun curFps(): Int = videoFormats[vfmtIdx].fps
    private fun expMinCap(): Long = max(caps.expMin, 125_000L)
    private fun expMaxCap(): Long {
        val lim = if (isVideoMode) 1_000_000_000L / curFps() else 1_000_000_000L
        return min(caps.expMax, lim)
    }

    private fun fmtShutter(ns: Long): String =
        if (ns >= 400_000_000L) String.format(Locale.US, "%.1fs", ns / 1e9)
        else "1/" + (1e9 / max(1L, ns)).roundToInt()

    private fun fmtFocus(d: Float): String = if (d < 0.05f) "∞" else "${(100f / d).roundToInt()} см"

    private fun evText(idx: Int): String {
        val v = idx * expStep
        return (if (v > 0f) "+" else "") + String.format(Locale.US, "%.1f", v)
    }

    private fun ensureManualExp() {
        if (!manualExp) {
            manualExp = true
            isoVal = liveIso.coerceIn(caps.isoMin, caps.isoMax)
            expNs = liveExp.coerceIn(expMinCap(), max(expMinCap(), expMaxCap()))
        }
    }

    private fun setManualExp(on: Boolean) {
        if (on && !caps.manualSensor) {
            toast("Ручная экспозиция не поддерживается этой камерой")
            return
        }
        manualExp = false
        if (on) ensureManualExp()
        refreshProUi()
        applyCamera2Options()
    }

    private fun resetPro() {
        manualExp = false
        manualFocus = false
        wbIdx = 0
        aeLock = false
        awbLock = false
        tapLocked = false
        camera?.cameraControl?.setExposureCompensationIndex(0)
        camera?.cameraControl?.cancelFocusAndMetering()
        seekEv.progress = -expLower
        seekExposure.progress = -expLower
        tvEv.text = evText(0)
        refreshProUi()
        applyCamera2Options()
        updateLockBadge()
    }

    /** Синхронизирует ползунки Pro-панели с текущими значениями. */
    private fun refreshProUi() {
        if (!::seekIso.isInitialized) return
        val manual = manualExp
        chipMan.text = if (manual) "M" else "AUTO"
        styleChip(chipMan, manual)
        styleChip(chipMf, manualFocus)
        chipMf.text = if (manualFocus) "MF" else "AF"
        styleChip(chipAeL, aeLock)
        styleChip(chipAwbL, awbLock)
        chipWb.text = "WB: " + (wbList.getOrNull(wbIdx)?.label ?: "Авто")
        styleChip(chipWb, wbIdx > 0)

        val iso = if (manual) isoVal else liveIso
        val exp = if (manual) expNs else liveExp
        seekIso.progress = logToProgress(iso.toDouble().coerceAtLeast(1.0), caps.isoMin.toDouble(), caps.isoMax.toDouble())
        tvIso.text = (if (manual) "" else "A ") + iso
        val lo = expMinCap().toDouble()
        val hi = max(expMinCap(), expMaxCap()).toDouble()
        seekShut.progress = logToProgress(exp.toDouble().coerceIn(lo, hi), lo, hi)
        tvShut.text = (if (manual) "" else "A ") + fmtShutter(exp)
        val fd = if (manualFocus) focusDiopt else max(0f, liveFocus)
        seekFocus.progress = if (caps.minFocus > 0f) (fd / caps.minFocus * 1000f).roundToInt().coerceIn(0, 1000) else 0
        tvFocus.text = (if (manualFocus) "" else "A ") + fmtFocus(fd)
        seekIso.alpha = if (caps.manualSensor) 1f else 0.4f
        seekShut.alpha = if (caps.manualSensor) 1f else 0.4f
        seekFocus.alpha = if (caps.minFocus > 0f) 1f else 0.4f
    }

    private fun rebuildWbList() {
        wbList.clear()
        wbList.add(Wb("Авто", CaptureRequest.CONTROL_AWB_MODE_AUTO))
        val all = listOf(
            Wb("Дневной", CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT),
            Wb("Облачно", CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT),
            Wb("Лампа", CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT),
            Wb("Люминесц.", CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT),
            Wb("Тень", CaptureRequest.CONTROL_AWB_MODE_SHADE),
            Wb("Сумерки", CaptureRequest.CONTROL_AWB_MODE_TWILIGHT)
        )
        for (w in all) if (caps.awbModes.contains(w.mode)) wbList.add(w)
        if (wbIdx >= wbList.size) wbIdx = 0
    }

    // =====================================================================
    // Camera setup
    // =====================================================================
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            cameraProvider = provider
            detectWide()
            try {
                val ef = ExtensionsManager.getInstanceAsync(this, provider)
                ef.addListener({
                    extensionsManager = try { ef.get() } catch (e: Exception) { null }
                    refreshChips()
                    bindCamera()
                }, ContextCompat.getMainExecutor(this))
            } catch (e: Exception) {
                bindCamera()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Эквивалентное фокусное расстояние (мм на полном кадре) — по диагонали датчика. */
    private fun equivFocal(info: CameraInfo): Float {
        return try {
            val c2 = Camera2CameraInfo.from(info)
            val focals = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            val size = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            if (focals == null || focals.isEmpty() || size == null || size.width <= 0f) 0f
            else focals[0] * 43.27f / hypot(size.width, size.height)
        } catch (e: Throwable) {
            0f
        }
    }

    /** Ищем отдельную сверхширокую камеру. Если основная камера сама умеет зум < 1× — отдельная не нужна. */
    private fun detectWide() {
        uwCandidate = false
        val provider = cameraProvider ?: return
        try {
            val backs = CameraSelector.DEFAULT_BACK_CAMERA.filter(provider.availableCameraInfos)
            if (backs.size < 2) return
            val main = equivFocal(backs[0])
            val minF = backs.map { equivFocal(it) }.filter { it > 0f }.minOrNull() ?: return
            if (main > 0f && minF < main * 0.75f) {
                uwCandidate = true
                wideFactor = (minF / main).coerceIn(0.3f, 0.8f)
            }
        } catch (e: Throwable) {
            uwCandidate = false
        }
    }

    private fun wideSeparate(): Boolean =
        lensFacing == CameraSelector.LENS_FACING_BACK && uwCandidate && mainMin >= 0.99f

    /** Самый маленький общий зум, который можно получить на этом телефоне. */
    private fun minTotal(): Float = when {
        useWideLens -> wideFactor
        mainMin < 0.99f -> mainMin
        wideSeparate() -> wideFactor
        else -> 1f
    }

    private fun pickWide(infos: List<CameraInfo>): List<CameraInfo> {
        val scored = infos.map { it to equivFocal(it) }.filter { it.second > 0f }
        val best = scored.minByOrNull { it.second } ?: return infos
        return listOf(best.first)
    }

    private fun baseSelector(): CameraSelector {
        val b = CameraSelector.Builder().requireLensFacing(lensFacing)
        if (lensFacing == CameraSelector.LENS_FACING_BACK && useWideLens) {
            b.addCameraFilter(CameraFilter { infos -> pickWide(infos) })
        }
        return b.build()
    }

    private fun readCaps(provider: ProcessCameraProvider) {
        val c = Caps()
        try {
            val infos = baseSelector().filter(provider.availableCameraInfos)
            if (infos.isNotEmpty()) {
                val ci = Camera2CameraInfo.from(infos[0])
                ci.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let {
                    c.isoMin = it.lower
                    c.isoMax = min(it.upper, 6400)
                }
                ci.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let {
                    c.expMin = it.lower
                    c.expMax = it.upper
                }
                c.minFocus = ci.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
                c.awbModes = ci.getCameraCharacteristic(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: IntArray(0)
                c.eisModes = ci.getCameraCharacteristic(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: IntArray(0)
                c.oisModes = ci.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: IntArray(0)
                c.fps = ci.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
                val cap = ci.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
                c.manualSensor = cap.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
            }
        } catch (e: Throwable) {
            // остаются значения по умолчанию
        }
        caps = c
        rebuildWbList()
    }

    /** Режим видеостабилизации для запроса или -1, если ничего ставить не надо. */
    private fun videoStabMode(): Int {
        val m = caps.eisModes
        return when (stabLevel) {
            0 -> if (m.contains(0)) 0 else -1
            2 -> if (Build.VERSION.SDK_INT >= 33 && m.contains(STAB_PREVIEW)) STAB_PREVIEW
                 else if (m.contains(1)) 1 else -1
            else -> if (m.contains(1)) 1 else -1
        }
    }

    private fun fpsRange(): Range<Int>? {
        val t = curFps()
        val rs = caps.fps
        if (rs.isEmpty()) return null
        for (r in rs) if (r.lower == t && r.upper == t) return r
        var best: Range<Int>? = null
        for (r in rs) {
            if (r.upper >= t && r.lower <= t) {
                if (best == null || r.lower > best.lower) best = r
            }
        }
        return best
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return
        if (recording != null) return

        var selector = baseSelector()
        try {
            if (!provider.hasCamera(selector)) {
                useWideLens = false
                selector = baseSelector()
            }
        } catch (e: Exception) {
            toast("Камера недоступна")
            endLensSwitch()
            return
        }

        readCaps(provider)
        freezePreview()

        val em = extensionsManager
        if (!isVideoMode && extMode != ExtensionMode.NONE) {
            if (em != null && safeAvail(em, selector, extMode)) {
                selector = em.getExtensionEnabledCameraSelector(selector, extMode)
            } else {
                extMode = ExtensionMode.NONE
            }
        }

        provider.unbindAll()
        val ratio = if (isVideoMode) AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
        else AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
        // В видео показываем кадр целиком (16:9 с полями) — то, что видишь, то и пишется
        previewView.scaleType = if (isVideoMode) PreviewView.ScaleType.FIT_CENTER else PreviewView.ScaleType.FILL_CENTER
        gridView.contentAspect = if (isVideoMode) 9f / 16f else 0f

        val previewSel = ResolutionSelector.Builder().setAspectRatioStrategy(ratio).build()
        val pvb = Preview.Builder().setResolutionSelector(previewSel)
        val pvExt = Camera2Interop.Extender(pvb)
        pvExt.setSessionCaptureCallback(captureCb)
        val vs = if (isVideoMode) videoStabMode() else -1
        if (vs >= 0) pvExt.setCaptureRequestOption(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, vs)
        val pv = pvb.build()
        pv.setSurfaceProvider(previewView.surfaceProvider)
        val useCases = mutableListOf<UseCase>(pv)

        if (isVideoMode) {
            val f = videoFormats[vfmtIdx]
            val order = when (f.res) {
                0 -> listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD)
                1 -> listOf(Quality.FHD, Quality.HD, Quality.SD)
                else -> listOf(Quality.HD, Quality.SD)
            }
            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.fromOrderedList(order, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))
                ).build()
            val vb = VideoCapture.Builder(recorder)
            val vExt = Camera2Interop.Extender(vb)
            if (vs >= 0) vExt.setCaptureRequestOption(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, vs)
            fpsRange()?.let { vExt.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            val vc = vb.build()
            videoCapture = vc
            imageCapture = null
            useCases.add(vc)
        } else {
            val icSel = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(Size(4160, 3120), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                ).build()
            val ic = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setResolutionSelector(icSel)
                .build()
            imageCapture = ic
            videoCapture = null
            useCases.add(ic)
        }

        try {
            val cam = provider.bindToLifecycle(this, selector, *useCases.toTypedArray())
            camera = cam

            zoomLive?.removeObserver(zoomObserver)
            zoomLive = cam.cameraInfo.zoomState
            zoomLive?.observe(this, zoomObserver)
            cam.cameraInfo.zoomState.value?.let {
                hwMin = it.minZoomRatio
                hwMax = it.maxZoomRatio
                if (!useWideLens) mainMin = hwMin
            }

            stateLive?.removeObserver(camStateObserver)
            stateLive = cam.cameraInfo.cameraState
            stateLive?.observe(this, camStateObserver)

            val es = cam.cameraInfo.exposureState
            if (es.isExposureCompensationSupported) {
                expLower = es.exposureCompensationRange.lower
                expStep = es.exposureCompensationStep.toFloat()
                seekExposure.max = es.exposureCompensationRange.upper - expLower
                seekExposure.progress = es.exposureCompensationIndex - expLower
                seekEv.max = seekExposure.max
                seekEv.progress = seekExposure.progress
                tvEv.text = evText(es.exposureCompensationIndex)
            } else {
                expLower = 0
            }

            applyFlash()
            applyCamera2Options()
            val z = (if (lensBusy) pendingZoom else effZoom).coerceIn(minTotal(), ZOOM_MAX)
            applyZoom(z)
            refreshChips()
            styleMode()
            refreshProUi()
            ui.postDelayed({ endLensSwitch() }, 1800)
        } catch (e: Exception) {
            endLensSwitch()
            releaseFreeze()
            if (extMode != ExtensionMode.NONE) {
                extMode = ExtensionMode.NONE
                bindCamera()
            } else {
                toast("Не удалось запустить камеру: ${e.message}")
            }
        }
    }

    // =====================================================================
    // Camera2: стабилизация + ручные настройки (одним набором)
    // =====================================================================
    private fun applyCamera2Options() {
        val cam = camera ?: return
        try {
            val b = CaptureRequestOptions.Builder()

            // --- стабилизация: оптическая (OIS) всегда, когда стаб включён
            if (caps.oisModes.isNotEmpty()) {
                b.setCaptureRequestOption(
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                    if (stabLevel > 0 && caps.oisModes.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON))
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                    else CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
                )
            }
            // --- электронная (EIS / preview-stab) — только видео
            if (isVideoMode) {
                val vs = videoStabMode()
                if (vs >= 0) b.setCaptureRequestOption(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, vs)
                fpsRange()?.let { b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            }

            // --- экспозиция
            if (manualExp && caps.manualSensor) {
                b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                b.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, isoVal)
                b.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, expNs)
                if (expNs > 33_000_000L) b.setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, expNs)
            } else if (aeLock) {
                b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
            }

            // --- фокус
            if (manualFocus && caps.minFocus > 0f) {
                b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                b.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focusDiopt.coerceIn(0f, caps.minFocus))
            }

            // --- баланс белого
            val wb = wbList.getOrNull(wbIdx)
            if (wb != null && wb.mode != CaptureRequest.CONTROL_AWB_MODE_AUTO) {
                b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, wb.mode)
            }
            if (awbLock) b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)

            Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(b.build())
        } catch (e: Throwable) {
            // не критично: камера продолжит работать с автоматикой
        }
    }

    // =====================================================================
    // Zoom (0.5× … 150×): аппаратный + цифровой хвост, плавные переходы
    // =====================================================================
    private fun animateZoomTo(target: Float) {
        val t = target.coerceIn(minTotal(), ZOOM_MAX)
        zoomAnim?.cancel()
        val from = max(effZoom, 0.05f)
        if (abs(ln(t / from)) < 0.005f) {
            setZoomNow(t)
            return
        }
        val a = ValueAnimator.ofFloat(ln(from), ln(t))
        a.duration = (260f + 240f * abs(log2(t / from))).toLong().coerceIn(300L, 1100L)
        a.interpolator = AccelerateDecelerateInterpolator()
        a.addUpdateListener { setZoomNow(exp(it.animatedValue as Float)) }
        a.start()
        zoomAnim = a
    }

    /** Мгновенно ставит зум (его вызывают анимация, щипок, ползунок). */
    private fun setZoomNow(zIn: Float) {
        if (camera == null) return
        val z = zIn.coerceIn(minTotal(), ZOOM_MAX)
        if (lensBusy) {
            pendingZoom = z
            return
        }
        if (wideSeparate()) {
            if (!useWideLens && z < 0.97f) {
                switchLens(true, z)
                return
            }
            if (useWideLens && z >= 1f) {
                switchLens(false, z)
                return
            }
        }
        applyZoom(z)
    }

    private fun switchLens(wide: Boolean, z: Float) {
        zoomAnim?.pause()
        lensBusy = true
        pendingZoom = z
        useWideLens = wide
        bindCamera()
    }

    private fun endLensSwitch() {
        if (!lensBusy) return
        lensBusy = false
        zoomAnim?.resume()
        applyZoom(pendingZoom.coerceIn(minTotal(), ZOOM_MAX))
    }

    private fun applyZoom(z: Float) {
        val cam = camera ?: return
        val lensScale = if (useWideLens) wideFactor else 1f
        var camZ = z / lensScale
        if (isVideoMode) camZ = min(camZ, hwMax) // в видео — только аппаратный зум
        val hw = camZ.coerceIn(hwMin, hwMax)
        val sw = if (camZ > hwMax) camZ / hwMax else 1f
        cam.cameraControl.setZoomRatio(hw)
        swZoom = sw
        effZoom = hw * sw * lensScale
        pendingZoom = effZoom
        applyViewTransform()
        updateZoomUi()
    }

    /** Масштаб/поворот превью: цифровой зум × горизонт-лок. */
    private fun applyViewTransform() {
        var k = 1f
        if (horizonSm != 0f) {
            val cw = previewContainer.width.toFloat()
            val ch = previewContainer.height.toFloat()
            val ratio = if (cw > 0f && ch > 0f) max(cw, ch) / min(cw, ch) else 1.3334f
            val a = Math.toRadians(abs(horizonSm).toDouble())
            k = (cos(a) + sin(a) * ratio).toFloat()
        }
        val s = swZoom * k
        previewView.rotation = horizonSm
        previewView.scaleX = s
        previewView.scaleY = s
        ivFreeze.rotation = horizonSm
        ivFreeze.scaleX = s
        ivFreeze.scaleY = s
        scopeView.overlayRotation = horizonSm
        scopeView.overlayScale = s
    }

    // ---------- замороженный кадр на время перепривязки камеры (плавный переход) ----------
    private fun freezePreview() {
        if (camera == null) return
        try {
            if (previewView.previewStreamState.value != PreviewView.StreamState.STREAMING) return
            val bmp = previewView.bitmap ?: return
            ivFreeze.setImageBitmap(bmp)
            ivFreeze.animate().cancel()
            ivFreeze.alpha = 1f
            ivFreeze.visibility = View.VISIBLE
            freezeActive = true
            freezeAt = SystemClock.uptimeMillis()
            ui.removeCallbacks(freezeTimeout)
            ui.postDelayed(freezeTimeout, 1800)
        } catch (_: Throwable) {
        }
    }

    private fun releaseFreeze() {
        ui.removeCallbacks(freezeTimeout)
        if (freezeActive) {
            freezeActive = false
            ivFreeze.animate().cancel()
            ivFreeze.animate().alpha(0f).setDuration(240).withEndAction {
                ivFreeze.visibility = View.GONE
                ivFreeze.setImageDrawable(null)
            }.start()
        }
        endLensSwitch()
    }

    private fun showZoomInput() {
        val et = EditText(this)
        et.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        et.setText(f1(effZoom))
        et.setSelectAllOnFocus(true)
        AlertDialog.Builder(this)
            .setTitle("Зум: " + f1(minTotal()) + "× … " + ZOOM_MAX.toInt() + "×")
            .setView(et)
            .setPositiveButton("OK") { _, _ ->
                val v = et.text.toString().replace(',', '.').toFloatOrNull()
                if (v == null) {
                    toast("Введи число, например 2.5")
                } else {
                    if (v < minTotal() - 0.001f) toast("Минимум на этом телефоне: " + f1(minTotal()) + "×")
                    animateZoomTo(v)
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // =====================================================================
    // Focus / AE-AF lock
    // =====================================================================
    private fun focusAt(x: Float, y: Float, lock: Boolean) {
        val cam = camera ?: return
        // координаты касания → координаты внутри повёрнутого/увеличенного превью
        val cx = previewView.width / 2f
        val cy = previewView.height / 2f
        val s = if (previewView.scaleX > 0.01f) previewView.scaleX else 1f
        val rad = Math.toRadians((-previewView.rotation).toDouble())
        val dx = x - cx
        val dy = y - cy
        val rx = (dx * cos(rad) - dy * sin(rad)).toFloat()
        val ry = (dx * sin(rad) + dy * cos(rad)).toFloat()
        val point = previewView.meteringPointFactory.createPoint(cx + rx / s, cy + ry / s)

        var flags = 0
        if (!manualFocus) flags = flags or FocusMeteringAction.FLAG_AF
        if (!manualExp) flags = flags or FocusMeteringAction.FLAG_AE
        if (flags != 0) {
            val fb = FocusMeteringAction.Builder(point, flags)
            if (lock) fb.disableAutoCancel() else fb.setAutoCancelDuration(4, TimeUnit.SECONDS)
            cam.cameraControl.startFocusAndMetering(fb.build())
        }

        focusRing.x = x - dp(32)
        focusRing.y = y - dp(32)
        focusRing.visibility = View.VISIBLE
        ui.removeCallbacks(hideFocus)
        ui.postDelayed(hideFocus, 1200)
        if (!lock && cam.cameraInfo.exposureState.isExposureCompensationSupported) {
            seekExposure.visibility = View.VISIBLE
            ui.removeCallbacks(hideSeek)
            ui.postDelayed(hideSeek, 3000)
        }
        if (lock) {
            tapLocked = true
            ui.postDelayed({
                if (tapLocked) {
                    aeLock = true
                    refreshProUi()
                    applyCamera2Options()
                    updateLockBadge()
                }
            }, 900)
            updateLockBadge()
        }
    }

    private fun unlockAll() {
        tapLocked = false
        aeLock = false
        awbLock = false
        camera?.cameraControl?.cancelFocusAndMetering()
        applyCamera2Options()
        refreshProUi()
        updateLockBadge()
    }

    private fun updateLockBadge() {
        val parts = ArrayList<String>()
        if (tapLocked) parts.add("AF")
        if (aeLock) parts.add("AE")
        if (awbLock) parts.add("WB")
        if (parts.isEmpty()) {
            tvLock.visibility = View.GONE
        } else {
            tvLock.text = "🔒 " + parts.joinToString("/")
            tvLock.visibility = View.VISIBLE
        }
    }

    // =====================================================================
    // Flash / mode / switch
    // =====================================================================
    private fun cycleFlash() {
        flashMode = if (isVideoMode) (if (flashMode == 3) 0 else 3) else (flashMode + 1) % 4
        applyFlash()
    }

    private fun applyFlash() {
        imageCapture?.flashMode = when (flashMode) {
            1 -> ImageCapture.FLASH_MODE_AUTO
            2 -> ImageCapture.FLASH_MODE_ON
            else -> ImageCapture.FLASH_MODE_OFF
        }
        camera?.cameraControl?.enableTorch(flashMode == 3)
        btnFlash.text = arrayOf("⚡✕", "⚡A", "⚡", "🔦")[flashMode]
    }

    private fun setVideoMode(v: Boolean) {
        if (recording != null || isVideoMode == v) return
        isVideoMode = v
        if (v && flashMode in 1..2) flashMode = 0
        styleMode()
        bindCamera()
        refreshChips()
    }

    private fun switchCamera() {
        if (recording != null) return
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
            CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        useWideLens = false
        lensBusy = false
        extMode = ExtensionMode.NONE
        effZoom = 1f
        pendingZoom = 1f
        mainMin = 1f
        bindCamera()
    }

    // =====================================================================
    // Shutter / photo
    // =====================================================================
    private fun onShutter() {
        if (busy) return
        if (isVideoMode) {
            if (recording != null || timerSec == 0) toggleRecording()
            else startCountdown(timerSec) { toggleRecording() }
            return
        }
        if (timerSec > 0) startCountdown(timerSec) { capturePhoto() } else capturePhoto()
    }

    private fun startCountdown(sec: Int, then: () -> Unit) {
        busy = true
        lifecycleScope.launch {
            tvTimer.visibility = View.VISIBLE
            for (i in sec downTo 1) {
                tvTimer.text = i.toString()
                delay(1000)
            }
            tvTimer.visibility = View.GONE
            busy = false
            then()
        }
    }

    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private suspend fun captureBytes(ic: ImageCapture): Pair<ByteArray, Int> =
        suspendCancellableCoroutine { cont ->
            ic.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val buf = image.planes[0].buffer
                        val bytes = ByteArray(buf.remaining())
                        buf.get(bytes)
                        cont.resume(Pair(bytes, image.imageInfo.rotationDegrees))
                    } catch (e: Exception) {
                        cont.resumeWithException(e)
                    } finally {
                        image.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    cont.resumeWithException(exception)
                }
            })
        }

    private suspend fun savePlain(ic: ImageCapture): Uri? = suspendCancellableCoroutine { cont ->
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "ULTRA_${stamp()}.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/UltraCam")
        }
        val md = ImageCapture.Metadata()
        md.setReversedHorizontal(lensFacing == CameraSelector.LENS_FACING_FRONT)
        val opts = ImageCapture.OutputFileOptions.Builder(
            contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ).setMetadata(md).build()
        ic.takePicture(opts, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                cont.resume(r.savedUri)
            }

            override fun onError(e: ImageCaptureException) {
                cont.resumeWithException(e)
            }
        })
    }

    private fun steadyThreshold(): Float = (0.05f / sqrt(max(1f, effZoom))).coerceAtLeast(0.008f)

    private fun capturePhoto() {
        val ic = imageCapture ?: return
        if (busy) return
        busy = true
        val sw = swZoom
        val mirror = lensFacing == CameraSelector.LENS_FACING_FRONT
        val enhance = enhanceOn
        val mode = burstMode
        val shots = when (mode) { 1 -> 4; 2 -> 8; else -> 1 }
        val look = Look.ALL[lookIdx]
        val aspect = guideRatios[guideIdx]
        val roll = if (horizonOn && !mirror) motion.rollDeg().let { if (abs(it) <= 20f) it else 0f } else 0f
        val ai = upscaler
        val strength = aiStrengths[aiStrengthIdx]
        val steady = steadyOn && motion.hasGyro
        val needsProcessing = enhance || sw > 1.01f || mode != 0 || lookIdx != 0 || aspect > 0f || abs(roll) > 0.3f

        if (shutterSound) sound.play(MediaActionSound.SHUTTER_CLICK)
        shutter.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        previewContainer.animate().cancel()
        previewContainer.alpha = 0.35f
        previewContainer.animate().alpha(1f).setDuration(140).start()

        lifecycleScope.launch {
            try {
                if (!needsProcessing) {
                    if (steady) {
                        showStatus("Держи ровно…")
                        motion.awaitSteady(steadyThreshold(), 600)
                    }
                    val uri = savePlain(ic)
                    setThumb(uri)
                } else {
                    val frames = ArrayList<ByteArray>()
                    val scores = ArrayList<Double>()
                    var rot = 0
                    for (i in 1..shots) {
                        if (steady) {
                            showStatus("Держи ровно…")
                            motion.awaitSteady(steadyThreshold(), 600)
                        }
                        showStatus(if (shots > 1) "Серия кадров $i/$shots…" else "Съёмка…")
                        val shot = captureBytes(ic)
                        rot = shot.second
                        frames.add(shot.first)
                        if (shots > 1) {
                            scores.add(withContext(Dispatchers.Default) { ImageEnhancer.sharpness(shot.first) })
                        }
                    }
                    var ref = 0
                    for (i in scores.indices) if (scores[i] > scores[ref]) ref = i
                    val use: List<ByteArray> = if (mode == 2) frames else listOf(frames[ref])
                    val useRef = if (mode == 2) ref else 0
                    val opts = PhotoPipeline.Opts(rot, mirror, sw, enhance, look, aspect, ai, strength, roll)
                    showStatus("Обработка…")
                    val bmp = withContext(Dispatchers.Default) {
                        PhotoPipeline.process(use, useRef, opts) { msg -> runOnUiThread { tvStatus.text = msg } }
                    }
                    val uri = withContext(Dispatchers.IO) { saveJpeg(bmp) }
                    bmp.recycle()
                    setThumb(uri)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                toast("Ошибка съёмки: ${e.message}")
            } finally {
                hideStatus()
                busy = false
            }
        }
    }

    private fun saveJpeg(bmp: Bitmap): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "ULTRA_${stamp()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/UltraCam")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        contentResolver.update(uri, values, null, null)
        return uri
    }

    // =====================================================================
    // Video
    // =====================================================================
    private fun toggleRecording() {
        val vc = videoCapture ?: return
        val cur = recording
        if (cur != null) {
            cur.stop()
            return
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "ULTRA_${stamp()}.mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/UltraCam")
        }
        val mso = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values).build()
        var pending = vc.output.prepareRecording(this, mso)
        if (granted(Manifest.permission.RECORD_AUDIO)) pending = pending.withAudioEnabled()

        recording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    setRecordingUi(true)
                    if (shutterSound) sound.play(MediaActionSound.START_VIDEO_RECORDING)
                }
                is VideoRecordEvent.Status -> {
                    val s = event.recordingStats.recordedDurationNanos / 1_000_000_000L
                    tvRec.text = String.format(Locale.US, "● %02d:%02d", s / 60, s % 60)
                }
                is VideoRecordEvent.Finalize -> {
                    recording = null
                    setRecordingUi(false)
                    if (shutterSound) sound.play(MediaActionSound.STOP_VIDEO_RECORDING)
                    if (event.hasError()) toast("Ошибка записи видео (код ${event.error})")
                    else setThumb(event.outputResults.outputUri)
                }
                else -> {}
            }
        }
    }

    private fun setRecordingUi(on: Boolean) {
        tvRec.visibility = if (on) View.VISIBLE else View.GONE
        if (on) tvRec.text = "● 00:00"
        shutterInner.scaleX = if (on) 0.6f else 1f
        shutterInner.scaleY = if (on) 0.6f else 1f
        btnPhoto.isEnabled = !on
        btnVideo.isEnabled = !on
        btnSwitch.isEnabled = !on
    }

    private fun showVideoFormatDialog() {
        if (recording != null) return
        val labels = videoFormats.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Формат видео")
            .setSingleChoiceItems(labels, vfmtIdx) { d, which ->
                vfmtIdx = which
                prefs.edit().putInt("vfmt", which).apply()
                refreshChipTexts()
                d.dismiss()
                if (isVideoMode) bindCamera()
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    // =====================================================================
    // Scopes (зебра / пиковка / гистограмма / ложные цвета)
    // =====================================================================
    private fun scopesActive() = zebraOn || peakOn || histOn || falseOn

    private fun ensureScopeLoop() {
        if (scopesActive()) {
            scopeView.visibility = View.VISIBLE
            if (!scopeLoopOn) {
                scopeLoopOn = true
                ui.post(scopeTick)
            }
        } else {
            scopeView.visibility = View.GONE
            scopeView.clear()
        }
    }

    private fun grabScope() {
        val bmp = previewView.bitmap ?: return
        scopeBusy = true
        val o = ScopeOptions(zebraOn, 245, peakOn, falseOn, histOn)
        lifecycleScope.launch {
            val res = withContext(Dispatchers.Default) {
                try {
                    ScopeAnalyzer.analyze(bmp, o)
                } catch (e: Throwable) {
                    null
                } finally {
                    bmp.recycle()
                }
            }
            if (res != null && scopesActive()) scopeView.update(res)
            scopeBusy = false
        }
    }

    private fun showScopesDialog() {
        val labels = arrayOf("Зебра (пересвет ≥ 96%)", "Пиковка фокуса", "Гистограмма", "Ложные цвета")
        val checked = booleanArrayOf(zebraOn, peakOn, histOn, falseOn)
        AlertDialog.Builder(this)
            .setTitle("Скопы")
            .setMultiChoiceItems(labels, checked) { _, i, on ->
                when (i) {
                    0 -> { zebraOn = on; prefs.edit().putBoolean("zebra", on).apply() }
                    1 -> { peakOn = on; prefs.edit().putBoolean("peak", on).apply() }
                    2 -> { histOn = on; prefs.edit().putBoolean("hist", on).apply() }
                    3 -> { falseOn = on; prefs.edit().putBoolean("false", on).apply() }
                }
                ensureScopeLoop()
            }
            .setPositiveButton("Готово", null)
            .show()
    }

    // =====================================================================
    // HUD
    // =====================================================================
    private fun updateHud() {
        if (!hudOn) {
            tvHud.visibility = View.GONE
            return
        }
        val sb = StringBuilder()
        sb.append("ISO ").append(liveIso).append("  ").append(fmtShutter(liveExp))
        if (liveFocus >= 0f) sb.append("  ").append(fmtFocus(liveFocus))
        sb.append("\n")
        val parts = ArrayList<String>()
        if (liveOis == 1) parts.add("OIS")
        if (liveVs == 1) parts.add("EIS")
        if (liveVs == 2) parts.add("PREVIEW-STAB")
        sb.append("СТАБ: ").append(if (parts.isEmpty()) "выкл" else parts.joinToString("+"))
        if (isVideoMode) sb.append("  ").append(videoFormats[vfmtIdx].label)
        if (horizonOn && !isVideoMode) sb.append("  ГОРИЗОНТ")
        tvHud.text = sb.toString()
        tvHud.visibility = View.VISIBLE
        if (proOpen && !manualExp) refreshProUi()
    }

    // =====================================================================
    // Gallery
    // =====================================================================
    private fun setThumb(uri: Uri?) {
        if (uri == null) return
        lastUri = uri
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                try { contentResolver.loadThumbnail(uri, Size(192, 192), null) } catch (e: Exception) { null }
            }
            if (bmp != null) ivGallery.setImageBitmap(bmp)
        }
    }

    private fun openLast() {
        val u = lastUri
        if (u == null) {
            toast("Пока нет снимков")
            return
        }
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(u, contentResolver.getType(u))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (e: Exception) {
            toast("Нет приложения для просмотра")
        }
    }

    // =====================================================================
    // Settings / diagnostics
    // =====================================================================
    private fun showSettings() {
        val labels = arrayOf(
            "Звук затвора",
            "Гиро-спуск (ждать, пока рука замрёт)",
            "HUD (ISO / выдержка / стабилизация)",
            "Уровень горизонта",
            "Кнопки громкости = зум (иначе спуск)"
        )
        val checked = booleanArrayOf(shutterSound, steadyOn, hudOn, levelOn, volZoom)
        AlertDialog.Builder(this)
            .setTitle("Настройки")
            .setMultiChoiceItems(labels, checked) { _, i, on ->
                when (i) {
                    0 -> { shutterSound = on; prefs.edit().putBoolean("sound", on).apply() }
                    1 -> {
                        steadyOn = on
                        prefs.edit().putBoolean("steady", on).apply()
                        if (on && !motion.hasGyro) toast("На телефоне нет гироскопа")
                    }
                    2 -> { hudOn = on; prefs.edit().putBoolean("hud", on).apply(); updateHud() }
                    3 -> {
                        levelOn = on
                        prefs.edit().putBoolean("level", on).apply()
                        levelView.enabledLevel = on
                        levelView.invalidate()
                    }
                    4 -> { volZoom = on; prefs.edit().putBoolean("volzoom", on).apply() }
                }
            }
            .setPositiveButton("Готово", null)
            .setNeutralButton("О камерах…") { _, _ -> showCameraInfo() }
            .show()
    }

    private fun showCameraInfo() {
        val sb = StringBuilder()
        sb.append("Android ").append(Build.VERSION.SDK_INT).append("\n")
        val provider = cameraProvider
        if (provider != null) {
            try {
                val backs = CameraSelector.DEFAULT_BACK_CAMERA.filter(provider.availableCameraInfos)
                sb.append("Тыловых камер видно приложению: ").append(backs.size).append("\n")
                for (i in backs) {
                    sb.append(" • id ").append(Camera2CameraInfo.from(i).cameraId)
                        .append(", экв. фокус ").append(f1(equivFocal(i))).append(" мм\n")
                }
            } catch (e: Throwable) {
                sb.append("Не удалось прочитать список камер\n")
            }
        }
        sb.append("\n0.5×: ")
        sb.append(
            when {
                mainMin < 0.99f -> "есть, даёт сама основная камера (зум от " + f1(mainMin) + "×)"
                uwCandidate -> "есть, отдельная сверхширокая камера (≈" + f1(wideFactor) + "×)"
                else -> "НЕТ — телефон не отдаёт приложениям сверхширокую камеру. Без неё шире 1× физически не снять."
            }
        )
        sb.append("\nЗум основной камеры: ").append(f1(hwMin)).append("× … ").append(f1(hwMax)).append("×")
        sb.append("\nOIS: ").append(if (caps.oisModes.contains(1)) "есть" else "нет")
        sb.append("\nEIS (видео): ").append(if (caps.eisModes.contains(1)) "есть" else "нет")
        sb.append("\nPreview-стаб (Android 13+): ")
            .append(if (Build.VERSION.SDK_INT >= 33 && caps.eisModes.contains(STAB_PREVIEW)) "есть" else "нет")
        sb.append("\nРучная экспозиция: ").append(if (caps.manualSensor) "есть" else "нет")
        sb.append("\nISO ").append(caps.isoMin).append("–").append(caps.isoMax)
        sb.append("\nВыдержка ").append(fmtShutter(caps.expMin)).append(" … ").append(fmtShutter(min(caps.expMax, 30_000_000_000L)))
        sb.append("\nРучной фокус: ").append(if (caps.minFocus > 0f) "есть" else "нет")
        sb.append("\nГироскоп: ").append(if (motion.hasGyro) "есть" else "нет")
        AlertDialog.Builder(this)
            .setTitle("О камерах")
            .setMessage(sb.toString())
            .setPositiveButton("Закрыть", null)
            .show()
    }

    // =====================================================================
    // ИИ-плагин (.tflite)
    // =====================================================================
    private fun modelDir(): File = File(filesDir, "models").apply { mkdirs() }
    private fun modelFile(): File = File(modelDir(), "sr.tflite")

    private fun loadAiModel() {
        lifecycleScope.launch(Dispatchers.IO) {
            val f = modelFile()
            if (!f.exists()) {
                // модель, вшитая в APK через app/src/main/assets/models/sr.tflite
                try {
                    val tmp = File(modelDir(), "asset.tmp")
                    assets.open("models/sr.tflite").use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                    tmp.renameTo(f)
                } catch (_: Exception) {
                }
            }
            if (f.exists()) {
                val (up, msg) = TfliteUpscaler.load(f, prefs.getBoolean("range01", true))
                upscaler?.close()
                upscaler = up
                aiInfo = if (up != null) up.description else msg
            } else {
                upscaler?.close()
                upscaler = null
                aiInfo = "модель не загружена"
            }
            withContext(Dispatchers.Main) { btnAi.alpha = if (upscaler != null) 1f else 0.5f }
        }
    }

    private fun installModel(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val tmp = File(modelDir(), "new.tmp")
                contentResolver.openInputStream(uri)?.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                val (probe, msg) = TfliteUpscaler.load(tmp, prefs.getBoolean("range01", true))
                if (probe == null) {
                    tmp.delete()
                    toastUi(msg)
                    return@launch
                }
                probe.close()
                upscaler?.close()
                upscaler = null
                modelFile().delete()
                tmp.renameTo(modelFile())
                toastUi("Модель загружена")
            } catch (e: Throwable) {
                toastUi("Не удалось загрузить модель: ${e.message}")
            }
            loadAiModel()
        }
    }

    private fun showAiDialog() {
        val range01 = prefs.getBoolean("range01", true)
        AlertDialog.Builder(this)
            .setTitle("ИИ-зум: $aiInfo")
            .setItems(
                arrayOf(
                    "📂 Выбрать модель (.tflite)",
                    "Диапазон входа: ${if (range01) "0–1" else "0–255"} (сменить)",
                    "Сила ИИ: ${(aiStrengths[aiStrengthIdx] * 100).roundToInt()}% (сменить)",
                    "🗑 Удалить модель"
                )
            ) { _, which ->
                when (which) {
                    0 -> pickModel.launch(arrayOf("*/*"))
                    1 -> {
                        prefs.edit().putBoolean("range01", !range01).apply()
                        loadAiModel()
                        toast("Диапазон: ${if (!range01) "0–1" else "0–255"}")
                    }
                    2 -> {
                        aiStrengthIdx = (aiStrengthIdx + 1) % aiStrengths.size
                        prefs.edit().putInt("aistr", aiStrengthIdx).apply()
                        toast("Сила ИИ: ${(aiStrengths[aiStrengthIdx] * 100).roundToInt()}%")
                    }
                    3 -> {
                        upscaler?.close()
                        upscaler = null
                        modelFile().delete()
                        loadAiModel()
                    }
                }
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }
}
