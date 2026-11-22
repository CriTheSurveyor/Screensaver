package dev.standby.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageDecoder
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.CalendarContract.Instances
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.roundToInt

fun Context.granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

data class Weather(val city: String, val temp: Int, val code: Int, val hi: Int, val lo: Int, val day: Boolean)

/** Open-Meteo: free, no API key. */
@SuppressLint("MissingPermission")
suspend fun fetchWeather(context: Context): Weather? {
    if (!context.granted(Manifest.permission.ACCESS_COARSE_LOCATION)) return null
    return runCatching {
        val lm = context.getSystemService(LocationManager::class.java)
        val loc: Location = lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
            ?: (if (Build.VERSION.SDK_INT >= 30) suspendCancellableCoroutine<Location?> { cont ->
                lm.getCurrentLocation(LocationManager.NETWORK_PROVIDER, null, context.mainExecutor) { cont.resume(it) }
            } else null)
            ?: return null
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
            day = cur.getInt("is_day") == 1,
        )
    }.getOrNull()
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

data class Event(val start: Long, val title: String, val allDay: Boolean)

fun upcomingEvents(context: Context): List<Event> {
    if (!context.granted(Manifest.permission.READ_CALENDAR)) return emptyList()
    val now = System.currentTimeMillis()
    val uri = Instances.CONTENT_URI.buildUpon().also {
        ContentUris.appendId(it, now)
        ContentUris.appendId(it, now + 7 * 24 * 3600_000L)
    }.build()
    return runCatching {
        context.contentResolver.query(uri, arrayOf(Instances.BEGIN, Instances.TITLE, Instances.ALL_DAY), null, null, "${Instances.BEGIN} ASC")
            ?.use { c -> buildList { while (size < 4 && c.moveToNext()) add(Event(c.getLong(0), c.getString(1) ?: "", c.getInt(2) == 1)) } }
    }.getOrNull() ?: emptyList()
}

/** Downsampled so a 50 MP photo doesn't blow the memory; ImageDecoder also applies EXIF rotation. */
fun loadBitmap(context: Context, uri: Uri, maxPx: Int = 1920): ImageBitmap? = runCatching {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
        val longest = max(info.size.width, info.size.height)
        if (longest > maxPx) decoder.setTargetSampleSize(longest / maxPx)
    }.asImageBitmap()
}.getOrNull()
