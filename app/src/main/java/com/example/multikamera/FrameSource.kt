package com.example.multikamera

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy

/** Najnowsza klatka z jednej kamery (lokalnej lub zdalnej). Bezpieczna wątkowo. */
class FrameSource(val key: String, @Volatile var label: String) {
    private val lock = Any()
    private var bitmap: Bitmap? = null
    private var rotation = 0

    /** Klatka ze zdalnej kamery (już zdekodowany JPEG). */
    fun update(frame: Bitmap, rotationDegrees: Int) {
        synchronized(lock) {
            bitmap = frame
            rotation = rotationDegrees
        }
    }

    /** Klatka z lokalnej kamery (RGBA_8888). Bitmapa jest używana ponownie, żeby nie obciążać pamięci. */
    fun update(image: ImageProxy) {
        val plane = image.planes[0]
        val w = image.width
        val h = image.height
        synchronized(lock) {
            rotation = image.imageInfo.rotationDegrees
            if (plane.pixelStride == 4 && plane.rowStride == w * 4) {
                var b = bitmap
                if (b == null || b.width != w || b.height != h || !b.isMutable) {
                    b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bitmap = b
                }
                plane.buffer.rewind()
                b!!.copyPixelsFromBuffer(plane.buffer)
            } else {
                bitmap = image.toBitmap()
            }
        }
    }

    fun clear() {
        synchronized(lock) { bitmap = null }
    }

    /** Daje dostęp do klatki na czas rysowania (blokuje podmianę klatki w tym czasie). */
    fun <T> withFrame(block: (Bitmap?, Int) -> T): T = synchronized(lock) { block(bitmap, rotation) }
}
