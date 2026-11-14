package com.oppocamera.unlock.aicomp

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * 构图引导用的陀螺仪积分。
 *
 * 相机的构图引导靠 `track(HardwareBuffer, ...)` 回传"参考构图现在跑到画面哪儿了"。
 * 它自带的 native 跟踪器在这台机器上一直在判"画面变化较大，已退出本次构图"，
 * 所以这里干脆不依赖图像跟踪：拿陀螺仪的角速度积分出像素偏移，直接回传。
 *
 * 换算：手机上按 60° 垂直视场估算焦距
 *
 *     focal = (h / 2) / tan(30°)
 *     dx += gyroY * focal * dt          // 绕 Y 轴转 = 画面左右移动
 *     dy -= gyroX * focal * dt          // 绕 X 轴转 = 画面上下移动
 *
 * 原点由 [anchor] 定，构图重新开始时归零。
 */
object GyroTracker {

    private var manager: SensorManager? = null

    @Volatile private var dx = 0f
    @Volatile private var dy = 0f
    @Volatile private var focal = 0f
    @Volatile private var lastNs = 0L
    @Volatile private var ready = false

    private val listener = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

        override fun onSensorChanged(event: SensorEvent) {
            val v = event.values ?: return
            if (v.size < 2) return
            val ts = event.timestamp
            if (lastNs == 0L) {
                lastNs = ts
                ready = true
                return
            }
            val dt = (ts - lastNs) / 1_000_000_000f
            lastNs = ts
            ready = true
            if (dt <= 0f || dt > 0.1f) return

            dx += v[1] * focal * dt
            dy -= v[0] * focal * dt
        }
    }

    /** 以当前取景为原点，按帧高算焦距。 */
    fun anchor(w: Float, h: Float) {
        dx = 0f
        dy = 0f
        lastNs = 0L
        focal = ((h / 2.0) / Math.tan(Math.toRadians(30.0))).toFloat()
        AiBridge.logOnce("gyro anchor ${w.toInt()}x${h.toInt()} focal=${focal.toInt()}")
    }

    /** 相对 anchor 的像素偏移。没收到陀螺仪数据时返回 0。 */
    fun offsetPixels(): FloatArray =
        if (!ready) floatArrayOf(0f, 0f) else floatArrayOf(dx, dy)

    fun start(ctx: Context?) {
        if (manager != null || ctx == null) return
        runCatching {
            val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
            val sensor = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
                ?: sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED)
                ?: run {
                    AiBridge.log("陀螺仪: 没有角速度传感器")
                    return
                }
            sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
            manager = sm
            AiBridge.log("陀螺仪已启动 ${sensor.name}")
        }.onFailure { AiBridge.log("陀螺仪启动失败 $it") }
    }
}
