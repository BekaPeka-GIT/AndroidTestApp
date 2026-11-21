package com.example.multikamera

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.util.Size
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.camera.core.ImageProxy
import androidx.camera.view.PreviewView
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Telefon jako zdalna kamera: pokazuje podgląd i wysyła klatki do Reżysera. */
class CameraActivity : ComponentActivity(), CameraLink.Listener {

    private companion object {
        const val FRAME_INTERVAL_MS = 50L // ok. 20 klatek/s
        const val JPEG_QUALITY = 70
    }

    private lateinit var status: TextView
    private lateinit var recBadge: TextView
    private lateinit var rig: CameraRig
    private lateinit var link: CameraLink
    private lateinit var discovery: Discovery

    private val sender = Executors.newSingleThreadExecutor()
    private val sending = AtomicBoolean(false)
    private var lastFrameAt = 0L
    private var directorAddress: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val preview = PreviewView(this)
        status = label("", 15f).apply {
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        recBadge = label("● NAGRYWANIE", 16f).apply {
            setBackgroundColor(RED)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            visibility = TextView.GONE
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(recBadge, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(12), dp(12), 0, 0) })
        }
        val switch = pillButton("🔄  Przód / tył") { rig.switchCamera(); updateStatus() }

        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(preview)
            addView(top, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP
            ))
            addView(switch, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply { bottomMargin = dp(32) })
        })

        link = CameraLink(this)
        link.start()
        discovery = Discovery(this)
        discovery.advertise("MultiKamera ${Protocol.deviceName}", link.port)

        rig = CameraRig(this, preview, Size(960, 540), ::onImage)
        rig.start { msg -> runOnUiThread { status.text = msg } }
        updateStatus()
    }

    private fun onImage(image: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (!link.isConnected || now - lastFrameAt < FRAME_INTERVAL_MS) return
            if (!sending.compareAndSet(false, true)) return // poprzednia klatka jeszcze się wysyła
            lastFrameAt = now
            val rotation = image.imageInfo.rotationDegrees
            val bitmap = image.toBitmap()
            sender.execute {
                try {
                    val out = ByteArrayOutputStream(64 * 1024)
                    bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                    link.sendFrame(rotation, out.toByteArray())
                } finally {
                    sending.set(false)
                }
            }
        } finally {
            image.close()
        }
    }

    private fun updateStatus() {
        val ip = Protocol.localIp() ?: "brak sieci"
        val camera = if (rig.useFront) "przednia" else "tylna"
        status.text = buildString {
            append("Kamera: ${Protocol.deviceName} ($camera)\n")
            val director = directorAddress
            if (director != null) {
                append("✅ Połączono z Reżyserem ($director)")
            } else {
                append("⏳ Czekam na Reżysera w tej samej sieci Wi-Fi…\n")
                append("Adres do ręcznego połączenia: $ip:${link.port}")
            }
        }
    }

    override fun onDirectorConnected(address: String) = runOnUiThread {
        directorAddress = address
        updateStatus()
    }

    override fun onDirectorDisconnected() = runOnUiThread {
        directorAddress = null
        recBadge.visibility = TextView.GONE
        updateStatus()
    }

    override fun onStartCommand(session: String) = runOnUiThread { recBadge.visibility = TextView.VISIBLE }

    override fun onStopCommand() = runOnUiThread { recBadge.visibility = TextView.GONE }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) updateStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        discovery.close()
        link.close()
        rig.close()
        sender.shutdown()
    }
}
