package com.ultracam.app

import android.graphics.Bitmap
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * ИИ-плагин: любая .tflite модель суперразрешения (ESRGAN / Real-ESRGAN и т.п.)
 * с ФИКСИРОВАННЫМ входом [1, H, W, 3] (float32 или uint8) и выходом [1, H*k, W*k, 3].
 * Картинка режется на тайлы с перекрытием, каждый тайл гоняется через модель, потом всё склеивается.
 */
class TfliteUpscaler private constructor(
    private val itp: Interpreter,
    private val tileW: Int,
    private val tileH: Int,
    val scale: Int,
    private val inFloat: Boolean,
    private val outFloat: Boolean,
    private val range01: Boolean
) {
    val description: String get() = "×$scale, тайл ${tileW}×$tileH"

    fun close() {
        try { itp.close() } catch (_: Throwable) {}
    }

    fun upscale(src: Bitmap, progress: (Int) -> Unit): Bitmap {
        val w = src.width
        val h = src.height
        val srcPx = IntArray(w * h)
        src.getPixels(srcPx, 0, w, 0, 0, w, h)
        val out = Bitmap.createBitmap(w * scale, h * scale, Bitmap.Config.ARGB_8888)

        val pad = max(2, min(tileW, tileH) / 10)
        val stepX = tileW - 2 * pad
        val stepY = tileH - 2 * pad
        val outW = tileW * scale
        val outH = tileH * scale

        val inBuf = ByteBuffer.allocateDirect(tileW * tileH * 3 * (if (inFloat) 4 else 1)).order(ByteOrder.nativeOrder())
        val outBuf = ByteBuffer.allocateDirect(outW * outH * 3 * (if (outFloat) 4 else 1)).order(ByteOrder.nativeOrder())
        val inF = inBuf.asFloatBuffer()
        val outF = outBuf.asFloatBuffer()
        val inDiv = if (range01) 255f else 1f
        val outMul = if (range01) 255f else 1f

        val total = ((w + stepX - 1) / stepX) * ((h + stepY - 1) / stepY)
        var done = 0
        var y0 = 0
        while (y0 < h) {
            var x0 = 0
            while (x0 < w) {
                var idx = 0
                for (ty in 0 until tileH) {
                    val sy = (y0 - pad + ty).coerceIn(0, h - 1)
                    for (tx in 0 until tileW) {
                        val sx = (x0 - pad + tx).coerceIn(0, w - 1)
                        val p = srcPx[sy * w + sx]
                        val r = (p shr 16) and 0xFF
                        val g = (p shr 8) and 0xFF
                        val b = p and 0xFF
                        if (inFloat) {
                            inF.put(idx, r / inDiv)
                            inF.put(idx + 1, g / inDiv)
                            inF.put(idx + 2, b / inDiv)
                        } else {
                            inBuf.put(idx, r.toByte())
                            inBuf.put(idx + 1, g.toByte())
                            inBuf.put(idx + 2, b.toByte())
                        }
                        idx += 3
                    }
                }
                inBuf.rewind()
                outBuf.rewind()
                itp.run(inBuf, outBuf)

                val bw = min(stepX, w - x0)
                val bh = min(stepY, h - y0)
                val ow = bw * scale
                val oh = bh * scale
                val block = IntArray(ow * oh)
                for (oy in 0 until oh) {
                    val ty = pad * scale + oy
                    for (ox in 0 until ow) {
                        val tx = pad * scale + ox
                        val o = (ty * outW + tx) * 3
                        val r = outVal(outBuf, outF, o, outMul)
                        val g = outVal(outBuf, outF, o + 1, outMul)
                        val b = outVal(outBuf, outF, o + 2, outMul)
                        block[oy * ow + ox] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
                out.setPixels(block, 0, ow, x0 * scale, y0 * scale, ow, oh)
                done++
                progress(done * 100 / total)
                x0 += stepX
            }
            y0 += stepY
        }
        return out
    }

    private fun outVal(buf: ByteBuffer, f: java.nio.FloatBuffer, i: Int, mul: Float): Int =
        if (outFloat) (f.get(i) * mul).toInt().coerceIn(0, 255) else buf.get(i).toInt() and 0xFF

    companion object {
        /** Возвращает (модель или null, сообщение). */
        fun load(file: File, range01: Boolean): Pair<TfliteUpscaler?, String> {
            var itp: Interpreter? = null
            try {
                itp = Interpreter(file, Interpreter.Options().apply { setNumThreads(4) })
                val si = itp.getInputTensor(0).shape()
                val so = itp.getOutputTensor(0).shape()
                val inType = itp.getInputTensor(0).dataType()
                val outType = itp.getOutputTensor(0).dataType()
                fun okType(t: DataType) = t == DataType.FLOAT32 || t == DataType.UINT8

                if (si.size != 4 || so.size != 4 || si[3] != 3 || so[3] != 3 ||
                    si[1] <= 0 || si[2] <= 0 || !okType(inType) || !okType(outType)
                ) {
                    itp.close()
                    return Pair(null, "Модель не подходит: нужен вход [1,H,W,3] фиксированного размера")
                }
                val sc = so[1] / si[1]
                if (sc < 2 || so[2] / si[2] != sc) {
                    itp.close()
                    return Pair(null, "Модель не увеличивает картинку (нужен масштаб ×2 и больше)")
                }
                val up = TfliteUpscaler(itp, si[2], si[1], sc, inType == DataType.FLOAT32, outType == DataType.FLOAT32, range01)
                return Pair(up, "OK")
            } catch (e: Throwable) {
                try { itp?.close() } catch (_: Throwable) {}
                return Pair(null, "Ошибка загрузки модели: ${e.message}")
            }
        }
    }
}
