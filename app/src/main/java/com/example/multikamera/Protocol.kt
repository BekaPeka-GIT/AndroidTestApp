package com.example.multikamera

import android.os.Build
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Prosty protokół TCP między Reżyserem a Kamerami.
 * Każda wiadomość zaczyna się bajtem typu, potem dane (DataOutputStream).
 */
object Protocol {
    const val SERVICE_TYPE = "_multikamera._tcp."

    // Kamera -> Reżyser
    const val HELLO = 1      // UTF: nazwa urządzenia
    const val FRAME = 2      // int obrót (stopnie), int długość, JPEG
    const val STATUS = 3     // UTF: tekst statusu

    // Reżyser -> Kamera (tylko informacyjnie: kamera pokazuje, że trwa nagrywanie)
    const val START = 10     // UTF: identyfikator sesji
    const val STOP = 11

    val deviceName: String
        get() {
            val model = Build.MODEL ?: "Android"
            val maker = Build.MANUFACTURER ?: ""
            return if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model".trim()
        }

    /** Adres IPv4 telefonu w sieci lokalnej (Wi-Fi). */
    fun localIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    } catch (e: Exception) {
        null
    }
}
