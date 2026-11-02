package com.example.imprimeya

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.File

data class ReceiptConfig(
    val fontSize: Int = 1,         // Tamaño de fuente numérico (1: Normal, 2: Doble, 3: Triple, etc.)
    val topMargin: Int = 0,        // Líneas de margen superior numérico (0, 1, 2, 3...)
    val bottomMargin: Int = 2,     // Líneas de espacio de corte numérico (1, 2, 3...)
    val footerMessage: String = "" // Texto opcional al pie de página
)

object EscPos {

    /**
     * Convierte un archivo PDF a comandos de imagen raster ESC/POS (GS v 0).
     * Recorta automáticamente los espacios en blanco superiores del PDF para no desperdiciar papel.
     * @param file Archivo PDF temporal a renderizar.
     * @param widthDots Ancho imprimible en puntos (576 puntos para rollos de 80mm).
     * @param config Ajustes numéricos del recibo (márgenes, tamaño de fuente, mensaje al pie).
     */
    fun pdfToEscPos(
        file: File,
        widthDots: Int = 576,
        config: ReceiptConfig = ReceiptConfig()
    ): ByteArray {
        val out = ByteArrayOutputStream()
        // ESC @ : Inicializar impresora
        out.write(byteArrayOf(0x1B, 0x40))

        // 1. Aplicar margen superior numérico configurado (ESC d n)
        val topLines = config.topMargin.coerceIn(0, 20)
        if (topLines > 0) {
            out.write(byteArrayOf(0x1B, 0x64, topLines.toByte()))
        }

        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = try {
            PdfRenderer(pfd)
        } catch (e: Exception) {
            pfd.close()
            throw e
        }

        try {
            for (i in 0 until renderer.pageCount) {
                val page = renderer.openPage(i)
                try {
                    val rawH = (page.height.toFloat() * widthDots / page.width).toInt()
                    val h = maxOf(1, rawH)
                    val bmp = Bitmap.createBitmap(widthDots, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    writeRaster(out, bmp)
                    bmp.recycle()
                } finally {
                    page.close()
                }
            }
        } finally {
            renderer.close()
            pfd.close()
        }

        // 2. Mensaje personalizado al pie de página (si existe)
        if (config.footerMessage.isNotBlank()) {
            // Centrar texto ESC a 1 (0x1B 0x61 0x01)
            out.write(byteArrayOf(0x1B, 0x61, 0x01))

            // Aplicar tamaño de letra numérico con GS ! n (0x1D 0x21 n)
            val s = (config.fontSize - 1).coerceIn(0, 7)
            val n = ((s shl 4) or s).toByte()
            out.write(byteArrayOf(0x1D, 0x21, n))

            out.write("\n${config.footerMessage}\n".toByteArray(Charsets.ISO_8859_1))

            // Restaurar fuente normal (GS ! 0) y alineación izquierda (ESC a 0)
            out.write(byteArrayOf(0x1D, 0x21, 0x00))
            out.write(byteArrayOf(0x1B, 0x61, 0x00))
        }

        // 3. Margen inferior numérico / avance previo al corte (ESC d n)
        val bottomLines = config.bottomMargin.coerceIn(1, 20)
        out.write(byteArrayOf(0x1B, 0x64, bottomLines.toByte()))

        // 4. Comando de corte parcial/total (GS V 66 0)
        out.write(byteArrayOf(0x1D, 0x56, 0x42, 0x00))

        return out.toByteArray()
    }

    private fun writeRaster(out: ByteArrayOutputStream, bmp: Bitmap) {
        val w = bmp.width
        val h = bmp.height
        val bytesPerRow = (w + 7) / 8
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)

        fun isDark(c: Int): Boolean {
            val lum = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
            return lum < 128
        }

        // Recortar filas en blanco al INICIO (elimina los ~5cm de papel desperdiciado antes del contenido)
        var firstRow = 0
        while (firstRow < h && (0 until w).none { isDark(px[firstRow * w + it]) }) {
            firstRow++
        }

        // Si toda la página está en blanco, no imprimir nada
        if (firstRow >= h) return

        // Recortar filas en blanco al FINAL
        var lastRow = h - 1
        while (lastRow > firstRow && (0 until w).none { isDark(px[lastRow * w + it]) }) {
            lastRow--
        }

        var y = firstRow
        while (y <= lastRow) {
            val chunk = minOf(255, lastRow - y + 1)
            out.write(
                byteArrayOf(
                    0x1D, 0x76, 0x30, 0x00,
                    (bytesPerRow and 0xFF).toByte(), (bytesPerRow shr 8).toByte(),
                    (chunk and 0xFF).toByte(), (chunk shr 8).toByte()
                )
            )

            val block = ByteArray(bytesPerRow * chunk)
            var i = 0
            for (row in y until (y + chunk)) {
                val base = row * w
                for (bx in 0 until bytesPerRow) {
                    var b = 0
                    val x0 = bx * 8
                    for (bit in 0..7) {
                        val x = x0 + bit
                        if (x < w && isDark(px[base + x])) b = b or (0x80 shr bit)
                    }
                    block[i++] = b.toByte()
                }
            }
            out.write(block, 0, block.size)
            y += chunk
        }
    }
}