package com.example.multikamera

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Strona Kamery: serwer TCP, do którego łączy się Reżyser.
 * Obsługuje jednego Reżysera naraz (nowe połączenie zastępuje stare).
 */
class CameraLink(private val listener: Listener) {

    interface Listener {
        fun onDirectorConnected(address: String)
        fun onDirectorDisconnected()
        fun onStartCommand(session: String)
        fun onStopCommand()
    }

    private val server = ServerSocket(0)
    private val lock = Any()
    @Volatile private var socket: Socket? = null
    @Volatile private var out: DataOutputStream? = null

    val port: Int get() = server.localPort
    val isConnected: Boolean get() = out != null

    fun start() {
        thread(name = "camera-accept", isDaemon = true) {
            while (!server.isClosed) {
                val s = try {
                    server.accept()
                } catch (e: IOException) {
                    break
                }
                attach(s)
            }
        }
    }

    private fun attach(s: Socket) {
        closeClient(notify = false)
        s.tcpNoDelay = true
        val o = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 64 * 1024))
        synchronized(lock) {
            socket = s
            out = o
        }
        send { it.writeByte(Protocol.HELLO); it.writeUTF(Protocol.deviceName) }
        listener.onDirectorConnected(s.inetAddress?.hostAddress ?: "?")
        thread(name = "camera-read", isDaemon = true) {
            try {
                val input = DataInputStream(BufferedInputStream(s.getInputStream()))
                while (true) {
                    when (input.readUnsignedByte()) {
                        Protocol.START -> listener.onStartCommand(input.readUTF())
                        Protocol.STOP -> listener.onStopCommand()
                        else -> throw IOException("Nieznana wiadomość")
                    }
                }
            } catch (e: IOException) {
                if (socket === s) closeClient(notify = true)
            }
        }
    }

    /** Wysyła wiadomość; zwraca false, gdy nie ma połączenia. */
    private fun send(block: (DataOutputStream) -> Unit): Boolean {
        synchronized(lock) {
            val o = out ?: return false
            return try {
                block(o)
                o.flush()
                true
            } catch (e: IOException) {
                false
            }
        }.also { ok -> if (!ok && out != null) closeClient(notify = true) }
    }

    fun sendFrame(rotation: Int, jpeg: ByteArray): Boolean = send {
        it.writeByte(Protocol.FRAME)
        it.writeInt(rotation)
        it.writeInt(jpeg.size)
        it.write(jpeg)
    }

    fun sendStatus(text: String): Boolean = send {
        it.writeByte(Protocol.STATUS)
        it.writeUTF(text)
    }

    private fun closeClient(notify: Boolean) {
        val s: Socket?
        synchronized(lock) {
            s = socket
            socket = null
            out = null
        }
        if (s != null) {
            try { s.close() } catch (_: IOException) {}
            if (notify) listener.onDirectorDisconnected()
        }
    }

    fun close() {
        try { server.close() } catch (_: IOException) {}
        closeClient(notify = false)
    }
}
