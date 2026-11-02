package com.example.imprimeya

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.File

object EscPos {

    fun pdfToEscPos(file: File, widthDots: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x1B, 0x40)) // ESC @ : inicializar

        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(pfd)
        try {
            for (i in 0 until renderer.pageCount) {
                val page = renderer.openPage(i)
                try {
                    val h = (page.height.toFloat() * widthDots / page.width).toInt()
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

        // Avanzar papel y cortar (si tu impresora no tiene cortador, se ignora)
        out.write(byteArrayOf(0x0A, 0x0A, 0x0A, 0x1D, 0x56, 0x42, 0x00))
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

        // Recortar filas en blanco al final (evita gastar papel)
        var lastRow = h - 1
        while (lastRow > 0 && (0 until w).none { isDark(px[lastRow * w + it]) }) lastRow--
        val rows = lastRow + 1

        var y = 0
        while (y < rows) {
            val chunk = minOf(255, rows - y)
            // GS v 0 m xL xH yL yH
            out.write(byteArrayOf(
                0x1D, 0x76, 0x30, 0x00,
                (bytesPerRow and 0xFF).toByte(), (bytesPerRow shr 8).toByte(),
                (chunk and 0xFF).toByte(), (chunk shr 8).toByte()
            ))
            for (row in y until y + chunk) {
                for (bx in 0 until bytesPerRow) {
                    var b = 0
                    for (bit in 0..7) {
                        val x = bx * 8 + bit
                        if (x < w && isDark(px[row * w + x])) b = b or (0x80 shr bit)
                    }
                    out.write(b)
                }
            }
            y += chunk
        }
    }
}