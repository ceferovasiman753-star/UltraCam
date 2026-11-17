package com.ultracam.app

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.roundToInt

class ScopeOptions(
    val zebra: Boolean,
    val zebraThr: Int,
    val peak: Boolean,
    val falseColor: Boolean,
    val hist: Boolean
)

class ScopeResult(
    val hist: IntArray?,
    val overlay: Bitmap?,
    val clipHi: Float,
    val clipLo: Float
)

/** Анализ кадра превью: гистограмма, зебра, пиковка фокуса, ложные цвета (как в профессиональных камерах). */
object ScopeAnalyzer {

    private val ZEBRA = android.graphics.Color.argb(204, 255, 23, 68)
    private val PEAK = android.graphics.Color.argb(255, 0, 230, 118)

    private fun gray(v: Int) = android.graphics.Color.argb(255, v, v, v)

    private fun falseColor(l: Int): Int = when {
        l < 13 -> android.graphics.Color.rgb(106, 27, 154)      // глухие тени — фиолетовый
        l < 38 -> android.graphics.Color.rgb(21, 101, 192)      // тени — синий
        l in 108..122 -> android.graphics.Color.rgb(46, 125, 50) // 18% серого — зелёный
        l in 150..175 -> android.graphics.Color.rgb(244, 143, 177) // кожа — розовый
        l in 225..244 -> android.graphics.Color.rgb(255, 235, 59) // почти пересвет — жёлтый
        l >= 245 -> android.graphics.Color.rgb(213, 0, 0)        // пересвет — красный
        else -> gray(l)
    }

    fun analyze(src: Bitmap, o: ScopeOptions): ScopeResult {
        val tw = 240
        val th = max(1, (src.height * tw.toFloat() / src.width).roundToInt())
        val small = Bitmap.createScaledBitmap(src, tw, th, true)
        val n = tw * th
        val px = IntArray(n)
        small.getPixels(px, 0, tw, 0, 0, tw, th)
        if (small !== src) small.recycle()

        val luma = IntArray(n)
        val hist = IntArray(256)
        for (i in 0 until n) {
            val c = px[i]
            val l = (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8
            luma[i] = l
            hist[l]++
        }
        var hi = 0
        var lo = 0
        for (v in 250..255) hi += hist[v]
        for (v in 0..3) lo += hist[v]

        var overlay: Bitmap? = null
        if (o.falseColor || o.zebra || o.peak) {
            val out = IntArray(n)
            if (o.falseColor) {
                for (i in 0 until n) out[i] = falseColor(luma[i])
            }
            if (o.zebra) {
                for (y in 0 until th) {
                    for (x in 0 until tw) {
                        val i = y * tw + x
                        if (luma[i] >= o.zebraThr && (((x + y) shr 1) and 1) == 0) out[i] = ZEBRA
                    }
                }
            }
            if (o.peak) {
                for (y in 1 until th - 1) {
                    for (x in 1 until tw - 1) {
                        val i = y * tw + x
                        if (luma[i] < 20) continue
                        val gx = luma[i + 1] - luma[i - 1]
                        val gy = luma[i + tw] - luma[i - tw]
                        val m = (if (gx < 0) -gx else gx) + (if (gy < 0) -gy else gy)
                        if (m > 44) out[i] = PEAK
                    }
                }
            }
            overlay = Bitmap.createBitmap(out, tw, th, Bitmap.Config.ARGB_8888)
        }
        return ScopeResult(if (o.hist) hist else null, overlay, hi.toFloat() / n, lo.toFloat() / n)
    }
}
