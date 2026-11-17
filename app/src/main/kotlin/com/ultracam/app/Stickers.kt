package com.ultracam.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * Один элемент эффекта. Координаты нормированы к превью (0..1), размер — доля ширины превью.
 */
class Sticker(
    val bmp: Bitmap,
    var cx: Float = 0.5f,
    var cy: Float = 0.5f,
    var scale: Float = 0.3f,
    var rot: Float = 0f
) {
    fun copy() = Sticker(bmp, cx, cy, scale, rot)
}

/** Рисование наклеек в произвольный холст (превью на экране и итоговое фото). */
object StickerRenderer {
    /**
     * @param x0,y0,cw,ch — какая часть превью попала в итоговый кадр (0..1); 1 для всего кадра
     */
    fun render(
        canvas: Canvas, list: List<Sticker>, outW: Float, outH: Float,
        x0: Float = 0f, y0: Float = 0f, cw: Float = 1f, ch: Float = 1f
    ) {
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        for (s in list) {
            val wpx = s.scale / cw * outW
            val hpx = wpx * s.bmp.height / s.bmp.width
            val px = (s.cx - x0) / cw * outW
            val py = (s.cy - y0) / ch * outH
            canvas.save()
            canvas.translate(px, py)
            canvas.rotate(s.rot)
            canvas.drawBitmap(s.bmp, null, RectF(-wpx / 2f, -hpx / 2f, wpx / 2f, hpx / 2f), paint)
            canvas.restore()
        }
    }
}

/**
 * Слой эффектов поверх превью. В режиме правки: тянуть — перемещать, два пальца — размер и поворот.
 * Вне режима правки касания проходят к камере (фокус/зум).
 */
class StickerLayer @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : View(c, a) {

    val items = ArrayList<Sticker>()
    var editMode = false
        set(v) {
            field = v
            if (!v) selected = null
            invalidate()
        }

    var selected: Sticker? = null
        private set

    private val outline = Paint().apply {
        color = Color.parseColor("#FFFFD600")
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private var lastX = 0f
    private var lastY = 0f
    private var startDist = 1f
    private var startAng = 0f
    private var startScale = 0.3f
    private var startRot = 0f

    fun snapshot(): List<Sticker> = items.map { it.copy() }

    fun addSticker(bmp: Bitmap) {
        val s = Sticker(bmp)
        items.add(s)
        selected = s
        invalidate()
    }

    fun removeSelected(): Boolean {
        val s = selected ?: return false
        items.remove(s)
        selected = null
        invalidate()
        return true
    }

    fun clearAll() {
        items.clear()
        selected = null
        invalidate()
    }

    fun setAll(list: List<Sticker>) {
        items.clear()
        items.addAll(list)
        selected = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (items.isEmpty()) return
        StickerRenderer.render(canvas, items, width.toFloat(), height.toFloat())
        val s = selected
        if (editMode && s != null) {
            val wpx = s.scale * width
            val hpx = wpx * s.bmp.height / s.bmp.width
            canvas.save()
            canvas.translate(s.cx * width, s.cy * height)
            canvas.rotate(s.rot)
            canvas.drawRect(-wpx / 2f, -hpx / 2f, wpx / 2f, hpx / 2f, outline)
            canvas.restore()
        }
    }

    private fun hit(x: Float, y: Float): Sticker? {
        for (i in items.indices.reversed()) {
            val s = items[i]
            val dx = x - s.cx * width
            val dy = y - s.cy * height
            val a = Math.toRadians(-s.rot.toDouble())
            val lx = (dx * cos(a) - dy * sin(a)).toFloat()
            val ly = (dx * sin(a) + dy * cos(a)).toFloat()
            val wpx = s.scale * width
            val hpx = wpx * s.bmp.height / s.bmp.width
            if (kotlin.math.abs(lx) <= wpx / 2f + 12f && kotlin.math.abs(ly) <= hpx / 2f + 12f) return s
        }
        return null
    }

    private fun dist(e: MotionEvent) = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
    private fun ang(e: MotionEvent) =
        Math.toDegrees(atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble())).toFloat()

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!editMode || items.isEmpty()) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val s = hit(e.x, e.y)
                selected = s
                invalidate()
                if (s == null) return false
                lastX = e.x
                lastY = e.y
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val s = selected ?: return false
                if (e.pointerCount == 2) {
                    startDist = max(1f, dist(e))
                    startAng = ang(e)
                    startScale = s.scale
                    startRot = s.rot
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val s = selected ?: return false
                if (e.pointerCount >= 2) {
                    s.scale = (startScale * dist(e) / startDist).coerceIn(0.05f, 2f)
                    s.rot = startRot + (ang(e) - startAng)
                } else {
                    s.cx = (s.cx + (e.x - lastX) / max(1, width)).coerceIn(0f, 1f)
                    s.cy = (s.cy + (e.y - lastY) / max(1, height)).coerceIn(0f, 1f)
                    lastX = e.x
                    lastY = e.y
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val keep = if (e.actionIndex == 0) 1 else 0
                if (keep < e.pointerCount) {
                    lastX = e.getX(keep)
                    lastY = e.getY(keep)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> return selected != null
        }
        return false
    }
}

/** Создание наклеек (эмодзи, текст, PNG) и сохранение «моих эффектов» в память приложения. */
object EffectsKit {

    fun emojiBitmap(emoji: String): Bitmap {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 220f }
        val b = p.fontMetrics
        val w = max(64, p.measureText(emoji).toInt() + 24)
        val h = max(64, (b.descent - b.ascent).toInt() + 24)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawText(emoji, 12f, 12f - b.ascent, p)
        return bmp
    }

    fun textBitmap(text: String, color: Int): Bitmap {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 160f
            this.color = color
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val stroke = Paint(fill).apply {
            style = Paint.Style.STROKE
            strokeWidth = 14f
            this.color = Color.BLACK
            strokeJoin = Paint.Join.ROUND
        }
        val b = fill.fontMetrics
        val w = max(64, fill.measureText(text).toInt() + 40)
        val h = max(64, (b.descent - b.ascent).toInt() + 40)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawText(text, 20f, 20f - b.ascent, stroke)
        c.drawText(text, 20f, 20f - b.ascent, fill)
        return bmp
    }

    /** Грузим PNG/JPG из галереи, уменьшая до разумного размера. */
    fun decodeImage(ctx: Context, uri: android.net.Uri, maxSide: Int = 1024): Bitmap? {
        return try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
            var sample = 1
            while (max(o.outWidth, o.outHeight) / sample > maxSide * 2) sample *= 2
            val o2 = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) }
                ?: return null
            val side = max(bmp.width, bmp.height)
            if (side > maxSide) {
                val k = maxSide.toFloat() / side
                Bitmap.createScaledBitmap(bmp, max(1, (bmp.width * k).toInt()), max(1, (bmp.height * k).toInt()), true)
            } else bmp
        } catch (e: Throwable) {
            null
        }
    }

    private fun dir(ctx: Context) = File(ctx.filesDir, "effects").apply { mkdirs() }

    private fun safe(name: String) = name.trim().replace(Regex("[^\\p{L}\\p{N} _-]"), "_").take(40).ifBlank { "effect" }

    fun save(ctx: Context, name: String, list: List<Sticker>): String {
        val n = safe(name)
        val d = File(dir(ctx), n)
        d.deleteRecursively()
        d.mkdirs()
        val arr = JSONArray()
        for ((i, s) in list.withIndex()) {
            val f = "$i.png"
            File(d, f).outputStream().use { s.bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            arr.put(
                JSONObject().put("f", f).put("cx", s.cx.toDouble()).put("cy", s.cy.toDouble())
                    .put("s", s.scale.toDouble()).put("r", s.rot.toDouble())
            )
        }
        File(d, "layout.json").writeText(arr.toString())
        return n
    }

    fun names(ctx: Context): List<String> =
        (dir(ctx).listFiles() ?: emptyArray()).filter { File(it, "layout.json").exists() }.map { it.name }.sorted()

    fun delete(ctx: Context, name: String) {
        File(dir(ctx), name).deleteRecursively()
    }

    fun load(ctx: Context, name: String): List<Sticker> {
        val d = File(dir(ctx), name)
        val out = ArrayList<Sticker>()
        val arr = JSONArray(File(d, "layout.json").readText())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val bmp = BitmapFactory.decodeFile(File(d, o.getString("f")).absolutePath) ?: continue
            out.add(
                Sticker(
                    bmp, o.getDouble("cx").toFloat(), o.getDouble("cy").toFloat(),
                    o.getDouble("s").toFloat(), o.getDouble("r").toFloat()
                )
            )
        }
        return out
    }

    val EMOJIS = arrayOf(
        "😀", "😎", "🥰", "😍", "🤩", "😜", "🤡", "👻", "💀", "👽",
        "🔥", "✨", "💖", "💥", "⭐", "🌈", "🌸", "🍀", "🎀", "👑",
        "🕶️", "🎧", "🐱", "🐶", "🦄", "🍕", "🎉", "💎", "🌙", "☀️"
    )
}
