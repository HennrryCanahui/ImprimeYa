package com.example.imprimeya

import android.content.Context
import android.os.Handler
import android.os.Looper
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
import android.os.ParcelFileDescriptor
import kotlin.concurrent.thread

class ThermalPrintService : PrintService() {

    private val mainHandler = Handler(Looper.getMainLooper())

    // 80 mm ≈ 576 puntos a 203 dpi | 58 mm ≈ 384 puntos
    private val paperWidthDots = 576

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession =
        ThermalDiscoverySession()

    override fun onRequestCancelPrintJob(printJob: PrintJob) {
        printJob.cancel()
    }

    override fun onPrintJobQueued(printJob: PrintJob) {
        // Esto corre en el hilo principal: aquí sí se puede llamar start()
        printJob.start()

        val pfd = printJob.document.data
        if (pfd == null) {
            printJob.fail("No se pudo leer el documento")
            return
        }

        // Copiamos el PDF a un archivo temporal (PdfRenderer necesita un fd "seekable")
        val tmp = File(cacheDir, "job_${System.currentTimeMillis()}.pdf")

        thread {
            try {
                ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                    FileOutputStream(tmp).use { input.copyTo(it) }
                }

                val prefs = getSharedPreferences("PrinterPrefs", Context.MODE_PRIVATE)
                val ip = prefs.getString("printer_ip", "") ?: ""
                if (ip.isBlank()) throw IllegalStateException("Configura la IP de la impresora")

                val data = EscPos.pdfToEscPos(tmp, paperWidthDots)

                Socket().use { socket ->
                    socket.connect(InetSocketAddress(ip, 9100), 5000)
                    socket.getOutputStream().apply {
                        write(data)
                        flush()
                    }
                }

                mainHandler.post { printJob.complete() }
            } catch (e: Exception) {
                e.printStackTrace()
                mainHandler.post { printJob.fail(e.localizedMessage ?: "Error de impresión") }
            } finally {
                tmp.delete()
            }
        }
    }

    inner class ThermalDiscoverySession : PrinterDiscoverySession() {

        override fun onStartPrinterDiscovery(priorityList: MutableList<PrinterId>) {
            val id = generatePrinterId("thermal_printer_1")

            // Rollo de 80 mm de ancho (3150 milésimas de pulgada), largo máx. ~ 11"
            val media = MediaSize("ROLL_80", "Rollo 80mm", 3150, 11000)

            val caps = PrinterCapabilitiesInfo.Builder(id)
                .addMediaSize(media, true)
                .addResolution(PrintAttributes.Resolution("r203", "203 dpi", 203, 203), true)
                .setColorModes(
                    PrintAttributes.COLOR_MODE_MONOCHROME,
                    PrintAttributes.COLOR_MODE_MONOCHROME
                )
                .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                .build()

            val info = PrinterInfo.Builder(id, "Impresora Térmica", PrinterInfo.STATUS_IDLE)
                .setCapabilities(caps)
                .build()

            addPrinters(listOf(info))
        }

        override fun onStopPrinterDiscovery() {}
        override fun onValidatePrinters(printerIds: MutableList<PrinterId>) {}
        override fun onStartPrinterStateTracking(printerId: PrinterId) {}
        override fun onStopPrinterStateTracking(printerId: PrinterId) {}
        override fun onDestroy() {}
    }
}