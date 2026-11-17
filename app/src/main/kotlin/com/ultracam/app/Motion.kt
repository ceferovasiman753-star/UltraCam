package com.ultracam.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Гироскоп + акселерометр:
 *  - omega: насколько дрожит рука (рад/с) → «гиро-спуск» ждёт, пока рука замрёт
 *  - rollDeg: крен относительно ближайшей «ровной» ориентации → горизонт-лок
 */
class MotionTracker(ctx: Context) : SensorEventListener {

    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyro: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accel: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    @Volatile
    var omega = 0f
        private set

    @Volatile
    private var gx = 0f

    @Volatile
    private var gy = 0f

    val hasGyro: Boolean get() = gyro != null
    val hasAccel: Boolean get() = accel != null

    fun start() {
        gyro?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        accel?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        sm.unregisterListener(this)
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type == Sensor.TYPE_GYROSCOPE) {
            val m = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
            omega = omega * 0.7f + m * 0.3f
        } else if (e.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            gx = gx * 0.85f + e.values[0] * 0.15f
            gy = gy * 0.85f + e.values[1] * 0.15f
        }
    }

    override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}

    /**
     * Крен кадра в градусах (−45..45), по часовой стрелке положительный.
     * Видимый горизонт в кадре повёрнут именно на этот угол, чтобы выровнять — крутим на −roll.
     * Если телефон лежит экраном вверх/вниз, крен не определён → 0.
     */
    fun rollDeg(): Float {
        if (accel == null) return 0f
        if (sqrt(gx * gx + gy * gy) < 3f) return 0f
        val a = Math.toDegrees(atan2(gx.toDouble(), gy.toDouble())).toFloat()
        var r = a % 90f
        if (r > 45f) r -= 90f
        if (r < -45f) r += 90f
        return r
    }

    /** Ждёт, пока дрожь рук не упадёт ниже порога (или истечёт таймаут). true — рука замерла. */
    suspend fun awaitSteady(threshold: Float, timeoutMs: Long): Boolean {
        if (gyro == null) return true
        val t0 = SystemClock.uptimeMillis()
        var okSince = -1L
        while (true) {
            val now = SystemClock.uptimeMillis()
            if (omega < threshold) {
                if (okSince < 0) okSince = now
                if (now - okSince >= 80) return true
            } else {
                okSince = -1L
            }
            if (now - t0 > timeoutMs) return false
            delay(15)
        }
    }
}
