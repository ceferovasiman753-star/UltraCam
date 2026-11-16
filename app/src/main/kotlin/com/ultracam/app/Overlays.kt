package com.ultracam.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ln
import kotlin.math.max

/** Сетки (3×3, золотое сечение, 4×4, крест) + маска рамки кадрирования. */
class GridOverlayView @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : View(c, a) {

    /** 0 — нет, 1 — 3×3, 2 — золотое сечение, 3 — 4×4, 4 — крест по центру */
    var gridMode = 0
        set(v) { field = v; invalidate() }

    /** w/h рамки (портрет). 0 — нет рамки. */
    var guideRatio = 0f
        set(v) { field = v; invalidate() }

    /** w/h видимой области картинки внутри вида (0 — вся). В видео превью с полями по бокам. */
    var contentAspect = 0f
        set(v) { field = v; invalidate() }

    private val p = Paint().apply {
        color = Color.parseColor("#88FFFFFF")
        strokeWidth = 2f
    }
    private val mask = Paint().apply { color = Color.parseColor("#AA000000") }
    private val frame = Paint().apply {
        color = Color.parseColor("#FFFFD600")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private fun content(): RectF {
        val w = width.toFloat()
        val h = height.toFloat()
        if (contentAspect <= 0f || h <= 0f) return RectF(0f, 0f, w, h)
        val va = w / h
        return if (contentAspect < va) {
            val cw = h * contentAspect
            RectF((w - cw) / 2f, 0f, (w + cw) / 2f, h)
        } else {
            val ch = w / contentAspect
            RectF(0f, (h - ch) / 2f, w, (h + ch) / 2f)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val r = content()
        val w = r.width()
        val h = r.height()
        when (gridMode) {
            1 -> for (i in 1..2) {
                canvas.drawLine(r.left + w * i / 3f, r.top, r.left + w * i / 3f, r.bottom, p)
                canvas.drawLine(r.left, r.top + h * i / 3f, r.right, r.top + h * i / 3f, p)
            }
            2 -> for (f in floatArrayOf(0.382f, 0.618f)) {
                canvas.drawLine(r.left + w * f, r.top, r.left + w * f, r.bottom, p)
                canvas.drawLine(r.left, r.top + h * f, r.right, r.top + h * f, p)
            }
            3 -> for (i in 1..3) {
                canvas.drawLine(r.left + w * i / 4f, r.top, r.left + w * i / 4f, r.bottom, p)
                canvas.drawLine(r.left, r.top + h * i / 4f, r.right, r.top + h * i / 4f, p)
            }
            4 -> {
                canvas.drawLine(r.centerX(), r.top, r.centerX(), r.bottom, p)
                canvas.drawLine(r.left, r.centerY(), r.right, r.centerY(), p)
            }
        }
        if (guideRatio > 0f) {
            val cur = w / h
            val gw: Float
            val gh: Float
            if (cur > guideRatio) {
                gh = h
                gw = h * guideRatio
            } else {
                gw = w
                gh = w / guideRatio
            }
            val g = RectF(r.centerX() - gw / 2f, r.centerY() - gh / 2f, r.centerX() + gw / 2f, r.centerY() + gh / 2f)
            canvas.drawRect(r.left, r.top, r.right, g.top, mask)
            canvas.drawRect(r.left, g.bottom, r.right, r.bottom, mask)
            canvas.drawRect(r.left, g.top, g.left, g.bottom, mask)
            canvas.drawRect(g.right, g.top, r.right, g.bottom, mask)
            canvas.drawRect(g, frame)
        }
    }
}

/** Электронный уровень горизонта (акселерометр). Зелёный — кадр ровный. */
class LevelView @JvmOverloads constructor(c: Context, a: AttributeSet? = null) :
    View(c, a), SensorEventListener {

    var enabledLevel = true
    private val sm = c.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 4f }
    private var roll = 0f
    private var upright = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onDetachedFromWindow() {
        sm.unregisterListener(this)
        super.onDetachedFromWindow()
    }

    override fun onSensorChanged(e: SensorEvent) {
        val a = Math.toDegrees(atan2(e.values[0].toDouble(), e.values[1].toDouble())).toFloat()
        upright = abs(a) < 30f && e.values[1] > 5f
        roll = roll * 0.8f + a * 0.2f
        if (enabledLevel) invalidate()
    }

    override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}

    override fun onDraw(canvas: Canvas) {
        if (!enabledLevel || !upright) return
        val cx = width / 2f
        val cy = height / 2f
        val len = width * 0.12f
        paint.color = if (abs(roll) < 1f) Color.parseColor("#69F0AE") else Color.parseColor("#CCFFFFFF")
        canvas.save()
        canvas.rotate(roll, cx, cy)
        canvas.drawLine(cx - len, cy, cx + len, cy, paint)
        canvas.restore()
        canvas.drawLine(cx - len * 1.6f, cy, cx - len * 1.3f, cy, paint)
        canvas.drawLine(cx + len * 1.3f, cy, cx + len * 1.6f, cy, paint)
    }
}

/** Слой скопов: зебра / пиковка / ложные цвета (картинкой) + гистограмма в углу. */
class ScopeView @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : View(c, a) {

    /** Масштаб и поворот слоя — совпадают с превью (цифровой зум, горизонт-лок). */
    var overlayScale = 1f
        set(v) { if (field != v) { field = v; invalidate() } }
    var overlayRotation = 0f
        set(v) { if (field != v) { field = v; invalidate() } }

    private var overlay: Bitmap? = null
    private var hist: IntArray? = null
    private var clipHi = 0f
    private var clipLo = 0f

    private val bmpPaint = Paint().apply { isFilterBitmap = false }
    private val bgPaint = Paint().apply { color = Color.parseColor("#88000000") }
    private val barPaint = Paint().apply { color = Color.parseColor("#CCFFFFFF") }
    private val hiPaint = Paint().apply { color = Color.parseColor("#FFFF1744") }
    private val loPaint = Paint().apply { color = Color.parseColor("#FF2979FF") }

    fun update(r: ScopeResult) {
        overlay = r.overlay
        hist = r.hist
        clipHi = r.clipHi
        clipLo = r.clipLo
        invalidate()
    }

    fun clear() {
        overlay = null
        hist = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val ov = overlay
        if (ov != null) {
            canvas.save()
            canvas.rotate(overlayRotation, width / 2f, height / 2f)
            canvas.scale(overlayScale, overlayScale, width / 2f, height / 2f)
            canvas.drawBitmap(ov, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), bmpPaint)
            canvas.restore()
        }
        val hs = hist ?: return
        val d = resources.displayMetrics.density
        val bw = 120f * d
        val bh = 56f * d
        val left = width - bw - 8f * d
        val top = 56f * d
        canvas.drawRect(left, top, left + bw, top + bh, bgPaint)
        val bins = 64
        val vals = FloatArray(bins)
        var mx = 1f
        for (i in 0 until bins) {
            var s = 0
            for (k in 0 until 4) s += hs[i * 4 + k]
            vals[i] = s.toFloat()
            mx = max(mx, vals[i])
        }
        val lm = ln(1f + mx)
        val inner = bw - 6f * d
        val bar = inner / bins
        for (i in 0 until bins) {
            val hgt = ln(1f + vals[i]) / lm * (bh - 6f * d)
            canvas.drawRect(left + 3f * d + i * bar, top + bh - 3f * d - hgt, left + 3f * d + (i + 1) * bar - 1f, top + bh - 3f * d, barPaint)
        }
        if (clipHi > 0.01f) canvas.drawRect(left + bw - 3f * d, top, left + bw, top + bh, hiPaint)
        if (clipLo > 0.01f) canvas.drawRect(left, top, left + 3f * d, top + bh, loPaint)
    }
}
