package at.goinon.badvigaun.terminplan

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Core
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.geometry.Geometry
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/** Detects the printed sheet and corrects its perspective; returns the source if no reliable sheet is found. */
object DocumentPerspective {
    fun correct(source: Bitmap): Bitmap {
        if (!OpenCVLoader.initLocal()) return source
        val (detectWidth, detectHeight) = ocrImageSize(source.width, source.height, 1200)
        val detection = if (detectWidth == source.width && detectHeight == source.height) source
            else source.scale(detectWidth, detectHeight)
        val rgba = Mat(); val gray = Mat(); val edges = Mat(); val hierarchy = Mat()
        val contours = mutableListOf<MatOfPoint>()
        try {
            Utils.bitmapToMat(detection, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
            Imgproc.Canny(gray, edges, 50.0, 150.0)
            Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
            val minimumArea = detection.width.toDouble() * detection.height * 0.35
            val corners = contours.asSequence().sortedByDescending { Geometry.contourArea(it) }.take(20).mapNotNull { contour ->
                if (Geometry.contourArea(contour) < minimumArea) return@mapNotNull null
                val curve = MatOfPoint2f(*contour.toArray())
                val polygon = MatOfPoint2f()
                try {
                    Geometry.approxPolyDP(curve, polygon, Geometry.arcLength(curve, true) * 0.02, true)
                    val corners = MatOfPoint(*polygon.toArray())
                    try { if (polygon.total() == 4L && Geometry.isContourConvex(corners)) polygon.toArray() else null }
                    finally { corners.release() }
                } finally { curve.release(); polygon.release() }
            }.firstOrNull() ?: return source
            val scaleX = source.width.toDouble() / detection.width
            val scaleY = source.height.toDouble() / detection.height
            val fullCorners = corners.map { Point(it.x * scaleX, it.y * scaleY) }
            val topLeft = fullCorners.minBy { it.x + it.y }
            val bottomRight = fullCorners.maxBy { it.x + it.y }
            val topRight = fullCorners.maxBy { it.x - it.y }
            val bottomLeft = fullCorners.minBy { it.x - it.y }
            // Sum/difference ordering can choose the same vertex twice for diagonal sheets.
            if (listOf(topLeft, topRight, bottomRight, bottomLeft).distinctBy { it.x to it.y }.size != 4) return source
            val width = max(hypot(topRight.x - topLeft.x, topRight.y - topLeft.y), hypot(bottomRight.x - bottomLeft.x, bottomRight.y - bottomLeft.y)).roundToInt().coerceAtLeast(1)
            val height = max(hypot(bottomLeft.x - topLeft.x, bottomLeft.y - topLeft.y), hypot(bottomRight.x - topRight.x, bottomRight.y - topRight.y)).roundToInt().coerceAtLeast(1)
            val from = MatOfPoint2f(topLeft, topRight, bottomRight, bottomLeft)
            val to = MatOfPoint2f(Point(0.0, 0.0), Point(width - 1.0, 0.0), Point(width - 1.0, height - 1.0), Point(0.0, height - 1.0))
            val matrix = Geometry.getPerspectiveTransform(from, to)
            val corrected = Mat()
            return try {
                Utils.bitmapToMat(source, rgba)
                Imgproc.warpPerspective(rgba, corrected, matrix, Size(width.toDouble(), height.toDouble()))
                createBitmap(width, height).also { Utils.matToBitmap(corrected, it) }
            } finally { from.release(); to.release(); matrix.release(); corrected.release() }
        } finally { rgba.release(); gray.release(); edges.release(); hierarchy.release(); contours.forEach { it.release() }; if (detection !== source) detection.recycle() }
    }

    /** Converts the page to high contrast grayscale and sharpens text before OCR. */
    fun prepareForTextRecognition(source: Bitmap): Bitmap {
        if (!OpenCVLoader.initLocal()) return source
        val rgba = Mat()
        val gray = Mat()
        val adjusted = Mat()
        val blurred = Mat()
        val sharpened = Mat()
        return try {
            Utils.bitmapToMat(source, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            gray.convertTo(adjusted, -1, 1.18, 8.0)
            Imgproc.GaussianBlur(adjusted, blurred, Size(0.0, 0.0), 1.0)
            Core.addWeighted(adjusted, 1.45, blurred, -0.45, 0.0, sharpened)
            Imgproc.cvtColor(sharpened, rgba, Imgproc.COLOR_GRAY2RGBA)
            createBitmap(source.width, source.height).also {
                Utils.matToBitmap(rgba, it)
            }
        } finally {
            rgba.release(); gray.release(); adjusted.release(); blurred.release(); sharpened.release()
        }
    }
}
