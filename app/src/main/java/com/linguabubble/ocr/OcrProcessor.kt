package com.linguabubble.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.view.WindowManager
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Capture l'écran via MediaProjection et reconnaît le texte avec ML Kit OCR japonais (on-device).
 *
 * Le VirtualDisplay est créé une seule fois au démarrage du service ([start]) et libéré à l'arrêt
 * ([stop]). [extractText] lit un frame depuis l'ImageReader persistant — ce qui contourne la
 * restriction Android 14 qui invalide la MediaProjection après le premier VirtualDisplay libéré.
 */
@Singleton
class OcrProcessor @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val recognizer = TextRecognition.getClient(
        JapaneseTextRecognizerOptions.Builder().build()
    )

    private var imageReader:    ImageReader?    = null
    private var virtualDisplay: VirtualDisplay? = null

    val isReady: Boolean get() = virtualDisplay != null

    // ─── Cycle de vie ─────────────────────────────────────────────────────────

    /**
     * Crée le VirtualDisplay + ImageReader. À appeler une seule fois dans onStartCommand.
     * Si déjà démarré, ne fait rien.
     */
    fun start(mediaProjection: MediaProjection) {
        if (virtualDisplay != null) return

        val bounds  = windowManager.currentWindowMetrics.bounds
        val width   = bounds.width()
        val height  = bounds.height()
        val density = context.resources.displayMetrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "AwabiOcr",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null, null
        )
    }

    /**
     * Redimensionne le VirtualDisplay et l'ImageReader aux nouvelles dimensions de l'écran.
     * À appeler lors d'un changement de configuration (rotation, pliage).
     * N'appelle PAS createVirtualDisplay (interdit sur la même MediaProjection instance).
     */
    fun onConfigurationChanged() {
        val vd = virtualDisplay ?: return
        val bounds  = windowManager.currentWindowMetrics.bounds
        val width   = bounds.width()
        val height  = bounds.height()
        val density = context.resources.displayMetrics.densityDpi

        // Détacher la surface avant de fermer l'ancien ImageReader
        vd.setSurface(null)
        imageReader?.close()

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        vd.resize(width, height, density)
        vd.setSurface(imageReader!!.surface)
    }

    /** Libère le VirtualDisplay et l'ImageReader. À appeler dans onDestroy. */
    fun stop() {
        virtualDisplay?.release()
        imageReader?.close()
        virtualDisplay = null
        imageReader    = null
    }

    // ─── Capture + reconnaissance ─────────────────────────────────────────────

    /**
     * Acquiert le dernier frame disponible et retourne le texte japonais reconnu.
     * Attend jusqu'à 500 ms pour qu'un frame soit disponible.
     */
    suspend fun extractText(): String {
        val bitmap = captureFrame() ?: return ""
        return recognize(bitmap)
    }

    private suspend fun captureFrame(): Bitmap? {
        val reader = imageReader ?: return null
        // Tenter jusqu'à 10 × 50 ms = 500 ms pour obtenir un frame frais
        repeat(10) {
            val image = reader.acquireLatestImage()
            if (image != null) {
                val bitmap = imageToBitmap(image)
                image.close()
                return bitmap
            }
            delay(50)
        }
        return null
    }

    private fun imageToBitmap(image: Image): Bitmap {
        // Utiliser les dimensions réelles de l'image, pas celles de windowManager
        // (évite la corruption si la config a changé depuis la création du VirtualDisplay)
        val width       = image.width
        val height      = image.height
        val plane       = image.planes[0]
        val rowStride   = plane.rowStride
        val pixelStride = plane.pixelStride
        val rowPadding  = rowStride - pixelStride * width

        val raw = Bitmap.createBitmap(
            width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
        )
        raw.copyPixelsFromBuffer(plane.buffer)
        return if (rowPadding == 0) raw
        else Bitmap.createBitmap(raw, 0, 0, width, height)
    }

    private suspend fun recognize(bitmap: Bitmap): String =
        suspendCancellableCoroutine { continuation ->
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result ->
                    if (!continuation.isCompleted) continuation.resume(result.text)
                }
                .addOnFailureListener {
                    if (!continuation.isCompleted) continuation.resume("")
                }
        }
}
