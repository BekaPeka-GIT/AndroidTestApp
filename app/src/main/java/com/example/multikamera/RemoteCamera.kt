package com.example.multikamera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Strona Reżysera: połączenie z jedną zdalną Kamerą.
 * Wszystkie metody Listenera są wołane z wątku w tle.
 */
class RemoteCamera(
    val host: String,
    val port: Int,
    private val listener: Listener,
) {
    interface Listener {
        fun onConnected(camera: RemoteCamera)
        fun onFrame(camera: RemoteCamera, frame: Bitmap, rotation: Int)
        fun onStatus(camera: RemoteCamera, text: String)
        fun onDisconnected(camera: RemoteCamera, reason: String)
    }

    val key: String get() = "$host:$port"

    @Volatile var name: String = host
        private set

    private val lock = Any()
    @Volatile private var socket: Socket? = null
    @Volatile private var out: DataOutputStream? = null
    @Volatile private var closed = false

    fun connect() {
        thread(name = "remote-$key", isDaemon = true) {
            val s = Socket()
            try {
                s.connect(InetSocketAddress(host, port), 5000)
                s.tcpNoDelay = true
                synchronized(lock) {
                    socket = s
                    out = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
                }
                readLoop(DataInputStream(BufferedInputStream(s.getInputStream(), 64 * 1024)))
            } catch (e: IOException) {
                if (!closed) listener.onDisconnected(this, e.message ?: "błąd połączenia")
                close()
            }
        }
    }

    private fun readLoop(input: DataInputStream) {
        while (true) {
            when (input.readUnsignedByte()) {
                Protocol.HELLO -> {
                    name = input.readUTF()
                    listener.onConnected(this)
                }
                Protocol.FRAME -> {
                    val rotation = input.readInt()
                    val len = input.readInt()
                    if (len <= 0 || len > 5_000_000) throw IOException("Zły rozmiar klatki")
                    val bytes = ByteArray(len)
                    input.readFully(bytes)
                    BitmapFactory.decodeByteArray(bytes, 0, len)?.let { listener.onFrame(this, it, rotation) }
                }
                Protocol.STATUS -> listener.onStatus(this, input.readUTF())
                else -> throw IOException("Nieznana wiadomość")
            }
        }
    }

    private fun send(block: (DataOutputStream) -> Unit) {
        thread(isDaemon = true) {
            synchronized(lock) {
                val o = out ?: return@thread
                try {
                    block(o)
                    o.flush()
                } catch (_: IOException) {
                    // Wątek czytający wykryje zerwanie połączenia.
                }
            }
        }
    }

    fun sendStart(session: String) = send { it.writeByte(Protocol.START); it.writeUTF(session) }

    fun sendStop() = send { it.writeByte(Protocol.STOP) }

    fun close() {
        closed = true
        try { socket?.close() } catch (_: IOException) {}
        socket = null
        out = null
    }
}
