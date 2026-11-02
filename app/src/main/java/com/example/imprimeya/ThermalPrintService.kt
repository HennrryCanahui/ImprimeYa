package com.example.imprimeya

import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import android.print.PrintAttributes.MediaSize
import android.print.PrinterCapabilitiesInfo
import android.print.PrinterId
import android.print.PrinterInfo
import android.printservice.PrintJob
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

class ThermalPrintService : PrintService() {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession {
        return ThermalDiscoverySession()
    }

    override fun onRequestCancelPrintJob(printJob: PrintJob) {
        if (printJob.isQueued || printJob.isStarted) {
            printJob.cancel()
        }
    }

    override fun onPrintJobQueued(printJob: PrintJob) {
        // 1. Iniciar inmediatamente el trabajo de impresión en el spooler del sistema
        if (printJob.isQueued) {
            printJob.start()
        }

        val pfd = printJob.document.data
        if (pfd == null) {
            printJob.fail("No se pudo obtener el documento de impresión.")
            return
        }

        // 2. Hilo de fondo autónomo para procesamiento y envío en red TCP sin congelar la interfaz
        thread(start = true, isDaemon = true, name = "ThermalPrintThread") {
            val tmpFile = File(cacheDir, "job_${System.currentTimeMillis()}.tmp")
            try {
                // Copiar el flujo de datos del archivo entrante
                ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                    FileOutputStream(tmpFile).use { output ->
                        input.copyTo(output)
                    }
                }

                val prefs = getSharedPreferences("PrinterPrefs", MODE_PRIVATE)
                val ip = prefs.getString("printer_ip", "")?.trim()?.ifEmpty { "192.168.1.100" }
                    ?: "192.168.1.100"

                val isPdf = isPdfFile(tmpFile)
                val printBytes: ByteArray = if (isPdf) {
                    // Convertir PDF a comandos raster ESC/POS (GS v 0) a 203 DPI (576 dots para 80mm)
                    EscPos.pdfToEscPos(tmpFile, widthDots = 576)
                } else {
                    // Bytes directos si es texto o ESC/POS nativo
                    tmpFile.readBytes()
                }

                // 3. Conexión Socket TCP al puerto 9100 con timeouts estrictos
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(ip, 9100), 5000)
                    socket.soTimeout = 5000
                    socket.getOutputStream().use { outputStream ->
                        outputStream.write(printBytes)
                        outputStream.flush()
                    }
                }

                // 4. Reportar éxito de inmediato al spooler en el hilo principal
                mainHandler.post {
                    if (printJob.isStarted) {
                        printJob.complete()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                val errorMsg = e.localizedMessage ?: "Error al procesar el trabajo de impresión"
                mainHandler.post {
                    if (printJob.isStarted || printJob.isQueued) {
                        printJob.fail(errorMsg)
                    }
                }
            } finally {
                tmpFile.delete()
            }
        }
    }

    private fun isPdfFile(file: File): Boolean {
        if (!file.exists() || file.length() < 4) return false
        return try {
            val header = ByteArray(4)
            file.inputStream().use { it.read(header) }
            header[0] == 0x25.toByte() && header[1] == 0x50.toByte() &&
                    header[2] == 0x44.toByte() && header[3] == 0x46.toByte()
        } catch (_: Exception) {
            false
        }
    }

    inner class ThermalDiscoverySession : PrinterDiscoverySession() {

        private fun getThermalPrinterInfo(): PrinterInfo {
            val printerId = generatePrinterId("thermal_printer_tcp_9100")
            val mediaSize = MediaSize("ROLL_80", "Rollo 80mm", 3150, 11000)

            val capabilities = PrinterCapabilitiesInfo.Builder(printerId)
                .addMediaSize(mediaSize, true)
                .addResolution(PrintAttributes.Resolution("r203", "203 dpi", 203, 203), true)
                .setColorModes(
                    PrintAttributes.COLOR_MODE_MONOCHROME,
                    PrintAttributes.COLOR_MODE_MONOCHROME
                )
                .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                .build()

            return PrinterInfo.Builder(printerId, "Impresora Térmica ImprimeYa", PrinterInfo.STATUS_IDLE)
                .setCapabilities(capabilities)
                .build()
        }

        override fun onStartPrinterDiscovery(priorityList: MutableList<PrinterId>) {
            val printer = getThermalPrinterInfo()
            addPrinters(listOf(printer))
        }

        override fun onValidatePrinters(printerIds: MutableList<PrinterId>) {
            val printer = getThermalPrinterInfo()
            if (printerIds.contains(printer.id)) {
                addPrinters(listOf(printer))
            }
        }

        override fun onStartPrinterStateTracking(printerId: PrinterId) {
            val printer = getThermalPrinterInfo()
            if (printerId == printer.id) {
                addPrinters(listOf(printer))
            }
        }

        override fun onStopPrinterStateTracking(printerId: PrinterId) {}
        override fun onStopPrinterDiscovery() {}
        override fun onDestroy() {}
    }
}