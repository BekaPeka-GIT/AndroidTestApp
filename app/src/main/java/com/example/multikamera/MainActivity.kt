package com.example.multikamera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/** Ekran startowy: wybór roli telefonu. */
class MainActivity : ComponentActivity() {

    private var pending: Class<*>? = null

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val target = pending ?: return@registerForActivityResult
        if (result[Manifest.permission.CAMERA] == true || hasCamera()) {
            startActivity(Intent(this, target))
        } else {
            Toast.makeText(this, "Bez dostępu do kamery aplikacja nie zadziała", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(18, 18, 22))
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        root.addView(label("MultiKamera", 32f).apply { gravity = Gravity.CENTER })
        root.addView(label(
            "Połącz kilka telefonów w tej samej sieci Wi-Fi i nagraj jeden film z podziałem ekranu.",
            16f
        ).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(32))
        })

        val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(72)).apply {
            setMargins(0, dp(8), 0, dp(4))
        }
        root.addView(pillButton("🎬  Reżyser (nagrywa film)", RED) {
            open(DirectorActivity::class.java, Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        }, params)
        root.addView(label(
            "Zbiera obraz ze wszystkich kamer, pokazuje podział ekranu i zapisuje film w galerii.", 13f
        ).apply { gravity = Gravity.CENTER })

        root.addView(pillButton("📷  Kamera (wysyła obraz)", Color.rgb(40, 110, 200)) {
            open(CameraActivity::class.java, Manifest.permission.CAMERA)
        }, LinearLayout.LayoutParams(params).apply { topMargin = dp(32) })
        root.addView(label(
            "Ten telefon staje się dodatkową kamerą dla Reżysera.", 13f
        ).apply { gravity = Gravity.CENTER })

        setContentView(root)
    }

    private fun hasCamera() =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun open(target: Class<*>, vararg perms: String) {
        pending = target
        permissions.launch(arrayOf(*perms))
    }
}
