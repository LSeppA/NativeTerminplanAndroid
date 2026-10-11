package at.goinon.badvigaun.terminplan

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** Coordinates are pixels in the oriented OCR image, independent of page size. */
internal data class OcrFragment(
    val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int
) {
    val centerY: Float get() = (top + bottom) / 2f
    val height: Int get() = bottom - top
}

/** Joins separate time/title columns without merging adjacent lines on densely printed pages. */
internal fun assembleOcrRows(fragments: List<OcrFragment>): List<String> {
    val rows = mutableListOf<MutableList<OcrFragment>>()
    fragments.filter { it.text.isNotBlank() && it.height > 0 && it.right > it.left }
        .sortedWith(compareBy<OcrFragment> { it.centerY }.thenBy { it.left })
        .forEach { fragment ->
            val row = rows.filter { existing ->
                val anchor = existing.first()
                val tolerance = min(anchor.height, fragment.height) * 0.45f
                abs(anchor.centerY - fragment.centerY) <= tolerance &&
                    existing.none { it.left < fragment.right && fragment.left < it.right }
            }.minByOrNull { abs(it.first().centerY - fragment.centerY) }
            if (row == null) rows.add(mutableListOf(fragment)) else row.add(fragment)
        }
    return rows.sortedBy { row -> row.map { it.centerY }.average() }
        .map { row -> row.sortedBy { it.left }.joinToString(" ") { it.text.trim() } }
}

/** Preserve aspect ratio and never enlarge an image that contains fewer actual pixels. */
internal fun ocrImageSize(width: Int, height: Int, maxEdge: Int = 3508): Pair<Int, Int> {
    require(width > 0 && height > 0 && maxEdge > 0)
    val ratio = min(1.0, maxEdge.toDouble() / maxOf(width, height))
    return (width * ratio).roundToInt().coerceAtLeast(1) to
        (height * ratio).roundToInt().coerceAtLeast(1)
}

/** Decode at or above the target size, then downscale precisely instead of losing half the detail. */
internal fun ocrDecodeSampleSize(width: Int, height: Int, maxEdge: Int = 3508): Int {
    require(width > 0 && height > 0 && maxEdge > 0)
    var sample = 1
    while (maxOf(width, height).toLong() / (sample.toLong() * 2) >= maxEdge) sample *= 2
    // Cap the intermediate bitmap on Android 8, before precise resizing allocates a second bitmap.
    while ((width.toLong() / sample) * (height.toLong() / sample) > 16_000_000L) sample *= 2
    return sample
}