package com.example.imprimeya

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.imprimeya.ui.theme.ImprimeYaTheme
import androidx.core.content.edit

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

    var ipAddress by remember {
        mutableStateOf(prefs.getString("printer_ip", "") ?: "")
    }
    var savedStatus by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "Configuración de Impresora Térmica", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = ipAddress,
            onValueChange = { ipAddress = it },
            label = { Text("IP de la Impresora (Ej: 192.168.1.50)") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = {
                prefs.edit { putString("printer_ip", ipAddress) }
                savedStatus = true
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Guardar IP")
        }
        if (savedStatus) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "IP guardada correctamente.", color = MaterialTheme.colorScheme.primary)
        }
    }
}