package dev.standby.app

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class StandByActivity : ComponentActivity() {
    private var leaving = false

    // Apps never see the power key itself; while StandBy keeps the screen on, a screen-off can only be that key.
    private val powerKey = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            leaving = true
            // bringing this activity forward again wakes the screen (turnScreenOn), then onResume hands over to the phone
            startActivity(Intent(c, StandByActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    override fun onResume() {
        super.onResume()
        if (!leaving) return
        leaving = false
        // asks for fingerprint/PIN, then the home screen appears; cancelled, the lock screen stays
        getSystemService(KeyguardManager::class.java).requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = finish()
            override fun onDismissCancelled() = finish()
            override fun onDismissError() = finish()
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ContextCompat.registerReceiver(this, powerKey, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
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
        unregisterReceiver(powerKey)
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
