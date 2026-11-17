package com.ultracam.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Максимум пикселей кропа, который отдаём ИИ (иначе слишком долго на телефоне). */
const val AI_MAX_PIXELS = 800_000L

/** Мультикадровая склейка включается, если кроп не больше этого (иначе не хватит памяти). */
const val STACK_MAX_PIXELS = 1_800_000L
const val STACK_GRID_MAX_PIXELS = 2_500_000.0

/** Цветовой «лук» для фото: пост-обработка поверх обычного улучшения. */
class Look(
    val name: String,
    val sat: Float,
    val contrast: Float,
    val r: Float,
    val g: Float,
    val b: Float,
    val lift: Float,
    val mono: Boolean,
    val grain: Float = 0f,
    val vignette: Float = 0f
) {
    companion object {
        val ALL: List<Look> = listOf(
            Look("Натурал", 1f, 1f, 1f, 1f, 1f, 0f, false),
            Look("Яркий", 1.28f, 1.08f, 1f, 1f, 1f, 0f, false),
            Look("Кино", 0.88f, 1.14f, 1.04f, 1.0f, 0.95f, 10f, false),
            Look("Ч/Б", 1f, 1.18f, 1f, 1f, 1f, 0f, true),
            Look("Тёплый", 1.05f, 1.03f, 1.07f, 1.0f, 0.92f, 0f, false),
            Look("Холодный", 1.0f, 1.03f, 0.94f, 1.0f, 1.07f, 0f, false),
            // ретро / эстетик: как фото со старого iPhone 5–6 и мыльниц
            Look("iPhone 5", 0.82f, 0.94f, 1.05f, 1.01f, 0.90f, 16f, false, 0.03f, 0.28f),
            Look("Эстетик", 0.78f, 0.92f, 1.04f, 1.0f, 1.05f, 22f, false, 0.02f, 0.12f),
            Look("Плёнка", 0.90f, 1.08f, 1.06f, 1.0f, 0.93f, 8f, false, 0.06f, 0.20f)
        )
    }
}

data class EnhanceParams(
    val sharpen: Float,
    val denoise: Float,
    val contrast: Float,
    val saturation: Float,
    val autoLevels: Float,
    val look: Look = Look.ALL[0]
) {
    companion object {
        val NORMAL = EnhanceParams(0.7f, 0.35f, 1.05f, 1.08f, 0.5f)
        val ZOOMED = EnhanceParams(1.0f, 0.55f, 1.06f, 1.08f, 0.5f)
        /** После склейки шума уже меньше — шумодав слабее, резкость сильнее. */
        val ZOOMED_STACK = EnhanceParams(1.15f, 0.30f, 1.06f, 1.08f, 0.5f)
    }
}

/**
 * Классический пайплайн улучшения (без ИИ, офлайн):
 *  - пошаговое масштабирование (x2 за шаг) вместо одного большого скачка
 *  - unsharp mask по яркости (цветового шума не усиливает)
 *  - «коринг»: мелкие шумовые детали сглаживаются, а не усиливаются
 *  - авто-уровни, подъём теней на тёмных кадрах, лёгкий контраст и насыщенность
 */
object ImageEnhancer {

    private val scalePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)

    fun resizeSmooth(src: Bitmap, dstW: Int, dstH: Int): Bitmap {
        if (src.width == dstW && src.height == dstH) return src
        var cur = src
        while (cur.width != dstW || cur.height != dstH) {
            val nw = stepDim(cur.width, dstW)
            val nh = stepDim(cur.height, dstH)
            val next = Bitmap.createBitmap(nw, nh, Bitmap.Config.ARGB_8888)
            Canvas(next).drawBitmap(cur, null, Rect(0, 0, nw, nh), scalePaint)
            if (cur !== src) cur.recycle()
            cur = next
        }
        return cur
    }

    private fun stepDim(cur: Int, target: Int): Int =
        if (cur < target) min(target, cur * 2) else max(target, cur / 2)

    fun enhance(src: Bitmap, p: EnhanceParams): Bitmap {
        val w = src.width
        val h = src.height
        val n = w * h
        val px = IntArray(n)
        src.getPixels(px, 0, w, 0, 0, w, h)

        val luma = ByteArray(n)
        val hist = IntArray(256)
        var sum = 0L
        for (i in 0 until n) {
            val c = px[i]
            val l = (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8
            luma[i] = l.toByte()
            hist[l]++
            sum += l
        }
        val mean = sum.toFloat() / n
        val lo = percentile(hist, n, 0.005f)
        val hi = percentile(hist, n, 0.995f)

        val radius = max(1, min(w, h) / 1200)
        val tmp = ByteArray(n)
        val blur = ByteArray(n)
        boxBlurH(luma, tmp, w, h, radius)
        boxBlurV(tmp, blur, w, h, radius)
        boxBlurH(blur, tmp, w, h, radius)
        boxBlurV(tmp, blur, w, h, radius)

        val look = p.look
        val gamma = if (mean < 110f) max(0.65f, (mean / 110f).pow(0.5f)) else 1f
        val range = max(1, hi - lo).toFloat()
        val contrast = p.contrast * look.contrast
        val sat = p.saturation * look.sat
        val tinted = look.r != 1f || look.g != 1f || look.b != 1f
        val lifted = look.lift > 0f
        val lut = IntArray(256) { v ->
            var x = v.toFloat()
            val stretched = (x - lo) * 255f / range
            x += (stretched - x) * p.autoLevels
            x = x.coerceIn(0f, 255f)
            x = 255f * (x / 255f).pow(gamma)
            x = (x - 128f) * contrast + 128f
            x.coerceIn(0f, 255f).roundToInt()
        }

        val coring = 6f
        val grainAmp = look.grain * 2f
        val grainOn = grainAmp > 0f
        val vigOn = look.vignette > 0f
        val halfW = max(1f, w / 2f)
        val halfH = max(1f, h / 2f)
        var seed = 12345
        var px0 = 0
        var py0 = 0
        for (i in 0 until n) {
            val c = px[i]
            val r = ((c shr 16) and 0xFF).toFloat()
            val g = ((c shr 8) and 0xFF).toFloat()
            val b = (c and 0xFF).toFloat()
            val l0 = luma[i].toInt() and 0xFF
            val detail = (l0 - (blur[i].toInt() and 0xFF)).toFloat()
            val ad = abs(detail)
            val t = if (ad >= coring) 1f else ad / coring
            val gain = p.sharpen * t - p.denoise * (1f - t)
            val d = (detail * gain).coerceIn(-48f, 48f)
            val l = l0.toFloat()
            var rr = lut[(l + (r - l) * sat + d).coerceIn(0f, 255f).toInt()]
            var gg = lut[(l + (g - l) * sat + d).coerceIn(0f, 255f).toInt()]
            var bb = lut[(l + (b - l) * sat + d).coerceIn(0f, 255f).toInt()]
            if (look.mono) {
                val m = (rr * 77 + gg * 150 + bb * 29) shr 8
                rr = m
                gg = m
                bb = m
            } else if (tinted) {
                rr = (rr * look.r).toInt().coerceIn(0, 255)
                gg = (gg * look.g).toInt().coerceIn(0, 255)
                bb = (bb * look.b).toInt().coerceIn(0, 255)
            }
            if (lifted) {
                val k = (255f - look.lift) / 255f
                rr = (look.lift + rr * k).toInt().coerceIn(0, 255)
                gg = (look.lift + gg * k).toInt().coerceIn(0, 255)
                bb = (look.lift + bb * k).toInt().coerceIn(0, 255)
            }
            if (vigOn) {
                val dxn = (px0 - halfW) / halfW
                val dyn = (py0 - halfH) / halfH
                val vf = 1f - look.vignette * (dxn * dxn + dyn * dyn) * 0.5f
                rr = (rr * vf).toInt().coerceIn(0, 255)
                gg = (gg * vf).toInt().coerceIn(0, 255)
                bb = (bb * vf).toInt().coerceIn(0, 255)
            }
            if (grainOn) {
                seed = seed * 1664525 + 1013904223
                val nz = (((seed ushr 24) - 128) * grainAmp).toInt()
                rr = (rr + nz).coerceIn(0, 255)
                gg = (gg + nz).coerceIn(0, 255)
                bb = (bb + nz).coerceIn(0, 255)
            }
            px[i] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
            px0++
            if (px0 == w) {
                px0 = 0
                py0++
            }
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    /** Оценка резкости (дисперсия лапласиана по центру кадра) — для «Умного кадра». */
    fun sharpness(bytes: ByteArray): Double {
        val o = BitmapFactory.Options().apply { inSampleSize = 4 }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o) ?: return 0.0
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        var s = 0.0
        var s2 = 0.0
        var cnt = 0
        for (y in max(1, h / 4) until min(h - 1, h * 3 / 4)) {
            for (x in max(1, w / 4) until min(w - 1, w * 3 / 4)) {
                val k = y * w + x
                val c = luma(px[k]) * 4 - luma(px[k - 1]) - luma(px[k + 1]) - luma(px[k - w]) - luma(px[k + w])
                s += c
                s2 += c.toDouble() * c
                cnt++
            }
        }
        if (cnt == 0) return 0.0
        val m = s / cnt
        return s2 / cnt - m * m
    }

    private fun luma(p: Int): Int =
        (((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8

    private fun percentile(h: IntArray, n: Int, q: Float): Int {
        val target = (n * q).toLong()
        var acc = 0L
        for (i in 0 until 256) {
            acc += h[i]
            if (acc >= target) return i
        }
        return 255
    }

    private fun boxBlurH(src: ByteArray, dst: ByteArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (y in 0 until h) {
            val row = y * w
            var sum = 0
            for (i in -r..r) sum += src[row + i.coerceIn(0, w - 1)].toInt() and 0xFF
            for (x in 0 until w) {
                dst[row + x] = (sum / div).toByte()
                sum += (src[row + min(w - 1, x + r + 1)].toInt() and 0xFF) -
                        (src[row + max(0, x - r)].toInt() and 0xFF)
            }
        }
    }

    private fun boxBlurV(src: ByteArray, dst: ByteArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        val sums = IntArray(w)
        for (i in -r..r) {
            val yy = i.coerceIn(0, h - 1) * w
            for (x in 0 until w) sums[x] += src[yy + x].toInt() and 0xFF
        }
        for (y in 0 until h) {
            val row = y * w
            val addRow = min(h - 1, y + r + 1) * w
            val subRow = max(0, y - r) * w
            for (x in 0 until w) {
                dst[row + x] = (sums[x] / div).toByte()
                sums[x] += (src[addRow + x].toInt() and 0xFF) - (src[subRow + x].toInt() and 0xFF)
            }
        }
    }
}

/**
 * Обработка снимка:
 *   поворот → кроп под цифровой зум → (мультикадровая склейка) → ИИ-апскейл (смесь с классикой)
 *   → масштаб до полного размера → кроп под рамку → улучшение + лук.
 * Для зума кроп декодируется напрямую из JPEG (BitmapRegionDecoder) — полный кадр в память не грузим.
 */
object PhotoPipeline {

    class Opts(
        val rotation: Int,
        val mirror: Boolean,
        val swZoom: Float,
        val enhance: Boolean,
        val look: Look,
        /** w/h в портретной ориентации; 0 — не обрезать. */
        val aspect: Float,
        val ai: TfliteUpscaler?,
        /** 0..1 — доля ИИ в результате (остальное — обычное масштабирование). */
        val aiStrength: Float,
        /** Крен кадра при съёмке (градусы, см. MotionTracker.rollDeg); 0 — горизонт-лок выключен. */
        val horizonRoll: Float = 0f,
        /** < 1 — виртуальный широкий угол (края дорисовываются зеркалом с размытием). */
        val virtWide: Float = 1f,
        /** Оранжевая дата в углу, как на старых мыльницах. */
        val dateStamp: Boolean = false
    )

    private fun orient(src: Bitmap, rotation: Int, mirror: Boolean): Bitmap {
        if (rotation == 0 && !mirror) return src
        val m = Matrix()
        if (rotation != 0) m.postRotate(rotation.toFloat())
        if (mirror) m.postScale(-1f, 1f)
        val r = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (r !== src) src.recycle()
        return r
    }

    private fun decodeCrop(bytes: ByteArray, o: Opts, w0: Int, h0: Int, cw: Int, ch: Int, zoomed: Boolean): Bitmap {
        var bmp: Bitmap? = null
        if (zoomed) {
            try {
                val swap = o.rotation % 180 != 0
                val rw = if (swap) ch else cw
                val rh = if (swap) cw else ch
                val left = ((w0 - rw) / 2).coerceAtLeast(0)
                val top = ((h0 - rh) / 2).coerceAtLeast(0)
                val rect = Rect(left, top, min(w0, left + rw), min(h0, top + rh))
                val dec = BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
                try {
                    bmp = dec?.decodeRegion(rect, null)
                } finally {
                    dec?.recycle()
                }
            } catch (e: Throwable) {
                bmp = null
            }
        }
        if (bmp == null) {
            // запасной путь: полный декод + кроп
            val full = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: throw IllegalStateException("Не удалось прочитать кадр")
            if (zoomed) {
                val swap = o.rotation % 180 != 0
                val rw = min(full.width, if (swap) ch else cw)
                val rh = min(full.height, if (swap) cw else ch)
                val c = Bitmap.createBitmap(full, (full.width - rw) / 2, (full.height - rh) / 2, rw, rh)
                if (c !== full) full.recycle()
                bmp = c
            } else {
                bmp = full
            }
        }
        return orient(bmp, o.rotation, o.mirror)
    }

    /** Горизонт-лок: поворот на angle и увеличение ровно настолько, чтобы не было чёрных углов. */
    private fun levelHorizon(src: Bitmap, angle: Float): Bitmap {
        val w = src.width
        val h = src.height
        val a = Math.toRadians(abs(angle).toDouble())
        val ratio = max(w, h).toDouble() / min(w, h)
        val k = (cos(a) + sin(a) * ratio).toFloat()
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        c.rotate(angle, w / 2f, h / 2f)
        c.scale(k, k, w / 2f, h / 2f)
        c.drawBitmap(src, 0f, 0f, paint)
        src.recycle()
        return out
    }

    /**
     * Виртуальный широкий угол: реальный кадр уменьшается до z и кладётся в центр,
     * края достраиваются зеркальным отражением и размываются. Это имитация, а не настоящая ширина.
     */
    private fun virtualWide(src: Bitmap, z: Float): Bitmap {
        val w = src.width
        val h = src.height
        val zw = max(2, (w * z).roundToInt())
        val zh = max(2, (h * z).roundToInt())
        val small = Bitmap.createScaledBitmap(src, zw, zh, true)
        src.recycle()
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        val cx = w / 2f
        val cy = h / 2f

        val ext = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val ec = Canvas(ext)
        for (row in -1..1) {
            for (col in -1..1) {
                val left = cx - zw / 2f + col * zw
                val top = cy - zh / 2f + row * zh
                ec.save()
                ec.translate(left + (if (col != 0) zw.toFloat() else 0f), top + (if (row != 0) zh.toFloat() else 0f))
                ec.scale(if (col != 0) -1f else 1f, if (row != 0) -1f else 1f)
                ec.drawBitmap(small, null, Rect(0, 0, zw, zh), p)
                ec.restore()
            }
        }
        val tiny = Bitmap.createScaledBitmap(ext, max(1, w / 24), max(1, h / 24), true)
        ext.recycle()
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val oc = Canvas(out)
        oc.drawBitmap(tiny, null, Rect(0, 0, w, h), p)
        tiny.recycle()
        oc.drawColor(0x66000000)
        oc.drawBitmap(small, cx - zw / 2f, cy - zh / 2f, p)
        small.recycle()
        return out
    }

    private fun drawDateStamp(src: Bitmap): Bitmap {
        val bmp = if (src.isMutable) src else src.copy(Bitmap.Config.ARGB_8888, true)
        if (bmp !== src) src.recycle()
        val c = Canvas(bmp)
        val size = min(bmp.width, bmp.height) * 0.04f
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x66FF6D00
            textSize = size
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
            maskFilter = android.graphics.BlurMaskFilter(size * 0.25f, android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
        val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFF9100.toInt()
            textSize = size
            typeface = glow.typeface
            textAlign = Paint.Align.RIGHT
        }
        val s = java.text.SimpleDateFormat("''yy  MM  dd", java.util.Locale.US).format(java.util.Date())
        val x = bmp.width - size * 0.8f
        val y = bmp.height - size * 0.9f
        c.drawText(s, x, y, glow)
        c.drawText(s, x, y, txt)
        return bmp
    }

    private fun cropAspect(src: Bitmap, portraitRatio: Float): Bitmap {
        val r = if (src.width > src.height) 1f / portraitRatio else portraitRatio
        val cur = src.width.toFloat() / src.height
        if (abs(cur - r) < 0.005f) return src
        val out = if (cur > r) {
            val nw = (src.height * r).toInt().coerceIn(1, src.width)
            Bitmap.createBitmap(src, (src.width - nw) / 2, 0, nw, src.height)
        } else {
            val nh = (src.width / r).toInt().coerceIn(1, src.height)
            Bitmap.createBitmap(src, 0, (src.height - nh) / 2, src.width, nh)
        }
        if (out !== src) src.recycle()
        return out
    }

    /** ai = ai*s + classic*(1-s), кусками по строкам (без лишней памяти). Результат пишется в ai. */
    private fun blendInto(ai: Bitmap, classic: Bitmap, s: Float) {
        val w = ai.width
        val h = ai.height
        val rows = 64
        val a = IntArray(w * rows)
        val c = IntArray(w * rows)
        var y = 0
        while (y < h) {
            val n = min(rows, h - y)
            ai.getPixels(a, 0, w, 0, y, w, n)
            classic.getPixels(c, 0, w, 0, y, w, n)
            for (i in 0 until w * n) {
                val p = a[i]
                val q = c[i]
                val r = (((p shr 16) and 0xFF) * s + ((q shr 16) and 0xFF) * (1f - s)).toInt()
                val g = (((p shr 8) and 0xFF) * s + ((q shr 8) and 0xFF) * (1f - s)).toInt()
                val b = ((p and 0xFF) * s + (q and 0xFF) * (1f - s)).toInt()
                a[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            ai.setPixels(a, 0, w, 0, y, w, n)
            y += n
        }
    }

    /**
     * @param frames JPEG-кадры серии (для одиночного снимка — один)
     * @param refIdx индекс опорного кадра (самого резкого)
     */
    fun process(frames: List<ByteArray>, refIdx: Int, o: Opts, status: (String) -> Unit): Bitmap {
        val refBytes = frames[refIdx]
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(refBytes, 0, refBytes.size, bounds)
        val w0 = bounds.outWidth
        val h0 = bounds.outHeight
        if (w0 <= 0 || h0 <= 0) throw IllegalStateException("Не удалось прочитать кадр")

        val swap = o.rotation % 180 != 0
        val fw = if (swap) h0 else w0
        val fh = if (swap) w0 else h0
        val zoomed = o.swZoom > 1.01f
        val cw = if (zoomed) max(32, (fw / o.swZoom).toInt()) else fw
        val ch = if (zoomed) max(32, (fh / o.swZoom).toInt()) else fh

        var bmp: Bitmap
        var stacked = false
        val canStack = frames.size > 1 && zoomed && cw.toLong() * ch <= STACK_MAX_PIXELS
        if (canStack) {
            status("Склейка кадров…")
            val sf = ArrayList<StackFrame>()
            var refPos = 0
            for (i in frames.indices) {
                val c = decodeCrop(frames[i], o, w0, h0, cw, ch, true)
                val px = IntArray(c.width * c.height)
                c.getPixels(px, 0, c.width, 0, 0, c.width, c.height)
                if (i == refIdx) refPos = sf.size
                sf.add(StackFrame(px, c.width, c.height))
                c.recycle()
            }
            val g = floor(sqrt(STACK_GRID_MAX_PIXELS / (cw.toDouble() * ch))).toInt().coerceIn(1, 3)
            val merged = try {
                FrameStacker.stack(sf, refPos, g) { pct -> status("Склейка кадров $pct%") }
            } catch (e: Throwable) {
                null
            }
            if (merged != null) {
                bmp = merged
                stacked = true
            } else {
                val r = sf[refPos]
                bmp = Bitmap.createBitmap(r.px, r.w, r.h, Bitmap.Config.ARGB_8888)
            }
        } else {
            bmp = decodeCrop(refBytes, o, w0, h0, cw, ch, zoomed)
        }

        if (zoomed) {
            var cur = bmp
            val ai = o.ai
            if (ai != null && cur.width.toLong() * cur.height <= AI_MAX_PIXELS) {
                try {
                    status("ИИ-апскейл…")
                    val aiOut = ai.upscale(cur) { pct -> status("ИИ-апскейл $pct%") }
                    if (o.aiStrength < 0.99f) {
                        val classic = ImageEnhancer.resizeSmooth(cur, aiOut.width, aiOut.height)
                        blendInto(aiOut, classic, o.aiStrength.coerceIn(0f, 1f))
                        if (classic !== cur) classic.recycle()
                    }
                    cur.recycle()
                    cur = aiOut
                } catch (e: Throwable) {
                    // ИИ не вышел — продолжаем классикой
                }
            }
            status("Масштабирование…")
            val resized = ImageEnhancer.resizeSmooth(cur, fw, fh)
            if (resized !== cur) cur.recycle()
            bmp = resized
        }

        if (abs(o.horizonRoll) > 0.3f && abs(o.horizonRoll) <= 25f) {
            status("Выравнивание горизонта…")
            bmp = levelHorizon(bmp, -o.horizonRoll)
        }
        if (o.aspect > 0f) bmp = cropAspect(bmp, o.aspect)

        if (o.enhance || zoomed || o.look !== Look.ALL[0]) {
            status("Улучшение…")
            val base = if (zoomed) (if (stacked) EnhanceParams.ZOOMED_STACK else EnhanceParams.ZOOMED) else EnhanceParams.NORMAL
            val out = ImageEnhancer.enhance(bmp, base.copy(look = o.look))
            bmp.recycle()
            bmp = out
        }
        if (o.dateStamp) bmp = drawDateStamp(bmp)
        if (o.virtWide < 0.999f) {
            status("Виртуальный 0.5×…")
            bmp = virtualWide(bmp, o.virtWide.coerceIn(0.5f, 1f))
        }
        return bmp
    }
}
