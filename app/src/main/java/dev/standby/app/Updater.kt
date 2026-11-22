package dev.standby.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Updates from the GitHub Releases the build workflow publishes: tag vN, N = versionCode. Needs the repository public. */
object Updater {
    private const val LATEST = "https://api.github.com/repos/CriTheSurveyor/Screensaver/releases/latest"

    class Release(val version: Int, val apk: String)

    fun installedVersion(context: Context) =
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()

    /** The newest release, or null when GitHub can't be reached. Call off the main thread. */
    fun latest(): Release? = runCatching {
        val conn = (URL(LATEST).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        val assets = json.getJSONArray("assets")
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
            .first { it.getString("name").endsWith(".apk") }.getString("browser_download_url")
        Release(json.getString("tag_name").removePrefix("v").toInt(), apk)
    }.getOrNull()

    /** Streams the APK into an install session; Android then shows its own "Update this app?" prompt. Call off the main thread. */
    fun install(context: Context, release: Release) {
        val installer = context.packageManager.packageInstaller
        val id = installer.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
        installer.openSession(id).use { session ->
            URL(release.apk).openStream().use { input ->
                session.openWrite("standby.apk", 0, -1).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            // mutable: the system adds the status extras to this intent
            val status = PendingIntent.getBroadcast(
                context, id, Intent(context, InstallReceiver::class.java),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            session.commit(status.intentSender)
        }
    }
}

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1) != PackageInstaller.STATUS_PENDING_USER_ACTION) return
        val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
        context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
