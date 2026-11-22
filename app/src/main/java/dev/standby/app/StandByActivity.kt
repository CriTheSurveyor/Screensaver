package dev.standby.app

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class StandByActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).run {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        Bus.shown = true
        val prefs = Prefs(this)
        val preview = intent.getBooleanExtra("preview", false)

        setContent {
            val night = rememberNight(prefs.nightAuto)
            LaunchedEffect(night) {
                window.attributes = window.attributes.also {
                    it.screenBrightness = if (night) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
            }
            // lifted off the charger or turned upright: close like iOS
            if (!preview) LaunchedEffect(Unit) { Bus.exit.collect { finish() } }
            StandByScreen(prefs, night)
        }
    }

    override fun onDestroy() {
        Bus.shown = false
        super.onDestroy()
    }
}

/** Night mode as on iOS: the room goes dark, the screen turns dim red. Hysteresis stops it flickering at dusk. */
@Composable
private fun rememberNight(enabled: Boolean): Boolean {
    val context = LocalContext.current
    var night by remember { mutableStateOf(false) }
    DisposableEffect(enabled) {
        val sm = context.getSystemService(SensorManager::class.java)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val lux = e.values[0]
                if (lux < 3f) night = true else if (lux > 15f) night = false
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        val light = sm.getDefaultSensor(Sensor.TYPE_LIGHT)
        if (enabled && light != null) sm.registerListener(listener, light, SensorManager.SENSOR_DELAY_NORMAL)
        onDispose { sm.unregisterListener(listener) }
    }
    return enabled && night
}
