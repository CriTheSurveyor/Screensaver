package dev.standby.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Green = Color(0xFF30D158)
private val Orange = Color(0xFFFF9F0A)

class MainActivity : ComponentActivity() {
    // bumped on every return from system settings, so permission rows refresh
    private val resumed = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = Prefs(this)
        if (prefs.enabled) WatchService.start(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Color.Black, surface = CardBg)) {
                SettingsScreen(prefs, resumed.intValue)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumed.intValue++
    }
}

@Composable
private fun SettingsScreen(prefs: Prefs, resumed: Int) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val key = resumed + refresh
    val overlay = remember(key) { Settings.canDrawOverlays(context) }
    val battery = remember(key) { context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName) }
    val calendar = remember(key) { context.granted(Manifest.permission.READ_CALENDAR) }
    val location = remember(key) { context.granted(Manifest.permission.ACCESS_COARSE_LOCATION) }

    var enabled by remember { mutableStateOf(prefs.enabled) }
    var wireless by remember { mutableStateOf(prefs.wirelessOnly) }
    var night by remember { mutableIntStateOf(prefs.nightMode) }
    var seconds by remember { mutableStateOf(prefs.showSeconds) }
    var font by remember { mutableIntStateOf(prefs.font) }
    var accent by remember { mutableIntStateOf(prefs.accent) }
    var photoSeconds by remember { mutableIntStateOf(prefs.photoSeconds) }

    val scope = rememberCoroutineScope()
    val installed = remember { Updater.installedVersion(context) }
    var release by remember { mutableStateOf<Updater.Release?>(null) }
    var updateText by remember { mutableStateOf("Controllo…") }
    LaunchedEffect(resumed) {
        release = withContext(Dispatchers.IO) { Updater.latest() }
        updateText = when {
            release == null -> "Non raggiungibile"
            release!!.version > installed -> "Installa la ${release!!.version}"
            else -> "Aggiornata"
        }
    }
    var h24 by remember { mutableStateOf(prefs.use24h) }
    var photos by remember { mutableIntStateOf(prefs.photos.size) }

    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        if (uris.isNotEmpty()) {
            // persist access, or the photos vanish after a reboot
            uris.forEach { runCatching { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
            prefs.photos = uris.map { it.toString() }.toSet()
            photos = uris.size
        }
    }
    fun openSystem(action: String) = context.startActivity(Intent(action, Uri.parse("package:${context.packageName}")))

    Column(
        Modifier.fillMaxSize().background(Color.Black)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("StandBy", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Text(
            "Appoggia il telefono in orizzontale sul caricatore e bloccalo: l'orologio si accende da solo. Scorri tra widget, foto e orologio.",
            color = Muted, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp),
        )

        Group("Attivazione") {
            ToggleRow("StandBy automatico", enabled) {
                enabled = it
                prefs.enabled = it
                if (it && Build.VERSION.SDK_INT >= 33) ask.launch(Manifest.permission.POST_NOTIFICATIONS)
                WatchService.sync(context)
            }
            Line()
            ToggleRow("Solo ricarica wireless", wireless, "Spento: parte con qualsiasi caricatore") {
                wireless = it
                prefs.wirelessOnly = it
                WatchService.sync(context)
            }
        }

        Group("Permessi") {
            LinkRow("Mostra sopra le altre app", if (overlay) "Attivo" else "Necessario", warn = !overlay) {
                openSystem(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            }
            Line()
            LinkRow("Nessun limite batteria", if (battery) "Attivo" else "Consigliato", warn = !battery) {
                if (!battery) openSystem(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            }
            Line()
            LinkRow("Calendario", if (calendar) "Attivo" else "Consenti") {
                if (!calendar) ask.launch(Manifest.permission.READ_CALENDAR)
            }
            Line()
            LinkRow("Posizione per il meteo", if (location) "Attivo" else "Consenti") {
                if (!location) ask.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        }

        Group("Aspetto") {
            ChoiceRow("Carattere", Fonts.map { it.name }, font, Fonts.map { it.family }) {
                font = it
                prefs.font = it
            }
            Line()
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("Colore", color = Color.White, fontSize = 17.sp)
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Accents.forEachIndexed { i, c ->
                        Box(
                            Modifier.size(32.dp)
                                .border(2.dp, if (i == accent) Color.White else Color.Transparent, CircleShape)
                                .padding(4.dp).clip(CircleShape).background(c)
                                .clickable { accent = i; prefs.accent = i },
                        )
                    }
                }
            }
        }

        Group("Schermata") {
            ChoiceRow("Modalità notte", listOf("Automatica", "Sempre", "Mai"), night) {
                night = it
                prefs.nightMode = it
            }
            Line()
            ToggleRow("Secondi sull'orologio", seconds) {
                seconds = it
                prefs.showSeconds = it
            }
            Line()
            ToggleRow("Formato 24 ore", h24) {
                h24 = it
                prefs.use24h = it
            }
            Line()
            LinkRow("Foto", if (photos == 0) "Scegli" else "$photos selezionate") {
                pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            Line()
            val intervals = listOf(10, 30, 60, 300)
            ChoiceRow("Cambio foto", listOf("10 s", "30 s", "1 min", "5 min"), intervals.indexOf(photoSeconds)) {
                photoSeconds = intervals[it]
                prefs.photoSeconds = intervals[it]
            }
        }

        Group("Aggiornamenti") {
            LinkRow("Versione $installed", updateText, warn = release?.let { it.version > installed } == true) {
                val r = release ?: return@LinkRow
                if (r.version <= installed) return@LinkRow
                // Android asks once to allow this app to install updates
                if (!context.packageManager.canRequestPackageInstalls()) {
                    openSystem(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    return@LinkRow
                }
                updateText = "Download…"
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { runCatching { Updater.install(context, r) }.isSuccess }
                    if (!ok) updateText = "Download non riuscito"
                }
            }
        }

        Button(
            onClick = { context.startActivity(Intent(context, StandByActivity::class.java).putExtra("preview", true)) },
            modifier = Modifier.fillMaxWidth().padding(top = 28.dp).height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
        ) {
            Text("Prova StandBy", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(
            "Nella schermata: tocca l'orologio per cambiare colore, scorri in verticale per cambiare stile. Tasto di accensione per tornare al telefono.",
            color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp, start = 4.dp, end = 4.dp),
        )
    }
}

@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(title.uppercase(), color = Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, top = 28.dp, bottom = 6.dp))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CardBg), content = content)
}

@Composable
private fun Line() = HorizontalDivider(Modifier.padding(start = 16.dp), thickness = 0.5.dp, color = Color(0xFF38383A))

@Composable
private fun ToggleRow(title: String, checked: Boolean, subtitle: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
            Text(title, color = Color.White, fontSize = 17.sp)
            if (subtitle != null) Text(subtitle, color = Muted, fontSize = 13.sp)
        }
        Switch(
            checked, onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Green, checkedThumbColor = Color.White, checkedBorderColor = Green,
                uncheckedTrackColor = Color(0xFF39393D), uncheckedThumbColor = Color.White, uncheckedBorderColor = Color(0xFF39393D),
            ),
        )
    }
}

@Composable
private fun ChoiceRow(title: String, options: List<String>, selected: Int, fonts: List<FontFamily>? = null, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, color = Color.White, fontSize = 17.sp)
        Row(Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEachIndexed { i, label ->
                val on = i == selected
                Text(
                    label,
                    Modifier.clip(RoundedCornerShape(50)).background(if (on) Color.White else Color(0xFF2C2C2E))
                        .clickable { onSelect(i) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    color = if (on) Color.Black else Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    fontFamily = fonts?.get(i),
                )
            }
        }
    }
}

@Composable
private fun LinkRow(title: String, value: String, warn: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 52.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), color = Color.White, fontSize = 17.sp)
        Text(value, color = if (warn) Orange else Muted, fontSize = 17.sp)
        Text("  ›", color = Muted, fontSize = 22.sp)
    }
}
