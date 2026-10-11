package at.goinon.badvigaun.terminplan

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrLayoutTest {
    @Test fun keepsDenseA4RowsSeparateAndJoinsTheirColumns() {
        val fragments = listOf(
            OcrFragment("Therapie B", 200, 130, 450, 150),
            OcrFragment("09:00", 10, 100, 80, 120),
            OcrFragment("10:00", 10, 130, 80, 150),
            OcrFragment("Therapie A", 200, 103, 450, 123)
        )
        assertEquals(listOf("09:00 Therapie A", "10:00 Therapie B"), assembleOcrRows(fragments))
    }

    @Test fun overlappingTextIsNotCollapsedIntoOneLine() {
        assertEquals(listOf("Erste Zeile", "Zweite Zeile"), assembleOcrRows(listOf(
            OcrFragment("Erste Zeile", 10, 100, 200, 140),
            OcrFragment("Zweite Zeile", 10, 115, 200, 155)
        )))
    }

    @Test fun tallHeadingDoesNotAbsorbSmallPrint() {
        assertEquals(listOf("Datum", "09:00"), assembleOcrRows(listOf(
            OcrFragment("Datum", 10, 100, 200, 160),
            OcrFragment("09:00", 250, 140, 310, 160)
        )))
    }

    @Test fun cameraImagePreservesMoreDetailThanPowerOfTwoDownsampling() {
        assertEquals(3508 to 2631, ocrImageSize(4000, 3000))
        assertEquals(1, ocrDecodeSampleSize(4000, 3000))
        assertEquals(2, ocrDecodeSampleSize(8000, 6000))
    }

    @Test fun portraitAndSmallImagesPreserveAspectRatio() {
        assertEquals(2631 to 3508, ocrImageSize(3000, 4000))
        assertEquals(1200 to 1600, ocrImageSize(1200, 1600))
    }
    @Test fun capsIntermediateBitmapForLargeSquarePhotos() {
        assertEquals(2, ocrDecodeSampleSize(7000, 7000))
        assertEquals(4, ocrDecodeSampleSize(12000, 9000))
    }
}