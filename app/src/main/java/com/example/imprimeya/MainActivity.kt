package com.example.imprimeya

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.example.imprimeya.ui.theme.ImprimeYaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ImprimeYaTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    PrinterConfigScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun PrinterConfigScreen(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("PrinterPrefs", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()

    // Estados de configuración de conexión
    var ipAddress by remember {
        mutableStateOf(prefs.getString("printer_ip", "") ?: "")
    }

    // Estados de personalización numérica del recibo
    var fontSize by remember {
        mutableStateOf(prefs.getInt("font_size", 1))
    }
    var topMargin by remember {
        mutableStateOf(prefs.getInt("top_margin", 0))
    }
    var bottomMargin by remember {
        mutableStateOf(prefs.getInt("bottom_margin", 2))
    }
    var footerMessage by remember {
        mutableStateOf(prefs.getString("footer_message", "¡Gracias por su compra!") ?: "")
    }

    // Estados de UI y retroalimentación
    var savedStatus by remember { mutableStateOf(false) }
    var isPrinting by remember { mutableStateOf(false) }
    var printResult by remember { mutableStateOf<String?>(null) }
    var isSuccess by remember { mutableStateOf(true) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Configuración de Impresión Térmica",
            style = MaterialTheme.typography.titleLarge
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Card 1: Conexión
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Conexión de la Impresora",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = ipAddress,
                    onValueChange = {
                        ipAddress = it
                        savedStatus = false
                        printResult = null
                    },
                    label = { Text("IP de la Impresora (Ej: 192.168.1.50)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Card 2: Personalización Numérica del Recibo
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Personalización de Formato y Márgenes",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Tamaño de letra numérico
                NumericSettingField(
                    label = "Tamaño de fuente (Numérico: 1 = Normal, 2 = Doble, 3 = Triple):",
                    value = fontSize,
                    minValue = 1,
                    maxValue = 8,
                    onValueChange = {
                        fontSize = it
                        savedStatus = false
                    }
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Margen superior numérico
                NumericSettingField(
                    label = "Margen superior (Líneas en blanco iniciales):",
                    value = topMargin,
                    minValue = 0,
                    maxValue = 20,
                    onValueChange = {
                        topMargin = it
                        savedStatus = false
                    }
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Margen inferior numérico
                NumericSettingField(
                    label = "Margen inferior (Líneas previas al corte):",
                    value = bottomMargin,
                    minValue = 1,
                    maxValue = 20,
                    onValueChange = {
                        bottomMargin = it
                        savedStatus = false
                    }
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Mensaje al pie de página
                OutlinedTextField(
                    value = footerMessage,
                    onValueChange = {
                        footerMessage = it
                        savedStatus = false
                    },
                    label = { Text("Mensaje al pie de página (Opcional)") },
                    placeholder = { Text("Ej: ¡Gracias por su preferencia!") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Botón Guardar
        Button(
            onClick = {
                prefs.edit {
                    putString("printer_ip", ipAddress.trim())
                    putInt("font_size", fontSize)
                    putInt("top_margin", topMargin)
                    putInt("bottom_margin", bottomMargin)
                    putString("footer_message", footerMessage.trim())
                }
                savedStatus = true
                printResult = null
                Toast.makeText(context, "Configuración guardada", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Guardar Configuración")
        }

        if (savedStatus) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "✓ Configuración guardada correctamente.",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Botón Ticket de Prueba
        Button(
            onClick = {
                val ipToUse = ipAddress.trim().ifEmpty {
                    prefs.getString("printer_ip", "")?.trim() ?: ""
                }.ifEmpty { "192.168.1.100" }

                val currentConfig = ReceiptConfig(
                    fontSize = fontSize,
                    topMargin = topMargin,
                    bottomMargin = bottomMargin,
                    footerMessage = footerMessage.trim()
                )

                isPrinting = true
                printResult = null
                savedStatus = false

                scope.launch {
                    val result = sendDirectPrintJob(ipToUse, currentConfig)
                    isPrinting = false
                    if (result.isSuccess) {
                        isSuccess = true
                        printResult = "¡Ticket enviado exitosamente a $ipToUse:9100!"
                        Toast.makeText(context, "Impresión de prueba exitosa", Toast.LENGTH_SHORT).show()
                    } else {
                        isSuccess = false
                        val errorMsg = result.exceptionOrNull()?.localizedMessage ?: "Error desconocido"
                        printResult = "Error al imprimir en $ipToUse: $errorMsg"
                        Toast.makeText(context, "Error de impresión: $errorMsg", Toast.LENGTH_LONG).show()
                    }
                }
            },
            enabled = !isPrinting,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondary
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isPrinting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onSecondary,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Enviando a Impresora...")
            } else {
                Text("Imprimir Ticket de Prueba")
            }
        }

        printResult?.let { resultText ->
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = resultText,
                color = if (isSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
fun NumericSettingField(
    label: String,
    value: Int,
    minValue: Int,
    maxValue: Int,
    onValueChange: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedIconButton(
                onClick = { if (value > minValue) onValueChange(value - 1) },
                enabled = value > minValue
            ) {
                Text("-", style = MaterialTheme.typography.titleLarge)
            }
            OutlinedTextField(
                value = value.toString(),
                onValueChange = { input ->
                    val cleaned = input.filter { it.isDigit() }
                    if (cleaned.isEmpty()) {
                        onValueChange(minValue)
                    } else {
                        cleaned.toIntOrNull()?.let { num ->
                            onValueChange(num.coerceIn(minValue, maxValue))
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.width(80.dp)
            )
            OutlinedIconButton(
                onClick = { if (value < maxValue) onValueChange(value + 1) },
                enabled = value < maxValue
            ) {
                Text("+", style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

private suspend fun sendDirectPrintJob(ipAddress: String, config: ReceiptConfig): Result<Unit> {
    return withContext(Dispatchers.IO) {
        var socket: Socket? = null
        var outputStream: OutputStream? = null
        try {
            socket = Socket()
            socket.connect(InetSocketAddress(ipAddress, 9100), 5000)
            socket.soTimeout = 5000

            outputStream = socket.getOutputStream()
            val ticketData = buildTestTicketBytes(config)
            outputStream.write(ticketData)
            outputStream.flush()
            Thread.sleep(100)

            Result.success(Unit)
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        } finally {
            try {
                outputStream?.close()
            } catch (_: Exception) {}
            try {
                socket?.close()
            } catch (_: Exception) {}
        }
    }
}

private fun buildTestTicketBytes(config: ReceiptConfig): ByteArray {
    val stream = ByteArrayOutputStream()
    // 1. Inicializar impresora ESC @ (0x1B 0x40)
    stream.write(byteArrayOf(0x1B, 0x40))

    // 2. Margen superior numérico (ESC d n)
    val topLines = config.topMargin.coerceIn(0, 20)
    if (topLines > 0) {
        stream.write(byteArrayOf(0x1B, 0x64, topLines.toByte()))
    }

    // 3. Encabezado centrado y en negrita
    stream.write(byteArrayOf(0x1B, 0x61, 0x01)) // Centrar
    stream.write(byteArrayOf(0x1B, 0x45, 0x01)) // Negrita
    stream.write("=== IMPRIME YA ===\n".toByteArray(Charsets.ISO_8859_1))
    stream.write(byteArrayOf(0x1B, 0x45, 0x00)) // Desactivar negrita
    stream.write(byteArrayOf(0x1B, 0x61, 0x00)) // Alinear izquierda

    // 4. Aplicar tamaño numérico de fuente al cuerpo (GS ! n)
    val s = (config.fontSize - 1).coerceIn(0, 7)
    val n = ((s shl 4) or s).toByte()
    stream.write(byteArrayOf(0x1D, 0x21, n))

    // 5. Contenido del ticket de prueba
    val content = """
        --------------------------------
        Prueba de Impresion Directa TCP
        Puerto: 9100
        Tamaño Fuente: ${config.fontSize}
        Margen Sup: ${config.topMargin} lineas
        Margen Inf: ${config.bottomMargin} lineas
        --------------------------------
        
    """.trimIndent()
    stream.write(content.toByteArray(Charsets.ISO_8859_1))

    // Restaurar fuente normal (GS ! 0)
    stream.write(byteArrayOf(0x1D, 0x21, 0x00))

    // 6. Mensaje al pie de página (si existe)
    if (config.footerMessage.isNotBlank()) {
        stream.write(byteArrayOf(0x1B, 0x61, 0x01)) // Centrar
        stream.write("${config.footerMessage}\n".toByteArray(Charsets.ISO_8859_1))
        stream.write(byteArrayOf(0x1B, 0x61, 0x00)) // Izquierda
    }

    // 7. Margen inferior numérico / avance previo al corte (ESC d n)
    val bottomLines = config.bottomMargin.coerceIn(1, 20)
    stream.write(byteArrayOf(0x1B, 0x64, bottomLines.toByte()))

    // 8. Comando de corte (GS V 66 0)
    stream.write(byteArrayOf(0x1D, 0x56, 0x42, 0x00))

    return stream.toByteArray()
}