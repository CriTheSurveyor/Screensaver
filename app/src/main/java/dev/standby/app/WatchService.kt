package dev.standby.app

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread

/** Waits for charging (wireless by default) + standing in landscape + locked, then opens StandBy as iOS does. */
class WatchService : Service(), SensorEventListener {
    private lateinit var prefs: Prefs
    private lateinit var sensors: SensorManager
    private lateinit var keyguard: KeyguardManager
    private val upright = Debounce(1500)
    private lateinit var wakeLock: PowerManager.WakeLock
    private var charging = false
    private var listening = false
    private var wasReady = false
    private val handler = Handler(Looper.getMainLooper())

    // a new GitHub build installs itself; only without the install permission it falls back to a notification
    private val updateCheck = object : Runnable {
        override fun run() {
            // installing restarts the app: never while StandBy is on screen, try again at the next round
            if (!Bus.shown) thread {
                val r = Updater.latest() ?: return@thread
                if (r.version <= Updater.installedVersion(this@WatchService)) return@thread
                if (packageManager.canRequestPackageInstalls()) runCatching { Updater.install(this@WatchService, r) }
                else notifyUpdate(r.version)
            }
            handler.postDelayed(this, 3 * 3600_000L)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                    charging = if (prefs.wirelessOnly) plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS else plugged != 0
                    if (charging != listening) setListening(charging)
                    check()
                }
                // on the stand StandBy replaces the dark screen: every screen-off brings it back
                Intent.ACTION_SCREEN_OFF -> if (ready()) open()
                // backup for phones that refuse an activity start while the screen is going dark
                Intent.ACTION_SCREEN_ON -> if (ready() && keyguard.isKeyguardLocked) open()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        sensors = getSystemService(SensorManager::class.java)
        keyguard = getSystemService(KeyguardManager::class.java)
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "standby:watch")
        try {
            goForeground()
        } catch (e: Exception) {
            stopSelf()
            return
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        handler.postDelayed(updateCheck, 60_000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        handler.removeCallbacks(updateCheck)
        runCatching { unregisterReceiver(receiver) }
        setListening(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    override fun onSensorChanged(e: SensorEvent) {
        upright.update(isLandscape(e.values[0], e.values[1]), SystemClock.elapsedRealtime())
        check()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun ready() = charging && upright.stable

    private fun check() {
        val ready = ready()
        if (wasReady && !ready) Bus.exit.tryEmit(Unit)
        // just put on the stand: open over the lock screen, never over someone watching a video
        if (!wasReady && ready && keyguard.isKeyguardLocked) open()
        wasReady = ready
    }

    private fun open() {
        // without "display over other apps" Android blocks activity starts from the background
        if (Bus.shown || !Settings.canDrawOverlays(this)) return
        startActivity(Intent(this, StandByActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
    }

    // accelerometer and wake lock only while charging: free on the charger, nothing on battery.
    // The wake lock keeps the sensor alive with the screen off, so placing the phone on the stand is noticed.
    private fun setListening(on: Boolean) {
        listening = on
        if (on) {
            sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                ?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
            if (!wakeLock.isHeld) wakeLock.acquire(24 * 3600_000L)
        } else {
            sensors.unregisterListener(this)
            upright.reset()
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("watch", "StandBy in attesa", NotificationManager.IMPORTANCE_MIN))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "watch")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("StandBy pronto")
            .setContentText("Si accende in carica, in orizzontale")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
    }

    private fun notifyUpdate(version: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("update", "Aggiornamenti", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(
            2,
            Notification.Builder(this, "update")
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Aggiornamento StandBy")
                .setContentText("La versione $version è pronta: tocca per installarla")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    companion object {
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, WatchService::class.java))
        }

        /** Restart so a changed setting applies at once. */
        fun sync(context: Context) {
            context.stopService(Intent(context, WatchService::class.java))
            if (Prefs(context).enabled) start(context)
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // after a reboot, and after an update (which stops the app), the watcher starts again by itself
        val restart = intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (restart && Prefs(context).enabled) WatchService.start(context)
    }
}
