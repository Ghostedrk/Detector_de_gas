package com.example.detectordegas

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.example.detectordegas.ui.theme.DetectorDeGasTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Teal = Color(0xFF006C65)
private val Red = Color(0xFFB3261E)
private val Amber = Color(0xFF885200)

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

@Composable
private fun DetectorApp(m: DetectorModel) {
    var tab by rememberSaveable { mutableStateOf(0) }
    val logged = m.user != null || m.demo
    Scaffold(containerColor = MaterialTheme.colorScheme.background, bottomBar = {
        if (logged) NavigationBar {
            listOf("Monitor" to "◉", "Historial" to "≡", "Teléfonos" to "⇄", "Ajustes" to "⚙").forEachIndexed { i, item ->
                NavigationBarItem(selected = tab == i, onClick = { tab = i },
                    icon = { Text(item.second, fontSize = 23.sp) }, label = { Text(item.first) })
            }
        }
    }) { padding ->
        // Cada pestaña empieza arriba: el estado de desplazamiento depende de la pestaña y de la sesión.
        val scroll = key(tab, logged) { rememberScrollState() }
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(scroll)
            .padding(horizontal = 22.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("DETECTOR DE GAS", style = MaterialTheme.typography.labelLarge, color = Teal)
                    Text(if (!logged) "Tu detector, a mano" else listOf("Monitoreo", "Historial de lecturas", "Dos teléfonos", "Configuración")[tab],
                        style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                }
                Text("MQ-2", color = Teal, fontWeight = FontWeight.Bold)
            }
            if (m.demo) Notice("DEMOSTRACIÓN · Lecturas simuladas. No hay conexión al circuito.", Amber)
            if (m.error.isNotEmpty()) Notice(m.error, Red)
            if (m.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!logged) Login(m) else when (tab) {
                0 -> Monitor(m)
                1 -> HistoryScreen(m)
                2 -> Phones(m)
                else -> Settings(m)
            }
            Text("Prototipo educativo · Las lecturas son ADC, no ppm. No reemplaza un detector certificado.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Login(m: DetectorModel) {
    var password by remember { mutableStateOf("") }
    var register by rememberSaveable { mutableStateOf(false) }
    Text("Detector y dos teléfonos en la misma red Wi-Fi. Funciona sin Internet.", style = MaterialTheme.typography.bodyLarge)
    Section(if (register) "Crear cuenta en este teléfono" else "Iniciar sesión local") {
        OutlinedTextField(value = m.username, onValueChange = { m.username = it }, label = { Text("Usuario local") },
            singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !m.busy)
        OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Contraseña") },
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !m.busy)
        Button(onClick = { m.login(password, register); password = "" }, enabled = !m.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) { Text(if (register) "Crear cuenta local" else "Entrar") }
        TextButton(onClick = { register = !register }, enabled = !m.busy) {
            Text(if (register) "Ya tengo una cuenta en este teléfono" else "Crear una cuenta local")
        }
    }
    Text("Cada teléfono guarda sus propias cuentas e historial. Para enlazar los equipos usarás un código compartido, sin enviar tu contraseña.")
    TextButton(onClick = { m.enterDemo() }, enabled = !m.busy, modifier = Modifier.fillMaxWidth()) { Text("Explorar con datos simulados") }
}

@Composable
private fun Monitor(m: DetectorModel) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) m.start() else m.error = "Permite el acceso a dispositivos de la red local para consultar el ESP32."
    }
    val r = m.reading
    val color = when (r?.state) { "alerta" -> Red; "calentando", "prueba" -> Amber; "sin_alerta" -> Teal; else -> Color.Gray }
    Section(when {
        m.demo -> "Sensor simulado"
        m.publisher && m.simulatedSensor -> "ESP32 simulado"
        m.publisher -> "Sensor conectado al ESP32"
        else -> "Lectura compartida"
    }) {
        Text(if (!m.running) "Monitoreo detenido" else stateLabel(r?.state ?: "sin_datos"), color = color,
            fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
        Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(225.dp, 180.dp).semantics { contentDescription = "Lectura ${r?.value ?: "no disponible"} de 1023 ADC" }) {
                val diameter = size.width - 22.dp.toPx()
                val top = Offset(11.dp.toPx(), 20.dp.toPx())
                val arcSize = Size(diameter, diameter)
                drawArc(Color(0xFFE0E9E6), 150f, 240f, false, top, arcSize, style = Stroke(15.dp.toPx(), cap = StrokeCap.Round))
                val fraction = (r?.value ?: 0).coerceIn(0, 1023) / 1023f
                if (fraction > 0f) drawArc(color, 150f, 240f * fraction, false, top, arcSize,
                    style = Stroke(15.dp.toPx(), cap = StrokeCap.Round))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 34.dp)) {
                Text(r?.value?.toString() ?: "—", fontSize = 52.sp, fontWeight = FontWeight.Bold)
                Text("ADC / 1023", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(m.sensorStatus)
        r?.let { Text("Último dato: ${date(it.time)}", style = MaterialTheme.typography.bodySmall) }
        if (m.running) OutlinedButton(onClick = { m.stop() }, modifier = Modifier.fillMaxWidth()) { Text("Detener monitoreo") }
        else Button(onClick = {
            if (!m.demo && Build.VERSION.SDK_INT >= 37 &&
                context.checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") != PackageManager.PERMISSION_GRANTED)
                launcher.launch("android.permission.ACCESS_LOCAL_NETWORK")
            else m.start()
        }, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) { Text("Iniciar monitoreo") }
    }
    Section("Control del detector") {
        Text(if (m.publisher || m.demo) "Activa el LED rojo y el buzzer durante 3 segundos para comprobar la alarma."
            else "La orden viaja cifrada al emisor, que la reenvía firmada al ESP32.")
        Button(onClick = { m.testAlarm() }, enabled = m.running && !m.busy && m.isAdmin,
            colors = ButtonDefaults.buttonColors(containerColor = Amber),
            modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) { Text("Probar alarma (3 s)") }
        if (!m.isAdmin) Text("Solo el administrador puede enviar órdenes al detector.", style = MaterialTheme.typography.bodySmall)
        else if (!m.running) Text("Inicia el monitoreo para enviar órdenes.", style = MaterialTheme.typography.bodySmall)
        if (m.lastCommand.isNotEmpty()) Text(m.lastCommand, style = MaterialTheme.typography.bodySmall)
    }
    Section("Conexión") {
        Text(if (m.demo) "Escenario de demostración" else if (m.publisher) "Emisor · ESP32 → este teléfono → otro Android" else "Receptor · otro Android → este teléfono")
        Text(m.peerStatus, style = MaterialTheme.typography.bodySmall)
        Text("El monitoreo funciona mientras la aplicación está visible. Vuelve a Ajustes para cambiar de rol o IP.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun HistoryScreen(m: DetectorModel) {
    var confirm by remember { mutableStateOf(false) }
    var fromCloud by rememberSaveable { mutableStateOf(false) }
    if (!m.demo) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = !fromCloud, onClick = { fromCloud = false }, label = { Text("Este teléfono · SQLite") })
        FilterChip(selected = fromCloud, onClick = { fromCloud = true; if (m.cloudEmail.isNotEmpty() && m.cloudHistory.isEmpty()) m.refreshCloud() },
            label = { Text("Nube · Firestore") })
    }
    val list = if (fromCloud && !m.demo) m.cloudHistory else m.localHistory
    if (fromCloud && !m.demo) {
        when {
            !m.cloudConfigured -> Text("Firebase no está configurado en esta compilación. Revisa la sección Nube de la guía.")
            m.cloudEmail.isEmpty() -> Text("El administrador debe iniciar sesión en la nube desde Ajustes.")
            else -> {
                Text("Últimas 100 lecturas subidas por el emisor a la cuenta ${m.cloudEmail}.")
                OutlinedButton(onClick = { m.refreshCloud() }, enabled = !m.busy) { Text("Actualizar desde la nube") }
            }
        }
        if (m.cloudStatus.isNotEmpty()) Text(m.cloudStatus, style = MaterialTheme.typography.bodySmall)
    } else {
        Text("Últimas 100 lecturas de esta cuenta en este teléfono. Se conservan hasta 500 registros locales.")
        if (m.isAdmin) TextButton(onClick = { confirm = true }) { Text("Borrar historial local") }
    }
    if (list.isEmpty()) Text("Aún no hay lecturas. Inicia el monitoreo para guardar registros.")
    list.forEach { r ->
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stateLabel(r.state), fontWeight = FontWeight.SemiBold)
                    Text(date(r.time), style = MaterialTheme.typography.bodySmall)
                    Text(r.source, style = MaterialTheme.typography.labelSmall)
                }
                Text("${r.value ?: "—"} ADC", fontWeight = FontWeight.Bold, color = if (r.state == "alerta") Red else Teal)
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
    Text("Este teléfono: ${m.deviceName}", fontWeight = FontWeight.SemiBold)
    Text("Conecta ambos Android a la misma red. En Ajustes elige un emisor y un receptor, y copia el código del emisor al receptor.")
    Section("Enlace por Wi-Fi local") {
        Text(m.peerStatus)
        if (m.publisher && m.localIps.isNotEmpty()) SelectionContainer { Text("IP del emisor:\n${m.localIps}") }
        Text("Usa la dirección de la interfaz Wi-Fi o del punto de acceso. El puerto de enlace es 8765.", style = MaterialTheme.typography.bodySmall)
    }
    Section("Solicitar y confirmar una revisión") {
        Text("Envía una solicitud desde un Android. En el otro, confirma que revisaste el detector. La respuesta aparecerá en ambos.")
        Button(onClick = { m.requestReview() }, enabled = !m.demo && m.running && !m.busy, modifier = Modifier.fillMaxWidth()) {
            Text("Solicitar revisión al otro teléfono")
        }
        if (m.demo) Text("La comunicación entre teléfonos requiere iniciar sesión local y enlazarlos.")
        else if (!m.running) Text("Inicia el monitoreo en ambos teléfonos para intercambiar solicitudes.")
        Text("Las solicitudes se guardan en memoria en el emisor y se borran al cerrar sesión. La confirmación no cambia el buzzer ni los LED.", style = MaterialTheme.typography.bodySmall)
    }
    m.reviews.forEach { review ->
        Section(if (review.sender == m.deviceId) "Solicitud enviada" else "Solicitud recibida") {
            Text("Desde ${review.device} · ${date(review.time)}")
            if (review.ack.isNotEmpty()) Text("Revisión confirmada por ${review.ack}", color = Teal)
            else if (review.sender == m.deviceId) Text("Esperando confirmación del otro teléfono…")
            else Button(onClick = { m.acknowledge(review) }, enabled = m.running && !m.busy) { Text("Confirmar revisión") }
        }
    }
}

@Composable
private fun Settings(m: DetectorModel) {
    var showCode by remember { mutableStateOf(false) }
    val editable = !m.running && !m.demo && m.isAdmin
    if (!m.isAdmin) Notice("Cuenta de operador: puedes monitorear, consultar el historial y gestionar revisiones. " +
        "La configuración, las claves y las órdenes al detector son del administrador.", Teal)
    Section("Función de este teléfono") {
        Text("Detén el monitoreo antes de cambiar la configuración.")
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
                modifier = Modifier.fillMaxWidth(), enabled = editable)
            OutlinedButton(onClick = { m.saveSettings() }, enabled = editable) { Text("Guardar dirección") }
        }
    }
    if (!m.demo && m.publisher) Section("Fuente del sensor") {
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
            style = MaterialTheme.typography.bodySmall)
        if (!m.simulatedSensor && m.isAdmin) {
            OutlinedTextField(value = m.deviceKey, onValueChange = { m.deviceKey = it.trim() },
                label = { Text("Clave del ESP32 (64 caracteres)") }, modifier = Modifier.fillMaxWidth(), enabled = editable)
            OutlinedButton(onClick = { m.generateDeviceKey() }, enabled = editable) { Text("Generar clave nueva") }
            Text("Copia esta misma clave en CLAVE_DISPOSITIVO del programa ESP32. Se guarda cifrada con Android Keystore.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
    if (!m.demo && m.isAdmin) Section("Código de enlace entre teléfonos") {
        Text(if (m.publisher) "Copia este código en Ajustes del receptor. Puedes mantener pulsado el texto para seleccionarlo."
            else "Pega los 64 caracteres del código mostrado por el teléfono emisor.")
        if (m.publisher) {
            TextButton(onClick = { showCode = !showCode }) { Text(if (showCode) "Ocultar código" else "Mostrar código de enlace") }
            if (showCode) SelectionContainer { Text(m.pairingCode, style = MaterialTheme.typography.bodyMedium) }
            OutlinedButton(onClick = { m.regenerateCode() }, enabled = editable) { Text("Generar otro código") }
        } else OutlinedTextField(value = m.pairingCode, onValueChange = { m.pairingCode = it },
            label = { Text("Código del emisor") }, modifier = Modifier.fillMaxWidth(), enabled = editable)
        Text("El código autoriza el enlace cifrado. Guárdalo entre tus dos teléfonos. Se conserva protegido en este dispositivo.", style = MaterialTheme.typography.bodySmall)
    }
    if (!m.demo) CloudSection(m)
    Section("Sesión local") {
        Text(m.user?.let { "${it.username} · ${it.role}" } ?: "Demostración sin iniciar sesión", fontWeight = FontWeight.SemiBold)
        if (!m.demo) Text("La primera cuenta creada en el teléfono es administradora; las siguientes son operadoras.",
            style = MaterialTheme.typography.bodySmall)
        Text("Las cuentas locales y el historial SQLite están en este teléfono. La nube (Firebase) es opcional y la conecta el administrador.")
        Text("Mantén abiertas ambas aplicaciones durante la evaluación. La prueba de alarma se envía desde Monitor o con T en el Monitor serie del Arduino.")
        OutlinedButton(onClick = { m.logout() }, enabled = !m.busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (m.demo) "Salir de la demostración" else "Cerrar sesión")
        }
    }
}

@Composable
private fun CloudSection(m: DetectorModel) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Section("Nube · Firebase") {
        when {
            !m.cloudConfigured -> Text("Firebase no está configurado: falta app/google-services.json. La app funciona solo en local.",
                style = MaterialTheme.typography.bodySmall)
            m.cloudEmail.isNotEmpty() -> {
                Text("Conectado como ${m.cloudEmail}", fontWeight = FontWeight.SemiBold)
                Text(if (m.publisher) "Este emisor sube cada lectura guardada a Cloud Firestore." else
                    "Este receptor puede consultar en Historial las lecturas que subió el emisor con la misma cuenta.",
                    style = MaterialTheme.typography.bodySmall)
                if (m.isAdmin) OutlinedButton(onClick = { m.cloudSignOut() }, enabled = !m.busy) { Text("Cerrar sesión de la nube") }
            }
            !m.isAdmin -> Text("La nube no está conectada. Solo el administrador puede conectarla.")
            else -> {
                Text("Usa la misma cuenta en los dos teléfonos para ver el historial del emisor desde cualquier lugar con Internet.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Correo") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth(), enabled = !m.busy)
                OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Contraseña de la nube") },
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !m.busy)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { m.cloudSignIn(email, password, false); password = "" }, enabled = !m.busy) { Text("Entrar") }
                    OutlinedButton(onClick = { m.cloudSignIn(email, password, true); password = "" }, enabled = !m.busy) { Text("Crear cuenta") }
                }
            }
        }
        if (m.cloudStatus.isNotEmpty() && m.cloudConfigured) Text(m.cloudStatus, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun Notice(message: String, color: Color) {
    Text(message, color = color, modifier = Modifier.fillMaxWidth()
        .background(color.copy(alpha = 0.08f), RoundedCornerShape(12.dp)).padding(14.dp))
}
private fun date(time: Long): String = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()).format(Date(time))
