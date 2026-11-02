package com.example.imprimeya

import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.print.PrintAttributes
import android.print.PrintAttributes.MediaSize
import android.print.PrinterCapabilitiesInfo
import android.print.PrinterId
import android.print.PrinterInfo
import android.printservice.PrintJob
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ThermalPrintService : PrintService() {

    companion object {
        private const val TAG = "ThermalPrint"
        private const val PRINTER_LOCAL_ID = "thermal_printer_tcp_9100"
        private const val PORT = 9100
        private const val PAPER_DOTS = 576 // 80 mm (Usar 384 para rollos de 58 mm)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var executor: ExecutorService = Executors.newSingleThreadExecutor()

    @Synchronized
    private fun getExecutor(): ExecutorService {
        if (executor.isShutdown || executor.isTerminated) {
            executor = Executors.newSingleThreadExecutor()
        }
        return executor
    }

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession =
        ThermalDiscoverySession()

    override fun onRequestCancelPrintJob(printJob: PrintJob) {
        if (printJob.isQueued || printJob.isStarted) {
            printJob.cancel()
        }
    }

    override fun onPrintJobQueued(printJob: PrintJob) {
        val pfd = printJob.document.data
        if (pfd == null) {
            printJob.fail("No se pudo obtener el documento de impresión.")
            return
        }

        // Iniciar inmediatamente el trabajo en el spooler del sistema
        if (printJob.isQueued) {
            printJob.start()
        }

        // Adquirir un Partial WakeLock para mantener el CPU activo durante la impresión
        val powerManager = getSystemService(POWER_SERVICE) as? PowerManager
        val wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ImprimeYa:print")
        wakeLock?.acquire(60_000L)

        getExecutor().execute {
            val tmpFile = File(cacheDir, "job_${System.nanoTime()}.tmp")
            var targetIp = "192.168.1.100"
            try {
                // Copiar flujo de datos a archivo temporal
                ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                    FileOutputStream(tmpFile).use { output ->
                        input.copyTo(output)
                    }
                }

                val prefs = getSharedPreferences("PrinterPrefs", MODE_PRIVATE)
                targetIp = prefs.getString("printer_ip", "")?.trim().orEmpty()
                    .ifEmpty { "192.168.1.100" }

                val bytes = if (isPdfFile(tmpFile)) {
                    EscPos.pdfToEscPos(tmpFile, PAPER_DOTS)
                } else {
                    tmpFile.readBytes()
                }

                Log.d(TAG, "Enviando ${bytes.size} bytes a $targetIp:$PORT")
                sendToPrinter(targetIp, bytes)
                Log.d(TAG, "Impresión completada exitosamente en $targetIp")

                mainHandler.post {
                    if (printJob.isStarted) {
                        printJob.complete()
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Error al imprimir en $targetIp", t)
                val msg = t.localizedMessage ?: "Error de comunicación con la impresora"
                mainHandler.post {
                    if (printJob.isStarted || printJob.isQueued) {
                        printJob.fail(msg)
                    }
                }
            } finally {
                tmpFile.delete()
                if (wakeLock?.isHeld == true) {
                    try {
                        wakeLock.release()
                    } catch (_: Exception) {}
                }
            }
        }
    }

    override fun onDestroy() {
        try {
            executor.shutdownNow()
        } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun connectWithRetry(ip: String): Socket {
        var last: Exception? = null
        repeat(3) { attempt ->
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(ip, PORT), 4000)
                return socket
            } catch (e: Exception) {
                runCatching { socket.close() }
                last = e
                Log.w(TAG, "Intento de conexión ${attempt + 1} fallido a $ip: ${e.message}")
                try {
                    Thread.sleep(500)
                } catch (_: InterruptedException) {}
            }
        }
        throw last ?: IOException("No se pudo conectar a la impresora en $ip:$PORT")
    }

    private fun sendToPrinter(ip: String, data: ByteArray) {
        connectWithRetry(ip).use { socket ->
            socket.tcpNoDelay = true
            socket.soTimeout = 10_000
            val out = socket.getOutputStream()
            out.write(data)
            out.flush()
            // Pausa de 100ms para permitir drenado del búfer de la interfaz de red de la impresora
            Thread.sleep(100)
        }
    }

    private fun isPdfFile(file: File): Boolean {
        if (!file.exists() || file.length() < 4) return false
        return try {
            val h = ByteArray(4)
            file.inputStream().use { it.read(h) }
            h[0] == 0x25.toByte() && h[1] == 0x50.toByte() &&
                    h[2] == 0x44.toByte() && h[3] == 0x46.toByte()
        } catch (_: Exception) {
            false
        }
    }

    inner class ThermalDiscoverySession : PrinterDiscoverySession() {

        private fun buildPrinterInfo(): PrinterInfo {
            val id = generatePrinterId(PRINTER_LOCAL_ID)
            val ip = getSharedPreferences("PrinterPrefs", MODE_PRIVATE)
                .getString("printer_ip", "")?.trim().orEmpty()

            val caps = PrinterCapabilitiesInfo.Builder(id)
                .addMediaSize(MediaSize("ROLL_80", "Rollo 80mm", 3150, 11000), true)
                .addResolution(PrintAttributes.Resolution("r203", "203 dpi", 203, 203), true)
                .setColorModes(
                    PrintAttributes.COLOR_MODE_MONOCHROME,
                    PrintAttributes.COLOR_MODE_MONOCHROME
                )
                .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                .build()

            val descriptionText = if (ip.isEmpty()) "IP no configurada" else "IP: $ip (TCP 9100)"

            return PrinterInfo.Builder(id, "Impresora Térmica ImprimeYa", PrinterInfo.STATUS_IDLE)
                .setDescription(descriptionText)
                .setCapabilities(caps)
                .build()
        }

        override fun onStartPrinterDiscovery(priorityList: MutableList<PrinterId>) {
            addPrinters(listOf(buildPrinterInfo()))
        }

        override fun onValidatePrinters(printerIds: MutableList<PrinterId>) {
            addPrinters(listOf(buildPrinterInfo()))
        }

        override fun onStartPrinterStateTracking(printerId: PrinterId) {
            addPrinters(listOf(buildPrinterInfo()))
        }

        override fun onStopPrinterStateTracking(printerId: PrinterId) {}
        override fun onStopPrinterDiscovery() {}
        override fun onDestroy() {}
    }
}