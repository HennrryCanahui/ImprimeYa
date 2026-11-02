package com.example.imprimeya

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

    var ipAddress by remember {
        mutableStateOf(prefs.getString("printer_ip", "") ?: "")
    }
    var savedStatus by remember { mutableStateOf(false) }
    var isPrinting by remember { mutableStateOf(false) }
    var printResult by remember { mutableStateOf<String?>(null) }
    var isSuccess by remember { mutableStateOf(true) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Configuración de Impresora Térmica",
            style = MaterialTheme.typography.titleLarge
        )

        Spacer(modifier = Modifier.height(24.dp))

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

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                prefs.edit { putString("printer_ip", ipAddress.trim()) }
                savedStatus = true
                printResult = null
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Guardar IP")
        }

        if (savedStatus) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "IP guardada correctamente.",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                val ipToUse = ipAddress.trim().ifEmpty {
                    prefs.getString("printer_ip", "")?.trim() ?: ""
                }.ifEmpty { "192.168.1.100" }

                isPrinting = true
                printResult = null
                savedStatus = false

                scope.launch {
                    val result = sendDirectPrintJob(ipToUse)
                    isPrinting = false
                    if (result.isSuccess) {
                        isSuccess = true
                        printResult = "¡Ticket enviado exitosamente a $ipToUse:9100!"
                        Toast.makeText(context, "Impresión exitosa", Toast.LENGTH_SHORT).show()
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

private suspend fun sendDirectPrintJob(ipAddress: String): Result<Unit> {
    return withContext(Dispatchers.IO) {
        var socket: Socket? = null
        var outputStream: OutputStream? = null
        try {
            socket = Socket()
            // Conexión TCP con timeout de 5 segundos
            socket.connect(InetSocketAddress(ipAddress, 9100), 5000)
            socket.soTimeout = 5000

            outputStream = socket.getOutputStream()
            val ticketData = buildTestTicketBytes()
            outputStream.write(ticketData)
            outputStream.flush()

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

private fun buildTestTicketBytes(): ByteArray {
    val stream = ByteArrayOutputStream()
    // 1. Inicializar impresora ESC @ (0x1B 0x40)
    stream.write(byteArrayOf(0x1B, 0x40))

    // 2. Centrar texto ESC a 1 (0x1B 0x61 0x01)
    stream.write(byteArrayOf(0x1B, 0x61, 0x01))

    // 3. Negrita ESC E 1 (0x1B 0x45 0x01)
    stream.write(byteArrayOf(0x1B, 0x45, 0x01))
    stream.write("=== IMPRIME YA ===\n".toByteArray(Charsets.ISO_8859_1))

    // 4. Restaurar negrita ESC E 0 y alinear izquierda ESC a 0
    stream.write(byteArrayOf(0x1B, 0x45, 0x00))
    stream.write(byteArrayOf(0x1B, 0x61, 0x00))

    // 5. Contenido del ticket
    val content = """
        --------------------------------
        Prueba de Impresion Directa TCP
        Puerto: 9100
        Socket: Conectado
        --------------------------------
        
    """.trimIndent()
    stream.write(content.toByteArray(Charsets.ISO_8859_1))

    // 6. Avanzar papel (ESC d 3) y comando de corte (GS V 66 0)
    stream.write(byteArrayOf(0x1B, 0x64, 0x03))
    stream.write(byteArrayOf(0x1D, 0x56, 0x42, 0x00))

    return stream.toByteArray()
}