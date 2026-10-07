package com.theveloper.pixelplay.spike.sonos

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SPIKE. Pantalla de depuración, solo en builds debug (icono "Sonos Spike"). No toca la UI principal.
 * Fase A: log y foto de red. Fase B: permiso de red local, SSDP, escaneo /24, topología y volumen.
 */
class SonosDebugActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SonosSpike.current(applicationContext)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { SonosDebugScreen() }
            }
        }
    }
}

/** Android 17+ (targetSdk 37): ACCESS_LOCAL_NETWORK. Android 16: NEARBY_WIFI_DEVICES (opt-in de prueba). */
private val LOCAL_NETWORK_PERMISSION: String =
    if (Build.VERSION.SDK_INT >= 37) "android.permission.ACCESS_LOCAL_NETWORK" else Manifest.permission.NEARBY_WIFI_DEVICES

private fun permissionState(context: Context): Map<String, Boolean> = mapOf(
    "nearby_wifi_devices" to granted(context, Manifest.permission.NEARBY_WIFI_DEVICES),
    "access_local_network" to granted(context, "android.permission.ACCESS_LOCAL_NETWORK"),
)

private fun granted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

@Composable
private fun SonosDebugScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("sonos_spike", Context.MODE_PRIVATE) }
    var run by remember { mutableStateOf(SonosSpike.current(context)) }
    val lines by run.lines.collectAsState()
    var ip by remember { mutableStateOf(prefs.getString("ip", "") ?: "") }
    var note by remember { mutableStateOf("") }
    var found by remember { mutableStateOf(listOf<String>()) }
    var busy by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableIntStateOf(0) }
    var audio by remember { mutableStateOf(listOf<SonosServer.AudioItem>()) }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var published by remember { mutableStateOf(listOf<SonosServer.Published>()) }
    var serverStatus by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("NORMAL") }
    var monitoring by remember { mutableStateOf(false) }
    val monitorStop = remember { AtomicBoolean(false) }
    var fieldWifi by remember { mutableStateOf(prefs.getString("field_wifi", "none") ?: "none") }
    var fieldWake by remember { mutableStateOf(prefs.getBoolean("field_wake", false)) }
    var fieldStatus by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            serverStatus = SonosServer.statusLine()
            fieldStatus = SonosField.statusLine()
            delay(1000)
        }
    }
    val audioPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        run.event("permission_result", "permission" to Manifest.permission.READ_MEDIA_AUDIO, "granted" to ok)
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        run.event(
            "permission_result",
            "permission" to LOCAL_NETWORK_PERMISSION,
            "granted" to ok,
            "state" to permissionState(context),
        )
    }

    fun task(label: String, block: (SonosRunLog) -> Unit) {
        if (busy != null) return
        val r = run
        busy = label
        scope.launch(Dispatchers.IO) {
            try {
                block(r)
            } catch (t: Throwable) {
                r.err("$label falló: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                busy = null
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Sonos spike · fases A–F", style = MaterialTheme.typography.titleMedium)
        Text(run.dir.absolutePath, style = MaterialTheme.typography.labelSmall)
        Text(busy?.let { "Trabajando: $it…" } ?: "Listo", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { run = SonosSpike.newRun(context) }) { Text("Nueva corrida") }
            OutlinedButton(onClick = { shareLog(context, run) }) { Text("Compartir log") }
        }
        TabRow(selectedTabIndex = tab) {
            listOf("Red · SOAP", "Servidor", "Cola", "Campo").forEachIndexed { i, label ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) })
            }
        }

        Column(
            Modifier.verticalScroll(rememberScrollState()).weight(0.45f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (tab == 3) {
                Text(serverStatus, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                Text(fieldStatus, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                Text("Condiciones de la corrida (anótalas en tu libreta):", fontSize = 12.sp)
                Text("WifiLock", fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("none", "HIGH_PERF", "LOW_LATENCY").forEach { m ->
                        val pick = { fieldWifi = m; prefs.edit().putString("field_wifi", m).apply() }
                        if (m == fieldWifi) Button(onClick = pick) { Text(m, fontSize = 10.sp) }
                        else OutlinedButton(onClick = pick) { Text(m, fontSize = 10.sp) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = fieldWake, onCheckedChange = { fieldWake = it; prefs.edit().putBoolean("field_wake", it).apply() })
                    Text("WakeLock parcial", fontSize = 12.sp)
                }
                if (!SonosField.isRunning()) {
                    Button(enabled = true, onClick = {
                        SonosField.start(context, ip.ifBlank { null }, SonosField.Config(fieldWifi, fieldWake))
                    }) { Text("Iniciar observación") }
                } else {
                    Button(onClick = { SonosField.stop(context) }) { Text("Detener observación") }
                }
                Text(
                    "Pasos: 1) pestaña Servidor: publica las 5 pistas; 2) pestaña Cola: crea la cola con REPEAT_ALL; " +
                        "3) aquí: Iniciar observación; 4) desenchufa el cable, apaga la pantalla y NO toques nada ≥ 20 min.",
                    fontSize = 11.sp,
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Nota") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = {
                    run.event("mark", "msg" to note)
                    note = ""
                }) { Text("Marcar evento") }
            } else if (tab == 2) {
                Text(serverStatus, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                Text("Publicadas: ${published.size} · IP: ${ip.ifBlank { "—" }}", fontSize = 12.sp)
                Text("Modo al empezar:", fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("NORMAL", "SHUFFLE", "REPEAT_ALL", "REPEAT_ONE").forEach { m ->
                        // Al tocar un modo se aplica de inmediato sobre la cola actual (y se usará en la próxima cola).
                        val pick = {
                            mode = m
                            if (ip.isNotBlank()) task("Modo $m") { r -> SonosQueue.setPlayMode(context, r, ip, m) }
                        }
                        if (m == mode) Button(onClick = pick) { Text(m, fontSize = 10.sp) }
                        else OutlinedButton(onClick = pick) { Text(m, fontSize = 10.sp) }
                    }
                }
                val ready = busy == null && ip.isNotBlank()
                Button(enabled = ready && published.isNotEmpty(), onClick = {
                    task("Cola + Play") { r -> SonosQueue.enqueueAndPlay(context, r, ip, published, mode) }
                }) { Text("Cola (${published.size}) + Play") }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(enabled = ready, onClick = { task("Anterior") { r -> SonosQueue.previousSmart(context, r, ip) } }) { Text("⏮") }
                    OutlinedButton(enabled = ready, onClick = { task("Play") { r -> SonosQueue.transport(context, r, ip, "Play") } }) { Text("▶") }
                    OutlinedButton(enabled = ready, onClick = { task("Pausa") { r -> SonosQueue.transport(context, r, ip, "Pause") } }) { Text("⏸") }
                    OutlinedButton(enabled = ready, onClick = { task("Siguiente") { r -> SonosQueue.transport(context, r, ip, "Next") } }) { Text("⏭") }
                    OutlinedButton(enabled = ready, onClick = { task("Stop") { r -> SonosQueue.transport(context, r, ip, "Stop") } }) { Text("⏹") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(enabled = ready, onClick = { task("Estado") { r -> SonosQueue.status(context, r, ip) } }) { Text("Estado") }
                    OutlinedButton(enabled = ready, onClick = { task("Ver cola") { r -> SonosQueue.browseQueue(context, r, ip) } }) { Text("Ver cola") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(enabled = ready, onClick = { task("Reenganchar") { r -> SonosQueue.reattach(context, r, ip) } }) { Text("Reenganchar cola del altavoz") }
                    OutlinedButton(enabled = ready, onClick = { task("Vol -2") { r -> SonosLab.nudgeVolume(context, r, ip, -2) } }) { Text("Vol −2") }
                    OutlinedButton(enabled = ready, onClick = { task("Vol +2") { r -> SonosLab.nudgeVolume(context, r, ip, +2) } }) { Text("Vol +2") }
                }
                Text("El monitor continuo ahora vive en la pestaña Campo (sobrevive a la pantalla apagada).", fontSize = 11.sp)
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Nota") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = {
                    run.event("mark", "msg" to note)
                    note = ""
                }) { Text("Marcar evento") }
            } else if (tab == 1) {
                Text(serverStatus, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = busy == null, onClick = {
                        task("Iniciar servidor") { r -> SonosServer.start(context, r, ip.ifBlank { null }) }
                    }) { Text("Iniciar servidor") }
                    OutlinedButton(enabled = busy == null, onClick = {
                        task("Detener servidor") { r -> SonosServer.stop(context, r) }
                    }) { Text("Detener") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { audioPermissionLauncher.launch(Manifest.permission.READ_MEDIA_AUDIO) }) { Text("Permiso audio") }
                    Button(enabled = busy == null, onClick = {
                        task("Listar audio") { r ->
                            audio = SonosServer.listAudio(context, r)
                            selected = emptySet()
                        }
                    }) { Text("Listar audio") }
                }
                audio.forEach { item ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = item.id in selected,
                            onCheckedChange = { on -> selected = if (on) selected + item.id else selected - item.id },
                        )
                        Text("${item.name} · ${item.mime} · ${item.size / 1024} KB", fontSize = 12.sp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = busy == null && selected.isNotEmpty(), onClick = {
                        task("Publicar") { r ->
                            published = SonosServer.publish(context, r, audio.filter { it.id in selected }, ip.ifBlank { null })
                        }
                    }) { Text("Publicar (${selected.size})") }
                    OutlinedButton(enabled = busy == null && published.isNotEmpty(), onClick = {
                        task("Autoprueba") { r -> SonosServer.selfTest(context, r, published) }
                    }) { Text("Autoprueba") }
                }
                OutlinedButton(enabled = busy == null, onClick = {
                    task("Republicar últimas") { r -> published = SonosServer.republishLast(context, r, ip.ifBlank { null }) }
                }) { Text("Republicar últimas (reenganche)") }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Nota") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = {
                    run.event("mark", "msg" to note)
                    note = ""
                }) { Text("Marcar evento") }
            } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    run.event(
                        "network_snapshot",
                        "networks" to SonosNetworkInfo.snapshot(context),
                        "permissions" to permissionState(context),
                    )
                }) { Text("Foto de red") }
                OutlinedButton(onClick = { permissionLauncher.launch(LOCAL_NETWORK_PERMISSION) }) { Text("Permiso red local") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = busy == null, onClick = {
                    task("SSDP") { r ->
                        found = SonosLab.discover(context, r)
                        if (found.size == 1 && ip.isBlank()) ip = found.first()
                    }
                }) { Text("Buscar SSDP") }
                OutlinedButton(enabled = busy == null, onClick = {
                    task("Escaneo /24") { r -> found = SonosSsdp.scan(context, r) }
                }) { Text("Escanear /24") }
            }
            if (found.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    found.take(4).forEach { candidate -> OutlinedButton(onClick = { ip = candidate }) { Text(candidate) } }
                }
            }
            OutlinedTextField(
                value = ip,
                onValueChange = {
                    ip = it.trim()
                    prefs.edit().putString("ip", ip).apply()
                },
                label = { Text("IP del Sonos") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = busy == null && ip.isNotBlank(), onClick = {
                    task("Topología") { r -> SonosLab.topology(context, r, ip) }
                }) { Text("Topología") }
                OutlinedButton(enabled = busy == null && ip.isNotBlank(), onClick = {
                    task("Leer volumen") { r -> SonosLab.getVolume(context, r, ip) }
                }) { Text("Volumen") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = busy == null && ip.isNotBlank(), onClick = {
                    task("Vol -2") { r -> SonosLab.nudgeVolume(context, r, ip, -2) }
                }) { Text("Vol −2") }
                OutlinedButton(enabled = busy == null && ip.isNotBlank(), onClick = {
                    task("Vol +2") { r -> SonosLab.nudgeVolume(context, r, ip, +2) }
                }) { Text("Vol +2 (tope ${SonosProtocol.DEFAULT_MAX_VOLUME})") }
            }
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Nota") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = {
                run.event("mark", "msg" to note)
                note = ""
            }) { Text("Marcar evento") }
            }
        }

        LazyColumn(Modifier.fillMaxWidth().weight(0.55f)) {
            items(lines.asReversed()) { line ->
                Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }
    }
}

private fun shareLog(context: Context, run: SonosRunLog) {
    runCatching {
        val dir = File(context.cacheDir, "sonos-share").apply { mkdirs() }
        val copy = File(dir, run.runId + "_log.jsonl")
        run.file.copyTo(copy, overwrite = true)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".provider", copy)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Log del spike"))
    }.onFailure { run.err("No pude compartir el log: ${it.javaClass.simpleName}: ${it.message}") }
}
