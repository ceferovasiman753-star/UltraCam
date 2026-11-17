package com.ultracam.app

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.camera.core.CameraEffect
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Эффект CameraX: preview + видео проходят через GlStabilizer. */
class GlEffect(proc: GlStabilizer) : CameraEffect(
    CameraEffect.PREVIEW or CameraEffect.VIDEO_CAPTURE,
    proc.executor,
    proc,
    { t -> proc.reportError(t) }
)

/**
 * Своя стабилизация видео на OpenGL (работает на любом телефоне, где нет нормальной EIS/OIS):
 *
 *  1. Стабилизация по картинке. Каждый кадр уменьшается до 160×120, ищется сдвиг относительно
 *     предыдущего (пирамида + субпиксель), сдвиги накапливаются в «траекторию» P.
 *     Сглаженная траектория S — фильтр низких частот. Кадр рисуется со смещением (P − S):
 *     быстрая дрожь компенсируется, плавные панорамы остаются.
 *     Всё считается в координатах текстуры камеры, поэтому ориентация телефона роли не играет.
 *  2. Горизонт-лок: кадр поворачивается на угол крена (акселерометр) и увеличивается ровно настолько,
 *     чтобы не было пустых углов.
 *  3. Виртуальный широкий угол (< 1×): кадр уменьшается, края дорисовываются зеркалом с размытием.
 *     Это имитация — реальных деталей за пределами кадра нет.
 */
class GlStabilizer(private val onError: (String) -> Unit) : SurfaceProcessor {

    private class Out(val es: EGLSurface, val w: Int, val h: Int)

    private val thread = HandlerThread("ultracam-gl").apply { start() }
    private val handler = Handler(thread.looper)
    val executor = Executor { r -> handler.post(r) }

    // ---- параметры (пишутся из UI-потока, читаются в GL-потоке) ----
    @Volatile var stabOn = false
    @Volatile var horizonOn = false
    @Volatile var rollDeg = 0f
    @Volatile var virtualZoom = 1f
    @Volatile var framesRendered = 0
    @Volatile var corrPercent = 0f
    @Volatile private var released = false
    @Volatile private var failed = false

    // ---- EGL / GL ----
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var aPos = 0
    private var uTex = 0
    private var uAsp = 0
    private var uRot = 0
    private var uScale = 0
    private var uShift = 0
    private var uSampler = 0
    private var inputTex = 0
    private var inputSt: SurfaceTexture? = null
    private var fbo = 0
    private var fboTex = 0
    private val outputs = LinkedHashMap<SurfaceOutput, Out>()
    private val texMat = FloatArray(16)
    private val outMat = FloatArray(16)
    private val identity = FloatArray(16)
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    // ---- движение ----
    private val mw = 160
    private val mh = 120
    private val rb: ByteBuffer = ByteBuffer.allocateDirect(mw * mh * 4).order(ByteOrder.nativeOrder())
    private var prevL: FloatArray? = null
    private var prevH: FloatArray? = null
    private var curL = FloatArray(mw * mh)
    private var curH = FloatArray((mw / 2) * (mh / 2))
    private var px = 0f
    private var py = 0f
    private var sx = 0f
    private var sy = 0f
    private var corrX = 0f
    private var corrY = 0f
    private var lastTs = 0L
    private var hSm = 0f

    init {
        android.opengl.Matrix.setIdentityM(identity, 0)
        quad.put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        quad.position(0)
    }

    fun reportError(t: Throwable) {
        fail(t)
    }

    private fun fail(t: Throwable) {
        if (failed) return
        failed = true
        onError(t.javaClass.simpleName + ": " + (t.message ?: ""))
    }

    fun release() {
        if (released) return
        released = true
        handler.post {
            try {
                teardown()
            } catch (_: Throwable) {
            }
            thread.quitSafely()
        }
    }

    // =====================================================================
    // SurfaceProcessor
    // =====================================================================
    override fun onInputSurface(request: SurfaceRequest) {
        if (released) {
            request.willNotProvideSurface()
            return
        }
        try {
            ensureEgl()
            makeCurrent(pbuffer)
            val tex = IntArray(1)
            GLES20.glGenTextures(1, tex, 0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            val st = SurfaceTexture(tex[0])
            st.setDefaultBufferSize(request.resolution.width, request.resolution.height)
            val surface = Surface(st)
            inputTex = tex[0]
            inputSt = st
            resetMotion()
            request.provideSurface(surface, executor) {
                try {
                    st.setOnFrameAvailableListener(null)
                    surface.release()
                    st.release()
                } catch (_: Throwable) {
                }
                if (inputSt === st) inputSt = null
            }
            st.setOnFrameAvailableListener({ drawFrame(st) }, handler)
        } catch (t: Throwable) {
            request.willNotProvideSurface()
            fail(t)
        }
    }

    override fun onOutputSurface(surfaceOutput: SurfaceOutput) {
        if (released) {
            surfaceOutput.close()
            return
        }
        try {
            ensureEgl()
            val surface = surfaceOutput.getSurface(executor) { _ ->
                removeOutput(surfaceOutput)
                surfaceOutput.close()
            }
            val es = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
            val size = surfaceOutput.size
            outputs[surfaceOutput] = Out(es, size.width, size.height)
        } catch (t: Throwable) {
            try {
                surfaceOutput.close()
            } catch (_: Throwable) {
            }
            fail(t)
        }
    }

    private fun removeOutput(o: SurfaceOutput) {
        val out = outputs.remove(o) ?: return
        try {
            EGL14.eglDestroySurface(display, out.es)
        } catch (_: Throwable) {
        }
    }

    // =====================================================================
    // EGL
    // =====================================================================
    private fun makeCurrent(s: EGLSurface) {
        if (!EGL14.eglMakeCurrent(display, s, s, context)) throw IllegalStateException("eglMakeCurrent")
    }

    private fun ensureEgl() {
        if (context != EGL14.EGL_NO_CONTEXT) return
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) throw IllegalStateException("eglInitialize")

        var cfg: EGLConfig? = null
        for (recordable in intArrayOf(1, 0)) {
            val attribs = ArrayList<Int>()
            attribs.addAll(listOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8))
            attribs.addAll(listOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT))
            attribs.addAll(listOf(EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT))
            if (recordable == 1) attribs.addAll(listOf(0x3142, 1)) // EGL_RECORDABLE_ANDROID
            attribs.add(EGL14.EGL_NONE)
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            if (EGL14.eglChooseConfig(display, attribs.toIntArray(), 0, configs, 0, 1, num, 0) && num[0] > 0) {
                cfg = configs[0]
                break
            }
        }
        if (cfg == null) throw IllegalStateException("EGL: нет подходящей конфигурации")
        config = cfg
        context = EGL14.eglCreateContext(
            display, cfg, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        pbuffer = EGL14.eglCreatePbufferSurface(
            display, cfg, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
        )
        makeCurrent(pbuffer)
        buildProgram()
        buildFbo()
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(s)
            GLES20.glDeleteShader(s)
            throw IllegalStateException("shader: $log")
        }
        return s
    }

    private fun buildProgram() {
        val vs = compile(
            GLES20.GL_VERTEX_SHADER,
            """
            attribute vec2 aPos;
            uniform float uAsp;
            uniform vec2 uRot;
            uniform float uScale;
            uniform vec2 uShift;
            varying vec2 vW;
            void main() {
                gl_Position = vec4(aPos, 0.0, 1.0);
                vec2 uv = aPos * 0.5 + 0.5;
                vec2 c = (uv - 0.5) * vec2(uAsp, 1.0);
                c = vec2(c.x * uRot.x + c.y * uRot.y, -c.x * uRot.y + c.y * uRot.x);
                c *= uScale;
                vW = c / vec2(uAsp, 1.0) + 0.5 + uShift;
            }
            """.trimIndent()
        )
        val fs = compile(
            GLES20.GL_FRAGMENT_SHADER,
            """
            #extension GL_OES_EGL_image_external : require
            precision highp float;
            varying vec2 vW;
            uniform mat4 uTex;
            uniform samplerExternalOES uSampler;
            vec2 mir(vec2 w) { return 1.0 - abs(1.0 - mod(w, 2.0)); }
            vec4 tap(vec2 w) { return texture2D(uSampler, (uTex * vec4(w, 0.0, 1.0)).xy); }
            void main() {
                vec2 m = mir(vW);
                vec4 c = tap(m);
                if (vW.x < 0.0 || vW.x > 1.0 || vW.y < 0.0 || vW.y > 1.0) {
                    float d = 0.012;
                    c = 0.25 * (tap(mir(m + vec2(d, d))) + tap(mir(m + vec2(-d, d)))
                              + tap(mir(m + vec2(d, -d))) + tap(mir(m + vec2(-d, -d))));
                    c.rgb *= 0.6;
                }
                gl_FragColor = vec4(c.rgb, 1.0);
            }
            """.trimIndent()
        )
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw IllegalStateException("link: " + GLES20.glGetProgramInfoLog(program))
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        uTex = GLES20.glGetUniformLocation(program, "uTex")
        uAsp = GLES20.glGetUniformLocation(program, "uAsp")
        uRot = GLES20.glGetUniformLocation(program, "uRot")
        uScale = GLES20.glGetUniformLocation(program, "uScale")
        uShift = GLES20.glGetUniformLocation(program, "uShift")
        uSampler = GLES20.glGetUniformLocation(program, "uSampler")
    }

    private fun buildFbo() {
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        fboTex = t[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, mw, mh, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val f = IntArray(1)
        GLES20.glGenFramebuffers(1, f, 0)
        fbo = f[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTex, 0
        )
        if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            throw IllegalStateException("FBO incomplete")
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    private fun teardown() {
        for ((o, out) in outputs) {
            try {
                EGL14.eglDestroySurface(display, out.es)
                o.close()
            } catch (_: Throwable) {
            }
        }
        outputs.clear()
        try {
            inputSt?.setOnFrameAvailableListener(null)
        } catch (_: Throwable) {
        }
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbuffer)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        pbuffer = EGL14.EGL_NO_SURFACE
    }

    // =====================================================================
    // Кадр
    // =====================================================================
    private fun drawQuad(tex: FloatArray, asp: Float, rotRad: Float, scale: Float, shx: Float, shy: Float) {
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inputTex)
        GLES20.glUniform1i(uSampler, 0)
        GLES20.glUniformMatrix4fv(uTex, 1, false, tex, 0)
        GLES20.glUniform1f(uAsp, asp)
        GLES20.glUniform2f(uRot, cos(rotRad), sin(rotRad))
        GLES20.glUniform1f(uScale, scale)
        GLES20.glUniform2f(uShift, shx, shy)
        quad.position(0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
    }

    private fun drawFrame(st: SurfaceTexture) {
        if (released || failed || st !== inputSt) return
        try {
            makeCurrent(pbuffer)
            st.updateTexImage()
            st.getTransformMatrix(texMat)
            val ts = st.timestamp
            if (stabOn) {
                measure(ts)
            } else {
                corrX = 0f
                corrY = 0f
                corrPercent = 0f
                prevL = null
                prevH = null
            }
            val tgt = if (horizonOn && abs(rollDeg) <= 15f) rollDeg else 0f
            hSm += (tgt - hSm) * 0.2f
            if (tgt == 0f && abs(hSm) < 0.05f) hSm = 0f

            for ((o, out) in outputs) drawOut(o, out, ts)
            framesRendered++
        } catch (t: Throwable) {
            fail(t)
        }
    }

    private fun drawOut(o: SurfaceOutput, out: Out, ts: Long) {
        makeCurrent(out.es)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, out.w, out.h)
        o.updateTransformMatrix(outMat, texMat)
        val asp = out.w.toFloat() / max(1, out.h)
        val vz = virtualZoom.coerceIn(0.5f, 1f)
        val kStab = if (stabOn) K_STAB else 1f
        var kHor = 1f
        if (hSm != 0f) {
            val a = Math.toRadians(abs(hSm).toDouble())
            val ratio = max(asp, 1f / asp)
            kHor = (cos(a) + sin(a) * ratio).toFloat()
        }
        // при виртуальном широком угле запас не нужен: пустоту закрывает дорисованный край
        val kTot = if (vz < 0.999f) 1f else kStab * kHor
        val scale = 1f / (vz * kTot)

        var shx = 0f
        var shy = 0f
        if (stabOn) {
            // смещение из координат текстуры в координаты выходного кадра: M_lin⁻¹ · c
            val det = outMat[0] * outMat[5] - outMat[4] * outMat[1]
            if (abs(det) > 1e-6f) {
                shx = (outMat[5] * corrX - outMat[4] * corrY) / det
                shy = (-outMat[1] * corrX + outMat[0] * corrY) / det
            }
        }
        drawQuad(outMat, asp, Math.toRadians(hSm.toDouble()).toFloat(), scale, shx, shy)
        EGLExt.eglPresentationTimeANDROID(display, out.es, ts)
        EGL14.eglSwapBuffers(display, out.es)
    }

    // =====================================================================
    // Движение по картинке
    // =====================================================================
    private fun resetMotion() {
        prevL = null
        prevH = null
        px = 0f
        py = 0f
        sx = 0f
        sy = 0f
        corrX = 0f
        corrY = 0f
        lastTs = 0L
    }

    private fun measure(ts: Long) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glViewport(0, 0, mw, mh)
        drawQuad(identity, 1f, 0f, 1f, 0f, 0f)   // «сырые» координаты текстуры: uv → uv
        rb.clear()
        GLES20.glReadPixels(0, 0, mw, mh, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, rb)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        // яркость с нулевым средним (дрейф экспозиции не мешает)
        var sum = 0f
        for (i in 0 until mw * mh) {
            val r = rb.get(i * 4).toInt() and 0xFF
            val g = rb.get(i * 4 + 1).toInt() and 0xFF
            val b = rb.get(i * 4 + 2).toInt() and 0xFF
            val l = (r * 77 + g * 150 + b * 29) / 256f
            curL[i] = l
            sum += l
        }
        val mean = sum / (mw * mh)
        for (i in 0 until mw * mh) curL[i] -= mean
        val hw = mw / 2
        val hh = mh / 2
        for (y in 0 until hh) {
            for (x in 0 until hw) {
                val i0 = 2 * y * mw + 2 * x
                curH[y * hw + x] = (curL[i0] + curL[i0 + 1] + curL[i0 + mw] + curL[i0 + mw + 1]) * 0.25f
            }
        }

        val pl = prevL
        val ph = prevH
        if (pl != null && ph != null) {
            val d = estimate(pl, ph, curL, curH)
            val dxT = d[0] / mw
            val dyT = d[1] / mh
            // слишком большой скачок — это панорама или сбой: траекторию не трогаем
            if (abs(dxT) < 0.08f && abs(dyT) < 0.08f) {
                px += dxT
                py += dyT
            }
        }
        // обмен буферами
        prevL = curL.copyOf()
        prevH = curH.copyOf()

        val dt = if (lastTs > 0L) ((ts - lastTs) / 1e9f).coerceIn(0.005f, 0.1f) else 0.033f
        lastTs = ts
        val alpha = 1f - exp(-dt / TAU)
        sx += (px - sx) * alpha
        sy += (py - sy) * alpha

        val lim = (1f - 1f / K_STAB) / 2f * 0.92f
        var cx = px - sx
        var cy = py - sy
        if (cx > lim) { cx = lim; sx = px - lim } else if (cx < -lim) { cx = -lim; sx = px + lim }
        if (cy > lim) { cy = lim; sy = py - lim } else if (cy < -lim) { cy = -lim; sy = py + lim }
        corrX = cx
        corrY = cy
        corrPercent = max(abs(cx), abs(cy)) * 100f
    }

    private fun sadAt(a: FloatArray, b: FloatArray, w: Int, h: Int, shx: Int, shy: Int): Float {
        val x0 = (w * 0.15f).toInt()
        val x1 = (w * 0.85f).toInt()
        val y0 = (h * 0.15f).toInt()
        val y1 = (h * 0.85f).toInt()
        var s = 0f
        var n = 0
        for (y in y0 until y1) {
            val cy = (y + shy).coerceIn(0, h - 1)
            for (x in x0 until x1) {
                val cx = (x + shx).coerceIn(0, w - 1)
                s += abs(a[y * w + x] - b[cy * w + cx])
                n++
            }
        }
        return if (n == 0) 1e9f else s / n
    }

    private fun parabola(m: Float, z: Float, p: Float): Float {
        val d = m - 2f * z + p
        if (abs(d) < 1e-6f) return 0f
        return (0.5f * (m - p) / d).coerceIn(-0.5f, 0.5f)
    }

    /** Сдвиг признаков prev → cur в пикселях [mw × mh]: cur(x + s) = prev(x). */
    private fun estimate(prevFull: FloatArray, prevHalf: FloatArray, curFull: FloatArray, curHalf: FloatArray): FloatArray {
        val hw = mw / 2
        val hh = mh / 2
        val zero = sadAt(prevHalf, curHalf, hw, hh, 0, 0)
        var best = Float.MAX_VALUE
        var bx = 0
        var by = 0
        for (sy in -5..5) {
            for (sx2 in -5..5) {
                val c = sadAt(prevHalf, curHalf, hw, hh, sx2, sy) + 0.12f * (abs(sx2) + abs(sy))
                if (c < best) {
                    best = c
                    bx = sx2
                    by = sy
                }
            }
        }
        if (zero - best < 0.5f) return floatArrayOf(0f, 0f)   // нет уверенного сдвига

        var fb = Float.MAX_VALUE
        var cx0 = bx * 2
        var cy0 = by * 2
        for (sy in (by * 2 - 1)..(by * 2 + 1)) {
            for (sx2 in (bx * 2 - 1)..(bx * 2 + 1)) {
                val c = sadAt(prevFull, curFull, mw, mh, sx2, sy)
                if (c < fb) {
                    fb = c
                    cx0 = sx2
                    cy0 = sy
                }
            }
        }
        val fx = parabola(sadAt(prevFull, curFull, mw, mh, cx0 - 1, cy0), fb, sadAt(prevFull, curFull, mw, mh, cx0 + 1, cy0))
        val fy = parabola(sadAt(prevFull, curFull, mw, mh, cx0, cy0 - 1), fb, sadAt(prevFull, curFull, mw, mh, cx0, cy0 + 1))
        return floatArrayOf(cx0 + fx, cy0 + fy)
    }

    companion object {
        /** Запас кадра на стабилизацию: окно выборки в 1.15 раза меньше кадра (~6.5% с каждой стороны). */
        const val K_STAB = 1.15f

        /** Постоянная времени сглаживания траектории, секунды. */
        const val TAU = 0.6f
    }
}
