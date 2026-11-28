package dev.standby.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.ImageDecoder
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.CancellationSignal
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.CalendarContract.Instances
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

fun Context.granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

// ---------- weather ----------

data class Weather(val city: String, val temp: Int, val code: Int, val hi: Int, val lo: Int, val day: Boolean = true)

/** Either the weather or a short reason why not, shown in the widget. */
data class WeatherState(val weather: Weather?, val error: String?)

/** Open-Meteo: free, no API key. */
@SuppressLint("MissingPermission")
suspend fun fetchWeather(context: Context): WeatherState {
    if (!context.granted(Manifest.permission.ACCESS_COARSE_LOCATION)) return WeatherState(null, "Consenti la posizione nelle impostazioni")
    val lm = context.getSystemService(LocationManager::class.java)
    if (!lm.isLocationEnabled) return WeatherState(null, "Attiva la posizione del telefono")
    val loc = runCatching { locate(lm, context) }.getOrNull() ?: return WeatherState(null, "Posizione non trovata")
    // network and geocoder must stay off the main thread, or Android throws
    return withContext(Dispatchers.IO) {
        runCatching {
            val url = URL(
                "https://api.open-meteo.com/v1/forecast?latitude=${loc.latitude}&longitude=${loc.longitude}" +
                    "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1"
            )
            val conn = (url.openConnection() as HttpURLConnection).apply { connectTimeout = 10_000; readTimeout = 10_000 }
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val cur = json.getJSONObject("current")
            val daily = json.getJSONObject("daily")
            @Suppress("DEPRECATION")
            val city = runCatching { Geocoder(context).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()?.locality }.getOrNull()
            Weather(
                city = city ?: "",
                temp = cur.getDouble("temperature_2m").roundToInt(),
                code = cur.getInt("weather_code"),
                hi = daily.getJSONArray("temperature_2m_max").getDouble(0).roundToInt(),
                lo = daily.getJSONArray("temperature_2m_min").getDouble(0).roundToInt(),
                day = cur.optInt("is_day", 1) == 1,
            )
        }.fold({ WeatherState(it, null) }, { WeatherState(null, "Meteo non raggiungibile") })
    }
}

/** Last known fix if any, else ask each provider for a fresh one (fused first: it's the one most phones keep warm). */
@SuppressLint("MissingPermission")
private suspend fun locate(lm: LocationManager, context: Context): Location? {
    val providers = lm.getProviders(true)
    providers.mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }?.let { return it }
    if (Build.VERSION.SDK_INT < 30) return null
    for (p in listOf("fused", LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).filter { it in providers }) {
        val loc = withTimeoutOrNull(20_000) {
            suspendCancellableCoroutine<Location?> { cont ->
                val cancel = CancellationSignal()
                cont.invokeOnCancellation { cancel.cancel() }
                lm.getCurrentLocation(p, cancel, context.mainExecutor) { cont.resume(it) }
            }
        }
        if (loc != null) return loc
    }
    return null
}

fun weatherLabel(code: Int) = when (code) {
    0 -> "Sereno"
    1, 2 -> "Poco nuvoloso"
    3 -> "Nuvoloso"
    45, 48 -> "Nebbia"
    in 51..57 -> "Pioviggine"
    in 61..67, in 80..82 -> "Pioggia"
    in 71..77, 85, 86 -> "Neve"
    in 95..99 -> "Temporale"
    else -> ""
}

// ---------- calendar, alarm ----------

data class Event(val id: Long, val start: Long, val end: Long, val title: String, val allDay: Boolean)

fun upcomingEvents(context: Context): List<Event> {
    if (!context.granted(Manifest.permission.READ_CALENDAR)) return emptyList()
    val now = System.currentTimeMillis()
    val uri = Instances.CONTENT_URI.buildUpon().also {
        ContentUris.appendId(it, now)
        ContentUris.appendId(it, now + 7 * 24 * 3600_000L)
    }.build()
    return runCatching {
        context.contentResolver.query(uri, arrayOf(Instances.BEGIN, Instances.TITLE, Instances.ALL_DAY, Instances.EVENT_ID, Instances.END), null, null, "${Instances.BEGIN} ASC")
            ?.use { c -> buildList { while (size < 4 && c.moveToNext()) add(Event(c.getLong(3), c.getLong(0), c.getLong(4), c.getString(1) ?: "", c.getInt(2) == 1)) } }
    }.getOrNull() ?: emptyList()
}

fun nextAlarm(context: Context): Long? = context.getSystemService(AlarmManager::class.java).nextAlarmClock?.triggerTime

/** The phone's clock app on its alarms page. */
fun Context.openAlarms() = launch(Intent(AlarmClock.ACTION_SHOW_ALARMS))

/** The calendar app on that event, or on today when there is none. */
fun Context.openEvent(e: Event?) = launch(
    if (e == null) Intent(Intent.ACTION_VIEW, CalendarContract.CONTENT_URI.buildUpon().appendPath("time").appendPath("${System.currentTimeMillis()}").build())
    else Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, e.id))
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, e.start).putExtra(CalendarContract.EXTRA_EVENT_END_TIME, e.end)
)

// ---------- battery ----------

data class Power(
    val level: Int,
    val percent: Double,
    val charging: Boolean,
    val source: String,
    val milliamps: Int?,
    val volts: Double?,
    val watts: Double?,
    val celsius: Double?,
    val fullInMs: Long?,
)

/** Android only gives whole percents; the charge counter (µAh) fills in the decimals, counted from the last whole-percent tick. */
class PrecisePercent {
    private var anchorLevel = -1
    private var anchorCounter = 0L

    fun update(level: Int, counterUah: Long): Double {
        // full is full: at 100 the counter keeps climbing and would read "100,99 %"
        if (counterUah <= 0 || level <= 0 || level >= 100) return level.coerceIn(0, 100).toDouble()
        if (level != anchorLevel) { anchorLevel = level; anchorCounter = counterUah }
        val perPercent = counterUah.toDouble() / level
        return level + ((counterUah - anchorCounter) / perPercent).coerceIn(0.0, 0.99)
    }
}

/** CURRENT_NOW is µA by spec, but some makers report mA and the sign differs between phones. */
fun currentMilliamps(raw: Int): Int? {
    if (raw == 0 || raw == Int.MIN_VALUE) return null
    val a = abs(raw)
    return if (a > 20_000) a / 1000 else a
}

/** EXTRA_VOLTAGE is mV by spec; a few phones send V or µV. */
fun batteryVolts(raw: Int): Double? = when {
    raw <= 0 -> null
    raw < 100 -> raw.toDouble()
    raw > 100_000 -> raw / 1_000_000.0
    else -> raw / 1000.0
}

fun readPower(context: Context, precise: PrecisePercent): Power {
    val bm = context.getSystemService(BatteryManager::class.java)
    // null receiver = just read the sticky battery broadcast
    val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val scale = (i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100).coerceAtLeast(1)
    val level = (i?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0) * 100 / scale
    val plugged = i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
    val mA = currentMilliamps(bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW))
    val volts = batteryVolts(i?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0)
    val temp = i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }
    return Power(
        level = level,
        percent = precise.update(level, bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER).toLong()),
        charging = plugged != 0,
        source = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_AC -> "Caricatore"
            else -> ""
        },
        milliamps = mA,
        volts = volts,
        watts = if (mA != null && volts != null) volts * mA / 1000 else null,
        celsius = temp?.div(10.0),
        fullInMs = bm.computeChargeTimeRemaining().takeIf { it > 0 },
    )
}

// ---------- photos ----------

/** Downsampled so a 50 MP photo doesn't blow the memory; ImageDecoder also applies EXIF rotation. */
fun loadBitmap(context: Context, uri: Uri, maxPx: Int = 1920): ImageBitmap? = runCatching {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
        val longest = max(info.size.width, info.size.height)
        if (longest > maxPx) decoder.setTargetSampleSize(longest / maxPx)
    }.asImageBitmap()
}.getOrNull()
