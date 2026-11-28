package dev.standby.app

import android.app.Activity
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
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
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
import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class StandByActivity : ComponentActivity() {
    private var leaving = false
    private val handler = Handler(Looper.getMainLooper())

    // Covered for 5 s (face down, in a drawer): the screen goes dark with the proximity wake lock phone calls use.
    // Uncovered, Android lights it again on StandBy. The phone stays "on", so no screen-off broadcast and the power-key logic is untouched.
    private val pocket by lazy {
        getSystemService(PowerManager::class.java).takeIf { it.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) }
            ?.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "standby:pocket")
    }
    private val darken = Runnable { pocket?.takeIf { !it.isHeld }?.acquire(12 * 3600_000L) }
    private val proximity = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            // many sensors only report 0 or their maximum: near is anything under it
            val near = e.values[0] < minOf(e.sensor.maximumRange, 5f)
            handler.removeCallbacks(darken)
            if (near) handler.postDelayed(darken, 5000)
            else pocket?.takeIf { it.isHeld }?.release()
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

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
        // the lock screen is portrait: cut straight to landscape instead of the system's spinning rotation
        window.attributes = window.attributes.also { it.rotationAnimation = WindowManager.LayoutParams.ROTATION_ANIMATION_JUMPCUT }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).run {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        Bus.shown = true
        NotifService.hush(this, true)
        getSystemService(SensorManager::class.java).run {
            getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let { registerListener(proximity, it, SensorManager.SENSOR_DELAY_NORMAL) }
        }
        val prefs = Prefs(this)
        val preview = intent.getBooleanExtra("preview", false)

        setContent {
            val night = when (prefs.nightMode) { 1 -> true; 2 -> false; else -> rememberNight(true) }
            LaunchedEffect(night) {
                window.attributes = window.attributes.also {
                    it.screenBrightness = if (night) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
            }
            // lifted off the charger or turned upright: close like iOS
            if (!preview) LaunchedEffect(Unit) { Bus.exit.collect { finish() } }
            // nothing is drawn until the display is already landscape, then it fades in: no portrait frame ever shows
            val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
            AnimatedVisibility(landscape, enter = fadeIn(tween(500)), exit = fadeOut(tween(120))) {
                StandByScreen(prefs, night)
            }
        }
    }

    // Left with the screen still on (home gesture, a call, another app): close for real. Kept alive in the background,
    // it held Bus.shown true and the watcher never opened StandBy again until the app was reopened.
    override fun onStop() {
        super.onStop()
        // dark from the proximity sensor is not leaving: StandBy must be there when it's uncovered
        if (!leaving && !isFinishing && pocket?.isHeld != true && getSystemService(PowerManager::class.java).isInteractive) finish()
    }

    override fun onDestroy() {
        unregisterReceiver(powerKey)
        getSystemService(SensorManager::class.java).unregisterListener(proximity)
        handler.removeCallbacks(darken)
        pocket?.takeIf { it.isHeld }?.release()
        NotifService.hush(this, false)
        Bus.shown = false
        super.onDestroy()
    }
}

/** Hands over to another app; over the lock screen it asks for fingerprint/PIN first, as the shade does. Leaving, StandBy closes (onStop). */
fun Context.leaveTo(go: () -> Unit) {
    val km = getSystemService(KeyguardManager::class.java)
    val activity = this as? Activity
    if (activity == null || !km.isKeyguardLocked) return go()
    km.requestDismissKeyguard(activity, object : KeyguardManager.KeyguardDismissCallback() {
        override fun onDismissSucceeded() = go()
    })
}

fun Context.launch(intent: Intent) = leaveTo { runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }

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
