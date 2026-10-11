package at.goinon.badvigaun.terminplan

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageSamplingTest {
    @Test fun leavesImagesAtOrBelowLimitUnchanged() {
        assertEquals(1, calculateImageSampleSize(2600, 1800))
    }

    @Test fun selectsPowerOfTwoSampleThatFitsLargeCameraImage() {
        assertEquals(2, calculateImageSampleSize(4000, 3000))
        assertEquals(8, calculateImageSampleSize(12000, 9000))
    }
}
