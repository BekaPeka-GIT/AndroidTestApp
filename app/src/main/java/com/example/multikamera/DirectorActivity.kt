package com.example.multikamera

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.Size
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Reżyser: łączy się z Kamerami, pokazuje podział ekranu i nagrywa go do jednego filmu MP4.
 */
class DirectorActivity : ComponentActivity(), RemoteCamera.Listener {

    private companion object {
        const val OWN_KEY = "local"
        const val FPS = 30
        const val RECONNECT_DELAY_MS = 3000L
    }

    /** Podgląd o proporcjach 16:9 (takich jak film). */
    private class PreviewSurface(context: Context) : SurfaceView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            setMeasuredDimension(w, w * 9 / 16)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val compositor = Compositor()
    private val ownSource = FrameSource(OWN_KEY, "Ten telefon")
    private val sources = LinkedHashMap<String, FrameSource>()   // dostęp pod synchronized(sources)
    private val remotes = HashMap<String, RemoteCamera>()          // tylko wątek UI
    private val wanted = HashSet<String>()                         // kamery, do których chcemy być podłączeni

    private lateinit var rig: CameraRig
    private lateinit var discovery: Discovery
    private lateinit var status: TextView
    private lateinit var recordButton: Button
    private lateinit var ownButton: Button

    @Volatile private var previewHolder: SurfaceHolder? = null
    @Volatile private var recorder: SplitScreenRecorder? = null
    @Volatile private var recordingStartedAt = 0L
    @Volatile private var rendering = true
    private var ownCameraOn = true
    private var stopping = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val preview = PreviewSurface(this)
        preview.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { previewHolder = holder }
            override fun surfaceChanged(holder: SurfaceHolder, f: Int, w: Int, h: Int) { previewHolder = holder }
            override fun surfaceDestroyed(holder: SurfaceHolder) { previewHolder = null }
        })

        status = label("", 15f).apply { setPadding(dp(16), dp(12), dp(16), dp(12)) }
        recordButton = pillButton("●  Nagrywaj", RED) { toggleRecording() }
        ownButton = pillButton("Moja kamera: wł.") { toggleOwnCamera() }
        val switchButton = pillButton("🔄  Przód / tył") { rig.switchCamera() }
        val ipButton = pillButton("➕  Połącz po IP") { askForAddress() }
        val searchButton = pillButton("🔍  Szukaj kamer") { startDiscovery(); toast("Szukam kamer w sieci…") }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(18, 18, 22))
            addView(preview)
            addView(row(recordButton).apply { setPadding(dp(12), dp(12), dp(12), 0) })
            addView(row(ownButton, switchButton).apply { setPadding(dp(12), 0, dp(12), 0) })
            addView(row(searchButton, ipButton).apply { setPadding(dp(12), 0, dp(12), 0) })
            addView(status)
            addView(label(
                "Na pozostałych telefonach uruchom MultiKamera i wybierz „Kamera”. " +
                    "Telefony muszą być w tej samej sieci Wi-Fi. Kamery łączą się automatycznie; " +
                    "jeśli się nie pojawią, użyj „Połącz po IP” (adres widać na ekranie kamery).", 13f
            ).apply {
                setTextColor(Color.LTGRAY)
                setPadding(dp(16), 0, dp(16), dp(16))
            })
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.rgb(18, 18, 22))
            addView(content)
        })

        synchronized(sources) { sources[OWN_KEY] = ownSource }
        rig = CameraRig(this, null, Size(1280, 720)) { image ->
            try { ownSource.update(image) } finally { image.close() }
        }
        rig.start { msg -> runOnUiThread { toast(msg) } }

        discovery = Discovery(this)
        startDiscovery()
        thread(name = "render", isDaemon = true) { renderLoop() }
        updateStatus()
    }

    // ---------- Rysowanie ----------

    private fun renderLoop() {
        val frameMs = 1000L / FPS
        while (rendering) {
            val started = SystemClock.elapsedRealtime()
            val list = synchronized(sources) { sources.values.toList() }
            val rec = recorder
            val seconds = if (rec != null) (started - recordingStartedAt) / 1000 else null
            try {
                rec?.drawFrame { canvas ->
                    compositor.draw(canvas, rec.width, rec.height, list, showLabels = false)
                }
            } catch (_: Exception) {}
            previewHolder?.let { holder ->
                try {
                    val canvas = if (Build.VERSION.SDK_INT >= 26) holder.lockHardwareCanvas() else holder.lockCanvas()
                    if (canvas != null) {
                        try {
                            compositor.draw(canvas, canvas.width, canvas.height, list, showLabels = true, recordingSeconds = seconds)
                        } finally {
                            holder.unlockCanvasAndPost(canvas)
                        }
                    }
                } catch (_: Exception) {}
            }
            val elapsed = SystemClock.elapsedRealtime() - started
            if (elapsed < frameMs) Thread.sleep(frameMs - elapsed)
        }
    }

    // ---------- Nagrywanie ----------

    private fun toggleRecording() {
        if (stopping) return
        if (recorder == null) startRecording() else stopRecording()
    }

    private fun startRecording() {
        val name = "MultiKamera_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(cacheDir, "$name.mp4")
        val audio = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val rec = try {
            SplitScreenRecorder(file, withAudio = audio).also { it.start() }
        } catch (e: Exception) {
            toast("Nie udało się rozpocząć nagrywania: ${e.message}")
            return
        }
        recordingStartedAt = SystemClock.elapsedRealtime()
        recorder = rec
        remotes.values.forEach { it.sendStart(name) }
        recordButton.text = "■  Stop"
        if (!audio) toast("Nagrywam bez dźwięku (brak uprawnienia do mikrofonu)")
        updateStatus()

        recordButton.tag = name
    }

    private fun stopRecording() {
        val rec = recorder ?: return
        val name = recordButton.tag as? String ?: "MultiKamera"
        recorder = null
        stopping = true
        recordButton.text = "Zapisuję…"
        remotes.values.forEach { it.sendStop() }
        thread {
            val ok = rec.stop()
            val message = if (ok) {
                try {
                    "Zapisano: " + Gallery.saveVideo(this, File(cacheDir, "$name.mp4"), "$name.mp4")
                } catch (e: Exception) {
                    "Błąd zapisu do galerii: ${e.message}"
                }
            } else {
                "Nagranie się nie udało (brak klatek obrazu)."
            }
            runOnUiThread {
                stopping = false
                recordButton.text = "●  Nagrywaj"
                toast(message)
                updateStatus()
            }
        }
    }

    // ---------- Kamery ----------

    private fun toggleOwnCamera() {
        ownCameraOn = !ownCameraOn
        rig.setEnabled(ownCameraOn)
        synchronized(sources) {
            if (ownCameraOn) {
                val others = sources.toMap()
                sources.clear()
                sources[OWN_KEY] = ownSource
                sources.putAll(others)
            } else {
                sources.remove(OWN_KEY)
                ownSource.clear()
            }
        }
        ownButton.text = if (ownCameraOn) "Moja kamera: wł." else "Moja kamera: wył."
        updateStatus()
    }

    private fun startDiscovery() {
        discovery.discover { _, host, port -> runOnUiThread { connect(host, port) } }
    }

    private fun connect(host: String, port: Int) {
        val key = "$host:$port"
        wanted += key
        if (remotes.containsKey(key) || isFinishing) return
        val camera = RemoteCamera(host, port, this)
        remotes[key] = camera
        camera.connect()
        updateStatus()
    }

    private fun askForAddress() {
        val input = EditText(this).apply {
            hint = "np. 192.168.1.23:40123"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        AlertDialog.Builder(this)
            .setTitle("Adres kamery")
            .setMessage("Wpisz adres widoczny na ekranie telefonu-kamery.")
            .setView(input)
            .setPositiveButton("Połącz") { _, _ ->
                val parts = input.text.toString().trim().split(":")
                val port = parts.getOrNull(1)?.toIntOrNull()
                if (parts.size == 2 && parts[0].isNotEmpty() && port != null) connect(parts[0], port)
                else toast("Podaj adres w formacie IP:port")
            }
            .setNegativeButton("Anuluj", null)
            .show()
    }

    override fun onConnected(camera: RemoteCamera) {
        synchronized(sources) {
            val source = sources.getOrPut(camera.key) { FrameSource(camera.key, camera.name) }
            source.label = camera.name
        }
        runOnUiThread {
            val name = recordButton.tag as? String
            if (recorder != null && name != null) camera.sendStart(name)
            toast("Połączono: ${camera.name}")
            updateStatus()
        }
    }

    override fun onFrame(camera: RemoteCamera, frame: Bitmap, rotation: Int) {
        val source = synchronized(sources) { sources[camera.key] } ?: return
        source.update(frame, rotation)
    }

    override fun onStatus(camera: RemoteCamera, text: String) {}

    override fun onDisconnected(camera: RemoteCamera, reason: String) {
        synchronized(sources) { sources.remove(camera.key) }
        runOnUiThread {
            if (remotes[camera.key] === camera) remotes.remove(camera.key)
            updateStatus()
            // Próbujemy połączyć się ponownie, dopóki ekran Reżysera jest otwarty.
            if (!isFinishing && camera.key in wanted) {
                main.postDelayed({ if (!isFinishing) connect(camera.host, camera.port) }, RECONNECT_DELAY_MS)
            }
        }
    }

    private fun updateStatus() {
        val names = synchronized(sources) { sources.values.map { it.label } }
        status.text = buildString {
            append(if (recorder != null) "🔴 Nagrywanie trwa\n" else "Gotowy do nagrywania\n")
            append("Kamery w filmie (${names.size}): ")
            append(if (names.isEmpty()) "brak" else names.joinToString(", "))
            val connecting = remotes.size - (names.size - if (ownCameraOn) 1 else 0)
            if (connecting > 0) append("\nŁączenie z kamerami: $connecting")
            append("\nFilmy trafiają do galerii: Filmy/MultiKamera")
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        super.onDestroy()
        rendering = false
        main.removeCallbacksAndMessages(null)
        recorder?.let { rec ->
            recorder = null
            val name = recordButton.tag as? String ?: "MultiKamera"
            val app = applicationContext
            thread {
                if (rec.stop()) {
                    try { Gallery.saveVideo(app, File(app.cacheDir, "$name.mp4"), "$name.mp4") } catch (_: Exception) {}
                }
            }
        }
        remotes.values.forEach { it.sendStop(); it.close() }
        remotes.clear()
        discovery.close()
        rig.close()
    }
}
