package at.goinon.badvigaun.terminplan

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException


/** Reads a photographed schedule, corrects its perspective and converts it into schedule items. */
class PlanScanner(context: Context) : AutoCloseable {
    private val resolver = context.applicationContext.contentResolver
    private val recognizer: TextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun scan(uri: Uri): List<ScheduleItem> = withContext(Dispatchers.IO) {
        val source = readBitmap(uri)
        val corrected = runCatching { DocumentPerspective.correct(source) }.getOrDefault(source)
        val prepared = runCatching { DocumentPerspective.prepareForTextRecognition(corrected) }.getOrDefault(corrected)
        val releaseBitmaps = {
            if (prepared !== corrected) prepared.recycle()
            if (corrected !== source) corrected.recycle()
            source.recycle()
        }
        val image = try {
            InputImage.fromBitmap(prepared, 0)
        } catch (error: Throwable) {
            releaseBitmaps()
            throw error
        }
        val lines = recognizeLines(image, releaseBitmaps)
        ScheduleParser.parse(lines)
    }

    private fun readBitmap(uri: Uri): Bitmap {
        if (Build.VERSION.SDK_INT >= 28) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val (width, height) = ocrImageSize(info.size.width, info.size.height, MAX_IMAGE_EDGE)
                decoder.setTargetSize(width, height)
            }
        }

        // Android 8: read only the dimensions first, then decode a sampled bitmap. Decoding the
        // full camera image before scaling can otherwise exceed the heap on older devices.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Das Bild konnte nicht geöffnet werden." }
            BitmapFactory.decodeStream(input, null, bounds)
        }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Die Bildgröße konnte nicht gelesen werden." }

        val options = BitmapFactory.Options().apply {
            inSampleSize = ocrDecodeSampleSize(bounds.outWidth, bounds.outHeight, MAX_IMAGE_EDGE)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Das Bild konnte nicht geöffnet werden." }
            BitmapFactory.decodeStream(input, null, options)
        } ?: error("Das Bild konnte nicht gelesen werden.")

        val orientation = resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Die Bildausrichtung konnte nicht gelesen werden." }
            ExifInterface(input).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        }
        val (width, height) = ocrImageSize(decoded.width, decoded.height, MAX_IMAGE_EDGE)
        val resized = if (width == decoded.width && height == decoded.height) decoded else {
            decoded.scale(width, height).also { decoded.recycle() }
        }
        return orient(resized, orientation)
    }

    @SuppressLint("UseKtx") // Core KTX has no createBitmap overload that accepts a Matrix.
    private fun orient(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
            if (it !== bitmap) bitmap.recycle()
        }
    }

    private suspend fun recognizeLines(image: InputImage, onComplete: () -> Unit): List<String> =
        suspendCancellableCoroutine { continuation ->
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    if (!continuation.isActive) return@addOnSuccessListener
                    val fragments = result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                        line.boundingBox?.let { box ->
                            OcrFragment(line.text, box.left, box.top, box.right, box.bottom)
                        }
                    }
                    continuation.resume(assembleOcrRows(fragments))
                }
                .addOnFailureListener { error ->
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
                .addOnCompleteListener { onComplete() }
        }

    override fun close() = recognizer.close()

    private companion object {
        const val MAX_IMAGE_EDGE = 3508
    }
}

internal fun calculateImageSampleSize(width: Int, height: Int, maxEdge: Int = 2600): Int {
    require(width > 0 && height > 0 && maxEdge > 0)
    var sampleSize = 1
    val largest = maxOf(width, height)
    while (largest / sampleSize > maxEdge) sampleSize *= 2
    return sampleSize
}
