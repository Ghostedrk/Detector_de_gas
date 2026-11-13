package com.example.detectordegas

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.repeatOnLifecycle
import com.example.detectordegas.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val model = ViewModelProvider(this)[DetectorModel::class.java]
        setContent {
            DetectorDeGasTheme {
                LaunchedEffect(model) {
                    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { model.poll() }
                }
                DetectorApp(model)
            }
        }
    }
}

/** Cómo se presenta cada estado: color de la cabecera, ícono, título y consejo para el usuario. */
private data class StateStyle(val color: Color, val icon: ImageVector, val title: String, val advice: String)

private fun stateStyle(running: Boolean, state: String?): StateStyle = when {
    !running -> StateStyle(Coral, Icons.Filled.PlayArrow, "Monitoreo detenido", "Pulsa Iniciar monitoreo para ver la lectura del detector.")
    state == "alerta" -> StateStyle(StateAlert, Icons.Filled.Warning, "Gas detectado",
        "Ventila el lugar, no enciendas fuego ni interruptores y revisa la cocina.")
    state == "prueba" -> StateStyle(StateWarn, Icons.Filled.Notifications, "Prueba de alarma",
        "El LED rojo y el buzzer están encendidos durante 3 segundos.")
    state == "calentando" -> StateStyle(StateWarn, Icons.Filled.Refresh, "Calentando el sensor",
        "Espera unos segundos antes de confiar en la lectura.")
    state == "sin_alerta" -> StateStyle(StateOk, Icons.Filled.CheckCircle, "Todo en orden",
        "La lectura está por debajo del umbral de alerta.")
    else -> StateStyle(StateUnknown, Icons.Filled.Info, "Sin datos del sensor",
        "Revisa la conexión del ESP32 o del teléfono emisor.")
}

private val TABS = listOf(
    Triple("Monitor", Icons.Filled.Home, "Monitoreo"),
    Triple("Historial", Icons.AutoMirrored.Filled.List, "Historial"),
    Triple("Teléfonos", Icons.Filled.Phone, "Dos teléfonos"),
    Triple("Ajustes", Icons.Filled.Settings, "Ajustes"),
)

@Composable
private fun DetectorApp(m: DetectorModel) {
    var tab by rememberSaveable { mutableStateOf(0) }
    val logged = m.user != null || m.demo
    val style = stateStyle(m.running, m.reading?.state ?: "sin_datos")
    val headerColor by animateColorAsState(if (logged) style.color else Coral, label = "estado")
    Scaffold(containerColor = MaterialTheme.colorScheme.background, contentWindowInsets = WindowInsets(0, 0, 0, 0), bottomBar = {
        if (logged) NavigationBar(containerColor = Color.White) {
            TABS.forEachIndexed { i, (label, icon, _) ->
                NavigationBarItem(selected = tab == i, onClick = { tab = i },
                    icon = { Icon(icon, contentDescription = null) }, label = { Text(label) },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = headerColor, selectedTextColor = headerColor,
                        indicatorColor = headerColor.copy(alpha = 0.14f)))
            }
        }
    }) { padding ->
        // Cada pestaña empieza arriba: el estado de desplazamiento depende de la pestaña y de la sesión.
        val scroll = key(tab, logged) { rememberScrollState() }
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(scroll)) {
            if (!logged) LoginHeader()
            else Header(m, style, headerColor, if (tab == 0) style.title else TABS[tab].third, showReading = tab == 0)
            Column(Modifier.offset(y = (-22).dp).padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (!logged) Login(m) else {
                    if (tab == 0) AdviceCard(style)
                    if (m.demo) Notice("Demostración: lecturas simuladas, sin conexión al circuito.", StateWarn, Icons.Filled.Info)
                    if (m.error.isNotEmpty()) Notice(m.error, StateAlert, Icons.Filled.Warning)
                    if (m.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = headerColor)
                    when (tab) {
                        0 -> Monitor(m, style)
                        1 -> HistoryScreen(m)
                        2 -> Phones(m)
                        else -> Settings(m)
                    }
                }
                Text("Prototipo educativo · Las lecturas son ADC, no ppm. No reemplaza un detector certificado.",
                    style = MaterialTheme.typography.bodySmall, color = TextSoft, modifier = Modifier.padding(horizontal = 4.dp))
            }
        }
    }
}

@Composable
private fun Header(m: DetectorModel, style: StateStyle, color: Color, title: String, showReading: Boolean) {
    val onHeader = Color.White
    Column(Modifier.fillMaxWidth().background(color, RoundedCornerShape(bottomStart = 30.dp, bottomEnd = 30.dp))
        .statusBarsPadding().padding(start = 22.dp, end = 22.dp, top = 16.dp, bottom = 40.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (m.demo) "Modo demostración" else "Hola, ${m.user?.username.orEmpty()}",
                color = onHeader.copy(alpha = 0.92f), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            HeaderPill(when { m.demo -> "Demo"; m.isAdmin -> "Administrador"; else -> "Operador" })
            Spacer(Modifier.width(6.dp))
            HeaderPill(if (m.publisher || m.demo) "Emisor" else "Receptor")
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (showReading) style.icon else TABS.first { it.third == title }.second, contentDescription = null,
                tint = onHeader, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(10.dp))
            Text(title, color = onHeader, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }
        if (showReading) {
            val value = m.reading?.value
            Text(value?.toString() ?: "—", color = onHeader, fontSize = 72.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { contentDescription = "Lectura ${value ?: "no disponible"} de 1023 ADC" })
            Text("ADC de 1023 · umbral de alerta 500", color = onHeader.copy(alpha = 0.92f))
            Spacer(Modifier.height(12.dp))
            ReadingBar((value ?: 0).coerceIn(0, 1023) / 1023f, 500 / 1023f)
        } else {
            Spacer(Modifier.height(6.dp))
            Text("Estado del detector: ${style.title.lowercase()}", color = onHeader.copy(alpha = 0.92f))
        }
    }
}

@Composable
private fun HeaderPill(text: String) {
    Text(text, color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp))
}

/** Barra de la lectura con la marca del umbral de alerta. */
@Composable
private fun ReadingBar(fraction: Float, threshold: Float) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(14.dp)) {
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.28f)))
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).clip(RoundedCornerShape(50)).background(Color.White))
        Box(Modifier.offset(x = maxWidth * threshold - 1.5.dp).width(3.dp).fillMaxHeight().background(Color.Black.copy(alpha = 0.35f)))
    }
}

@Composable
private fun AdviceCard(style: StateStyle) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, style.color.copy(alpha = 0.35f)), elevation = CardDefaults.cardElevation(2.dp),
        modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(style.icon, style.color, 46.dp)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(style.title, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = style.color)
                Text(style.advice, color = TextSoft)
            }
        }
    }
}

@Composable
private fun IconBadge(icon: ImageVector, color: Color, size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color.copy(alpha = 0.13f)), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(size * 0.52f))
    }
}

@Composable
private fun LoginHeader() {
    Column(Modifier.fillMaxWidth().background(Coral, RoundedCornerShape(bottomStart = 30.dp, bottomEnd = 30.dp))
        .statusBarsPadding().padding(start = 22.dp, end = 22.dp, top = 28.dp, bottom = 48.dp)) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text("Detector de gas", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text("Sensor MQ-2 y dos teléfonos en la misma red Wi-Fi. Funciona sin Internet.", color = Color.White.copy(alpha = 0.92f),
            style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Login(m: DetectorModel) {
    var password by remember { mutableStateOf("") }
    var register by rememberSaveable { mutableStateOf(false) }
    Section(if (register) "Crear cuenta en este teléfono" else "Iniciar sesión", if (register) Icons.Filled.Person else Icons.Filled.Lock) {
        OutlinedTextField(value = m.username, onValueChange = { m.username = it }, label = { Text("Usuario local") },
            leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null) },
            singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !m.busy, shape = RoundedCornerShape(14.dp))
        OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Contraseña") },
            leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !m.busy, shape = RoundedCornerShape(14.dp))
        BigButton(if (register) "Crear cuenta local" else "Entrar", Coral, enabled = !m.busy) { m.login(password, register); password = "" }
        TextButton(onClick = { register = !register }, enabled = !m.busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (register) "Ya tengo una cuenta en este teléfono" else "Crear una cuenta local")
        }
    }
    Text("Cada teléfono guarda sus propias cuentas e historial. Para enlazar los equipos usarás un código compartido, sin enviar tu contraseña.",
        color = TextSoft, modifier = Modifier.padding(horizontal = 4.dp))
    OutlinedButton(onClick = { m.enterDemo() }, enabled = !m.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        shape = RoundedCornerShape(16.dp)) {
        Icon(Icons.Filled.PlayArrow, contentDescription = null); Spacer(Modifier.width(6.dp)); Text("Explorar con datos simulados")
    }
}

@Composable
private fun Monitor(m: DetectorModel, style: StateStyle) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) m.start() else m.error = "Permite el acceso a dispositivos de la red local para consultar el ESP32."
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ActionTile(Icons.Filled.Notifications, "Probar alarma", when {
            !m.isAdmin -> "Solo el administrador"
            !m.running -> "Inicia el monitoreo"
            else -> "LED y buzzer 3 s"
        }, style.color, enabled = m.running && !m.busy && m.isAdmin, modifier = Modifier.weight(1f)) { m.testAlarm() }
        ActionTile(Icons.Filled.Call, "Pedir revisión", when {
            m.demo -> "No disponible en demo"
            !m.running -> "Inicia el monitoreo"
            else -> "Respuesta en Teléfonos"
        }, style.color, enabled = !m.demo && m.running && !m.busy, modifier = Modifier.weight(1f)) { m.requestReview() }
    }
    if (m.lastCommand.isNotEmpty()) Notice(m.lastCommand, StateWarn, Icons.Filled.Notifications)
    if (m.running) OutlinedButton(onClick = { m.stop() }, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
        shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
        Icon(Icons.Filled.Close, contentDescription = null); Spacer(Modifier.width(6.dp)); Text("Detener monitoreo", fontSize = 16.sp)
    } else BigButton("Iniciar monitoreo", Coral, icon = Icons.Filled.PlayArrow) {
        if (!m.demo && Build.VERSION.SDK_INT >= 37 &&
            context.checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") != PackageManager.PERMISSION_GRANTED)
            launcher.launch("android.permission.ACCESS_LOCAL_NETWORK")
        else m.start()
    }
    Section("Conexión", Icons.Filled.Info) {
        InfoRow(Icons.Filled.Home, when {
            m.demo -> "Sensor simulado"
            m.publisher && m.simulatedSensor -> "ESP32 simulado"
            m.publisher -> "Sensor conectado al ESP32"
            else -> "Lectura compartida por el emisor"
        }, m.sensorStatus)
        InfoRow(Icons.Filled.Phone, if (m.demo) "Escenario de demostración" else if (m.publisher) "Emisor · ESP32 → este teléfono → otro Android"
            else "Receptor · otro Android → este teléfono", m.peerStatus)
        m.reading?.let { InfoRow(Icons.Filled.Refresh, "Último dato", date(it.time)) }
        Text("El monitoreo funciona mientras la aplicación está visible. Cambia el rol o la IP en Ajustes.",
            style = MaterialTheme.typography.bodySmall, color = TextSoft)
    }
}

@Composable
private fun ActionTile(icon: ImageVector, title: String, subtitle: String, color: Color, enabled: Boolean,
                       modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(20.dp), modifier = modifier.heightIn(min = 120.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White, disabledContainerColor = Color.White),
        border = BorderStroke(1.dp, WarmLine)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            IconBadge(icon, if (enabled) color else StateUnknown.copy(alpha = 0.6f), 42.dp)
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = if (enabled) Color(0xFF2C2C2A) else TextSoft)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextSoft)
        }
    }
}

@Composable
private fun InfoRow(icon: ImageVector, title: String, detail: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = Coral, modifier = Modifier.size(20.dp).padding(top = 2.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, fontWeight = FontWeight.SemiBold)
            if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = TextSoft)
        }
    }
}

@Composable
private fun BigButton(text: String, color: Color, enabled: Boolean = true, icon: ImageVector? = null, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
        shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = color)) {
        if (icon != null) { Icon(icon, contentDescription = null); Spacer(Modifier.width(6.dp)) }
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun HistoryScreen(m: DetectorModel) {
    var confirm by remember { mutableStateOf(false) }
    var fromCloud by rememberSaveable { mutableStateOf(false) }
    Section(if (fromCloud && !m.demo) "Historial en la nube" else "Historial en este teléfono", Icons.AutoMirrored.Filled.List) {
        if (!m.demo) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !fromCloud, onClick = { fromCloud = false }, label = { Text("Este teléfono · SQLite") })
            FilterChip(selected = fromCloud, onClick = { fromCloud = true; if (m.cloudEmail.isNotEmpty() && m.cloudHistory.isEmpty()) m.refreshCloud() },
                label = { Text("Nube · Firestore") })
        }
        if (fromCloud && !m.demo) {
            when {
                !m.cloudConfigured -> Text("Firebase no está configurado en esta compilación. Revisa la sección Nube de la guía.", color = TextSoft)
                m.cloudEmail.isEmpty() -> Text("El administrador debe iniciar sesión en la nube desde Ajustes.", color = TextSoft)
                else -> {
                    Text("Últimas 100 lecturas subidas por el emisor a la cuenta ${m.cloudEmail}.", color = TextSoft)
                    OutlinedButton(onClick = { m.refreshCloud() }, enabled = !m.busy, shape = RoundedCornerShape(14.dp)) {
                        Icon(Icons.Filled.Refresh, contentDescription = null); Spacer(Modifier.width(6.dp)); Text("Actualizar desde la nube")
                    }
                }
            }
            if (m.cloudStatus.isNotEmpty()) Text(m.cloudStatus, style = MaterialTheme.typography.bodySmall, color = TextSoft)
        } else {
            Text("Últimas 100 lecturas de esta cuenta. Se conservan hasta 500 registros locales.", color = TextSoft)
            if (m.isAdmin) TextButton(onClick = { confirm = true }) {
                Icon(Icons.Filled.Delete, contentDescription = null); Spacer(Modifier.width(6.dp)); Text("Borrar historial local")
            }
        }
    }
    val list = if (fromCloud && !m.demo) m.cloudHistory else m.localHistory
    if (list.isEmpty()) Text("Aún no hay lecturas. Inicia el monitoreo para guardar registros.", color = TextSoft,
        modifier = Modifier.padding(horizontal = 4.dp))
    list.forEach { r ->
        val s = stateStyle(true, r.state)
        Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, WarmLine), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBadge(s.icon, s.color, 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stateLabel(r.state), fontWeight = FontWeight.SemiBold)
                    Text(date(r.time), style = MaterialTheme.typography.bodySmall, color = TextSoft)
                    Text(r.source, style = MaterialTheme.typography.labelSmall, color = TextSoft)
                }
                Text("${r.value ?: "—"}", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = s.color)
                Text(" ADC", style = MaterialTheme.typography.bodySmall, color = TextSoft)
            }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Borrar historial local") },
        text = { Text("Se borrarán los registros de esta cuenta en este teléfono.") },
        confirmButton = { TextButton(onClick = { m.clearHistory(); confirm = false }) { Text("Borrar") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancelar") } })
}

@Composable
private fun Phones(m: DetectorModel) {
    Section("Este teléfono", Icons.Filled.Phone) {
        Text(m.deviceName, fontWeight = FontWeight.SemiBold)
        Text("Conecta ambos Android a la misma red. En Ajustes elige un emisor y un receptor, y copia el código del emisor al receptor.",
            color = TextSoft)
    }
    Section("Enlace por Wi-Fi local", Icons.Filled.Share) {
        Text(m.peerStatus)
        if (m.publisher && m.localIps.isNotEmpty()) SelectionContainer {
            Text("IP del emisor:\n${m.localIps}", fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)).padding(12.dp),
                color = CoralDark)
        }
        Text("Usa la dirección de la interfaz Wi-Fi o del punto de acceso. El puerto de enlace es 8765.",
            style = MaterialTheme.typography.bodySmall, color = TextSoft)
    }
    Section("Solicitar y confirmar una revisión", Icons.Filled.Call) {
        Text("Envía una solicitud desde un Android. En el otro, confirma que revisaste el detector. La respuesta aparecerá en ambos.", color = TextSoft)
        BigButton("Solicitar revisión al otro teléfono", Coral, enabled = !m.demo && m.running && !m.busy, icon = Icons.Filled.Call) { m.requestReview() }
        if (m.demo) Text("La comunicación entre teléfonos requiere iniciar sesión local y enlazarlos.", color = TextSoft)
        else if (!m.running) Text("Inicia el monitoreo en ambos teléfonos para intercambiar solicitudes.", color = TextSoft)
        Text("Las solicitudes se guardan en memoria en el emisor y se borran al cerrar sesión. La confirmación no cambia el buzzer ni los LED.",
            style = MaterialTheme.typography.bodySmall, color = TextSoft)
    }
    m.reviews.forEach { review ->
        val mine = review.sender == m.deviceId
        Section(if (mine) "Solicitud enviada" else "Solicitud recibida", if (mine) Icons.AutoMirrored.Filled.Send else Icons.Filled.Notifications) {
            Text("Desde ${review.device} · ${date(review.time)}", color = TextSoft)
            if (review.ack.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = StateOk); Spacer(Modifier.width(6.dp))
                Text("Revisión confirmada por ${review.ack}", color = StateOk, fontWeight = FontWeight.SemiBold)
            }
            else if (mine) Text("Esperando confirmación del otro teléfono…")
            else BigButton("Confirmar revisión", StateOk, enabled = m.running && !m.busy, icon = Icons.Filled.Check) { m.acknowledge(review) }
        }
    }
}

@Composable
private fun Settings(m: DetectorModel) {
    var showCode by remember { mutableStateOf(false) }
    val editable = !m.running && !m.demo && m.isAdmin
    if (!m.isAdmin) Notice("Cuenta de operador: puedes monitorear, consultar el historial y gestionar revisiones. " +
        "La configuración, las claves y las órdenes al detector son del administrador.", StateOk, Icons.Filled.Lock)
    Section("Función de este teléfono", Icons.Filled.Phone) {
        Text("Detén el monitoreo antes de cambiar la configuración.", color = TextSoft)
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = m.publisher, onClick = { m.choosePublisher(true) }, enabled = editable)
            Text("Emisor: consulta el ESP32", modifier = Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = !m.publisher, onClick = { m.choosePublisher(false) }, enabled = editable)
            Text("Receptor: consulta al emisor", modifier = Modifier.weight(1f))
        }
        if (!m.publisher || !m.simulatedSensor) {
            OutlinedTextField(value = if (m.publisher) m.ip else m.peerIp,
                onValueChange = { if (m.publisher) m.ip = it else m.peerIp = it },
                label = { Text(if (m.publisher) "IP local del ESP32" else "IP Wi-Fi del teléfono emisor") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(), enabled = editable, shape = RoundedCornerShape(14.dp))
            OutlinedButton(onClick = { m.saveSettings() }, enabled = editable, shape = RoundedCornerShape(14.dp)) { Text("Guardar dirección") }
        }
    }
    if (!m.demo && m.publisher) Section("Fuente del sensor", Icons.Filled.Home) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = !m.simulatedSensor, onClick = { m.chooseSimulated(false) }, enabled = editable)
            Text("ESP32 real del circuito", modifier = Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = m.simulatedSensor, onClick = { m.chooseSimulated(true) }, enabled = editable)
            Text("ESP32 simulado (sin circuito)", modifier = Modifier.weight(1f))
        }
        Text(if (m.simulatedSensor) "El emisor genera lecturas con la misma lógica del Arduino y las comparte con el receptor por Wi-Fi."
            else "Cada consulta al ESP32 se firma con HMAC-SHA256 usando la clave del dispositivo.",
            style = MaterialTheme.typography.bodySmall, color = TextSoft)
        if (!m.simulatedSensor && m.isAdmin) {
            OutlinedTextField(value = m.deviceKey, onValueChange = { m.deviceKey = it.trim() },
                label = { Text("Clave del ESP32 (64 caracteres)") }, modifier = Modifier.fillMaxWidth(), enabled = editable,
                shape = RoundedCornerShape(14.dp))
            OutlinedButton(onClick = { m.generateDeviceKey() }, enabled = editable, shape = RoundedCornerShape(14.dp)) { Text("Generar clave nueva") }
            Text("Copia esta misma clave en CLAVE_DISPOSITIVO del programa ESP32. Se guarda cifrada con Android Keystore.",
                style = MaterialTheme.typography.bodySmall, color = TextSoft)
        }
    }
    if (!m.demo && m.isAdmin) Section("Código de enlace entre teléfonos", Icons.Filled.Lock) {
        Text(if (m.publisher) "Copia este código en Ajustes del receptor. Puedes mantener pulsado el texto para seleccionarlo."
            else "Pega los 64 caracteres del código mostrado por el teléfono emisor.", color = TextSoft)
        if (m.publisher) {
            TextButton(onClick = { showCode = !showCode }) { Text(if (showCode) "Ocultar código" else "Mostrar código de enlace") }
            if (showCode) SelectionContainer {
                Text(m.pairingCode, style = MaterialTheme.typography.bodyMedium, color = CoralDark,
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)).padding(12.dp))
            }
            OutlinedButton(onClick = { m.regenerateCode() }, enabled = editable, shape = RoundedCornerShape(14.dp)) { Text("Generar otro código") }
        } else OutlinedTextField(value = m.pairingCode, onValueChange = { m.pairingCode = it },
            label = { Text("Código del emisor") }, modifier = Modifier.fillMaxWidth(), enabled = editable, shape = RoundedCornerShape(14.dp))
        Text("El código autoriza el enlace cifrado. Guárdalo entre tus dos teléfonos. Se conserva protegido en este dispositivo.",
            style = MaterialTheme.typography.bodySmall, color = TextSoft)
    }
    if (!m.demo) CloudSection(m)
    Section("Sesión", Icons.Filled.Person) {
        Text(m.user?.let { "${it.username} · ${it.role}" } ?: "Demostración sin iniciar sesión", fontWeight = FontWeight.SemiBold)
        if (!m.demo) Text("La primera cuenta creada en el teléfono es administradora; las siguientes son operadoras.",
            style = MaterialTheme.typography.bodySmall, color = TextSoft)
        Text("Las cuentas locales y el historial SQLite están en este teléfono. La nube (Firebase) es opcional y la conecta el administrador.", color = TextSoft)
        Text("Mantén abiertas ambas aplicaciones durante la evaluación. La prueba de alarma se envía desde Monitor o con T en el Monitor serie del Arduino.",
            color = TextSoft)
        OutlinedButton(onClick = { m.logout() }, enabled = !m.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
            shape = RoundedCornerShape(14.dp)) {
            Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null); Spacer(Modifier.width(6.dp))
            Text(if (m.demo) "Salir de la demostración" else "Cerrar sesión")
        }
    }
}

@Composable
private fun CloudSection(m: DetectorModel) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Section("Nube · Firebase", Icons.Filled.Share) {
        when {
            !m.cloudConfigured -> Text("Firebase no está configurado: falta app/google-services.json. La app funciona solo en local.",
                style = MaterialTheme.typography.bodySmall, color = TextSoft)
            m.cloudEmail.isNotEmpty() -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = StateOk); Spacer(Modifier.width(6.dp))
                    Text("Conectado como ${m.cloudEmail}", fontWeight = FontWeight.SemiBold)
                }
                Text(if (m.publisher) "Este emisor sube cada lectura guardada a Cloud Firestore." else
                    "Este receptor puede consultar en Historial las lecturas que subió el emisor con la misma cuenta.",
                    style = MaterialTheme.typography.bodySmall, color = TextSoft)
                if (m.isAdmin) OutlinedButton(onClick = { m.cloudSignOut() }, enabled = !m.busy, shape = RoundedCornerShape(14.dp)) {
                    Text("Cerrar sesión de la nube")
                }
            }
            !m.isAdmin -> Text("La nube no está conectada. Solo el administrador puede conectarla.", color = TextSoft)
            else -> {
                Text("Usa la misma cuenta en los dos teléfonos para ver el historial del emisor desde cualquier lugar con Internet.",
                    style = MaterialTheme.typography.bodySmall, color = TextSoft)
                OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Correo") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Email, contentDescription = null) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth(),
                    enabled = !m.busy, shape = RoundedCornerShape(14.dp))
                OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Contraseña de la nube") },
                    leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !m.busy, shape = RoundedCornerShape(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { m.cloudSignIn(email, password, false); password = "" }, enabled = !m.busy,
                        shape = RoundedCornerShape(14.dp)) { Text("Entrar") }
                    OutlinedButton(onClick = { m.cloudSignIn(email, password, true); password = "" }, enabled = !m.busy,
                        shape = RoundedCornerShape(14.dp)) { Text("Crear cuenta") }
                }
            }
        }
        if (m.cloudStatus.isNotEmpty() && m.cloudConfigured) Text(m.cloudStatus, style = MaterialTheme.typography.bodySmall, color = TextSoft)
    }
}

@Composable
private fun Section(title: String, icon: ImageVector? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, WarmLine), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) { IconBadge(icon, Coral, 34.dp); Spacer(Modifier.width(10.dp)) }
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            content()
        }
    }
}

@Composable
private fun Notice(message: String, color: Color, icon: ImageVector) {
    Row(Modifier.fillMaxWidth().background(color.copy(alpha = 0.1f), RoundedCornerShape(16.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Text(message, color = color)
    }
}

private fun date(time: Long): String = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()).format(Date(time))
