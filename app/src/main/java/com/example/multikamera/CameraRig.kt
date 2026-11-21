package com.example.multikamera

import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/**
 * Obsługa kamery telefonu przez CameraX: (opcjonalny) podgląd na ekranie
 * i analiza klatek, które trafiają do podziału ekranu.
 */
class CameraRig(
    private val activity: ComponentActivity,
    private val previewView: PreviewView?,
    private val targetSize: Size,
    private val analyzer: ImageAnalysis.Analyzer,
) {
    var useFront = false
        private set

    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var enabled = false
    private val executor = Executors.newSingleThreadExecutor()
    private var onError: (String) -> Unit = {}

    // Ekran jest zablokowany w pionie, ale obraz obracamy zgodnie z tym, jak trzymany jest telefon.
    private val orientation = object : OrientationEventListener(activity) {
        override fun onOrientationChanged(o: Int) {
            if (o == ORIENTATION_UNKNOWN) return
            analysis?.targetRotation = when (o) {
                in 45 until 135 -> Surface.ROTATION_270
                in 135 until 225 -> Surface.ROTATION_180
                in 225 until 315 -> Surface.ROTATION_90
                else -> Surface.ROTATION_0
            }
        }
    }

    fun start(onError: (String) -> Unit) {
        this.onError = onError
        enabled = true
        orientation.enable()
        val future = ProcessCameraProvider.getInstance(activity)
        future.addListener({
            provider = future.get()
            bind()
        }, ContextCompat.getMainExecutor(activity))
    }

    fun switchCamera() {
        useFront = !useFront
        bind()
    }

    fun setEnabled(on: Boolean) {
        enabled = on
        if (on) bind() else provider?.unbindAll()
    }

    private fun bind() {
        val p = provider ?: return
        p.unbindAll()
        if (!enabled) return
        val resolution = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(targetSize, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
            )
            .build()
        val a = ImageAnalysis.Builder()
            .setResolutionSelector(resolution)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
        a.setAnalyzer(executor, analyzer)
        analysis = a
        val useCases = mutableListOf<UseCase>(a)
        if (previewView != null) {
            useCases += Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        }
        val selector = if (useFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        try {
            p.bindToLifecycle(activity, selector, *useCases.toTypedArray())
        } catch (e: Exception) {
            onError("Nie można uruchomić kamery: ${e.message}")
        }
    }

    fun close() {
        orientation.disable()
        executor.shutdown()
    }
}
