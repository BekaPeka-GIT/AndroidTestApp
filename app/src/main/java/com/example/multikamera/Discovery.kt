package com.example.multikamera

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

/**
 * Wyszukiwanie Kamer w sieci lokalnej (mDNS / DNS-SD) i ogłaszanie się Kamery.
 */
class Discovery(context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    // --- Kamera: ogłaszanie ---

    private var registration: NsdManager.RegistrationListener? = null

    fun advertise(name: String, port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = Protocol.SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        registration = listener
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    // --- Reżyser: wyszukiwanie ---

    private var discovery: NsdManager.DiscoveryListener? = null
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var onFound: ((name: String, host: String, port: Int) -> Unit)? = null

    fun discover(onFound: (name: String, host: String, port: Int) -> Unit) {
        stopDiscovery()
        this.onFound = onFound
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceType.contains("_multikamera")) enqueue(info)
            }
        }
        discovery = listener
        nsd.discoverServices(Protocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    // NsdManager potrafi rozwiązywać tylko jedną usługę naraz, więc kolejkujemy.
    private fun enqueue(info: NsdServiceInfo) {
        synchronized(queue) {
            queue.addLast(info)
            if (resolving) return
            resolving = true
        }
        resolveNext()
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        val next = synchronized(queue) {
            val n = queue.removeFirstOrNull()
            if (n == null) resolving = false
            n
        } ?: return
        try {
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = resolveNext()
                override fun onServiceResolved(info: NsdServiceInfo) {
                    val host = info.host?.hostAddress
                    if (host != null) onFound?.invoke(info.serviceName, host, info.port)
                    resolveNext()
                }
            })
        } catch (e: Exception) {
            resolveNext()
        }
    }

    private fun stopDiscovery() {
        discovery?.let { try { nsd.stopServiceDiscovery(it) } catch (_: Exception) {} }
        discovery = null
    }

    fun close() {
        stopDiscovery()
        registration?.let { try { nsd.unregisterService(it) } catch (_: Exception) {} }
        registration = null
    }
}
