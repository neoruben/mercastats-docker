package io.github.neoruben.mercadonastats.server.pdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper

/**
 * Extrae texto seleccionable de un PDF en JVM usando Apache PDFBox 3.x
 * con `sortByPosition = true` (mismo comportamiento que la app Android).
 */
object JvmPdfTextExtractor {

    fun extractText(pdfBytes: ByteArray): String {
        Loader.loadPDF(pdfBytes).use { document ->
            val stripper = PDFTextStripper().apply {
                sortByPosition = true
            }
            return stripper.getText(document)
        }
    }
}
