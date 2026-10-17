package com.shilapi.xcertplay.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import java.io.BufferedReader
import java.io.Closeable
import java.io.IOException
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

data class CarPlayBonjourEndpoint(
    val serviceName: String,
    val host: String,
    val port: Int,
    val bluetoothId: String?,
)

sealed interface CarPlayBonjourEvent {
    data class Resolved(val endpoint: CarPlayBonjourEndpoint) : CarPlayBonjourEvent

    data class Probed(
        val endpoint: CarPlayBonjourEndpoint,
        val attempts: Int,
        val statusLine: String?,
        val error: IOException?,
    ) : CarPlayBonjourEvent
}

/** Pure protocol values shared by the Android runtime and JVM tests. */
object CarPlayBonjourProtocol {
    fun airPlayTxtRecords(
        config: AirPlayConfig,
        identity: AirPlayIdentity,
    ): Map<String, String> = linkedMapOf(
        "deviceid" to config.deviceId,
        "features" to "0x44540380,0x61",
        "flags" to "0x4",
        "model" to config.model,
        "srcvers" to config.sourceVersion,
        "protovers" to "1.1",
        "pi" to identity.pairingId,
        "pk" to identity.publicKeyHex,
    )

    fun connectProbeRequest(
        host: String,
        port: Int,
        sourceVersion: String,
        deviceId: String,
    ): String {
        val unbracketedHost = host.removeSurrounding("[", "]").substringBefore('%')
        require(unbracketedHost.isNotBlank()) { "host must not be blank" }
        require(port in 1..65535) { "port must be in 1..65535" }
        require(sourceVersion.isNotEmpty()) { "sourceVersion must not be empty" }
        require('\r' !in sourceVersion && '\n' !in sourceVersion) {
            "sourceVersion must not contain a line break"
        }
        require('\r' !in unbracketedHost && '\n' !in unbracketedHost) {
            "host must not contain a line break"
        }
        val receiverDeviceId = macAddressToDecimal(deviceId)
        val hostHeader = if (':' in unbracketedHost) {
            "[$unbracketedHost]:$port"
        } else {
            "$unbracketedHost:$port"
        }
        return "GET /ctrl-int/1/connect HTTP/1.1\r\n" +
            "Host: $hostHeader\r\n" +
            "User-Agent: AirPlay/$sourceVersion\r\n" +
            "AirPlay-Receiver-Device-ID: $receiverDeviceId\r\n" +
            "Connection: close\r\n" +
            "\r\n"
    }

    private fun macAddressToDecimal(value: String): String {
        val hexadecimal = value.replace(":", "")
        require(hexadecimal.length == 12 && hexadecimal.all { it.digitToIntOrNull(16) != null }) {
            "deviceId must contain exactly 12 hexadecimal digits"
        }
        return hexadecimal.toLong(16).toString()
    }
}

internal fun canBindBonjourProbeTo(local: InetAddress, target: InetAddress): Boolean =
    (local is Inet6Address && target is Inet6Address) ||
        (local is Inet4Address && target is Inet4Address)

/**
 * Publishes the accessory AirPlay service and discovers the iPhone's CarPlay control service.
 *
 * The NSD callbacks only enqueue work. Resolution, probing, and [onEvent] all run on the worker
 * started by [start], so a blocking consumer callback never runs on the caller or main thread.
 */
class CarPlayBonjour(
    context: Context,
    private val config: AirPlayConfig,
    private val identity: AirPlayIdentity,
    private val advertisedHost: String? = null,
    private val onEvent: (CarPlayBonjourEvent) -> Unit = {},
) : Closeable {
    private val nsdManager = (context.applicationContext ?: context)
        .getSystemService(Context.NSD_SERVICE) as NsdManager
    private val services = LinkedBlockingQueue<NsdServiceInfo>()
    private val seenServices = ConcurrentHashMap.newKeySet<String>()
    private val lifecycleLock = Object()
    private val closeAttemptLock = Any()
    private val localAdvertisedAddress = advertisedHostAddress()

    private var started = false
    @Volatile
    private var closed = false
    private var retired = false
    private var retirementCoordinator: Thread? = null
    private var registrationRequested = false
    private var discoveryRequested = false
    private var registrationStopRequested = false
    private var discoveryStopRequested = false
    private var registrationStopFailure: Exception? = null
    private var discoveryStopFailure: Exception? = null
    private var socketRetirementFailure: Exception? = null
    @Volatile
    private var worker: Thread? = null
    @Volatile
    private var activeSocket: Socket? = null

    private val registrationListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit

        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.w(TAG, "AirPlay NSD registration failed code=$errorCode")
            synchronized(lifecycleLock) {
                registrationRequested = false
                registrationStopRequested = false
                registrationStopFailure = null
                lifecycleLock.notifyAll()
            }
        }

        override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
            synchronized(lifecycleLock) {
                registrationRequested = false
                registrationStopRequested = false
                registrationStopFailure = null
                lifecycleLock.notifyAll()
            }
        }

        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.w(TAG, "AirPlay NSD unregistration failed code=$errorCode")
            synchronized(lifecycleLock) {
                registrationStopRequested = false
                registrationStopFailure = IOException(
                    "AirPlay NSD unregistration failed code=$errorCode",
                )
                lifecycleLock.notifyAll()
            }
        }
    }

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "CarPlay control discovery failed code=$errorCode")
            synchronized(lifecycleLock) {
                discoveryRequested = false
                discoveryStopRequested = false
                discoveryStopFailure = null
                lifecycleLock.notifyAll()
            }
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "CarPlay control discovery stop failed code=$errorCode")
            synchronized(lifecycleLock) {
                discoveryStopRequested = false
                discoveryStopFailure = IOException(
                    "CarPlay control discovery stop failed code=$errorCode",
                )
                lifecycleLock.notifyAll()
            }
        }

        override fun onDiscoveryStarted(serviceType: String) = Unit

        override fun onDiscoveryStopped(serviceType: String) {
            synchronized(lifecycleLock) {
                discoveryRequested = false
                discoveryStopRequested = false
                discoveryStopFailure = null
                lifecycleLock.notifyAll()
            }
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            if (closed) return
            val name = serviceInfo.serviceName ?: return
            val type = serviceInfo.serviceType ?: CARPLAY_CONTROL_SERVICE_TYPE
            val key = "$type|$name"
            if (!seenServices.add(key)) return
            services.offer(serviceInfo)
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            val name = serviceInfo.serviceName ?: return
            val type = serviceInfo.serviceType ?: CARPLAY_CONTROL_SERVICE_TYPE
            seenServices.remove("$type|$name")
        }
    }

    /** Starts publication and discovery. Calling this more than once is harmless. */
    fun start() {
        synchronized(lifecycleLock) {
            check(!closed) { "CarPlayBonjour is closed" }
            if (started) return
            started = true
            try {
                registerAirPlay()
                registrationRequested = true
                nsdManager.discoverServices(
                    CARPLAY_CONTROL_SERVICE_TYPE,
                    NsdManager.PROTOCOL_DNS_SD,
                    discoveryListener,
                )
                discoveryRequested = true
                val createdWorker = Thread(::runWorker, WORKER_NAME).apply {
                    isDaemon = true
                }
                startPublishedWorker(createdWorker) { worker = it }
            } catch (error: Throwable) {
                closed = true
                worker?.interrupt()
                throw error
            }
        }
    }

    override fun close() {
        synchronized(closeAttemptLock) closeAttempt@{
        var failure: Exception? = null
        val workerToJoin: Thread?
        val socketToClose: Socket?
        synchronized(lifecycleLock) {
            if (retired) return@closeAttempt
            closed = true
            if (registrationRequested && !registrationStopRequested) {
                try {
                    nsdManager.unregisterService(registrationListener)
                    registrationStopRequested = true
                    registrationStopFailure = null
                } catch (error: Exception) {
                    registrationStopFailure = error
                    failure = combineCloseFailure(failure, error)
                }
            }
            if (discoveryRequested && !discoveryStopRequested) {
                try {
                    nsdManager.stopServiceDiscovery(discoveryListener)
                    discoveryStopRequested = true
                    discoveryStopFailure = null
                } catch (error: Exception) {
                    discoveryStopFailure = error
                    failure = combineCloseFailure(failure, error)
                }
            }
            services.clear()
            workerToJoin = worker
            socketToClose = activeSocket
            workerToJoin?.interrupt()
        }
        if (socketToClose != null) {
            try {
                socketToClose.close()
                synchronized(lifecycleLock) {
                    if (ownsBonjourSocket(activeSocket, socketToClose)) {
                        activeSocket = null
                        socketRetirementFailure = null
                    }
                }
            } catch (error: Exception) {
                val retained = synchronized(lifecycleLock) {
                    socketRetirementFailure = retainBonjourSocketCloseFailure(
                        activeSocket,
                        socketToClose,
                        socketRetirementFailure,
                        error,
                    )
                    ownsBonjourSocket(activeSocket, socketToClose)
                }
                if (retained) failure = combineCloseFailure(failure, error)
            }
        }
        if (workerToJoin === Thread.currentThread()) {
            val deferred = IOException(
                "CarPlay Bonjour retirement was deferred until its worker exits",
            )
            failure?.let { if (it !== deferred) deferred.addSuppressed(it) }
            scheduleDeferredRetirement()
            throw deferred
        }
        try {
            workerToJoin?.let(::joinWorkerStrict)
            synchronized(lifecycleLock) {
                if (worker === workerToJoin) worker = null
            }
        } catch (error: Exception) {
            failure = combineCloseFailure(failure, error)
        }
        try {
            awaitNsdRetirement()
        } catch (error: Exception) {
            failure = combineCloseFailure(failure, error)
        }
        synchronized(lifecycleLock) {
            registrationStopFailure?.let { failure = combineCloseFailure(failure, it) }
            discoveryStopFailure?.let { failure = combineCloseFailure(failure, it) }
            socketRetirementFailure?.let { failure = combineCloseFailure(failure, it) }
        }
        if (failure == null) {
            synchronized(lifecycleLock) {
                if (worker != null || activeSocket != null ||
                    registrationRequested || discoveryRequested
                ) {
                    failure = IOException("CarPlay Bonjour retirement remains unproven")
                } else {
                    retired = true
                }
            }
        }
        failure?.let { throw it }
        }
    }

    private fun scheduleDeferredRetirement() {
        val coordinator = synchronized(lifecycleLock) {
            retirementCoordinator
                ?.takeUnless { it.state == Thread.State.TERMINATED }
                ?.let { return }
            Thread(
                {
                    var delayMillis = DEFERRED_RETRY_INITIAL_MILLIS
                    try {
                        while (true) {
                            try {
                                close()
                                return@Thread
                            } catch (_: Exception) {
                                // Keep the exact NSD/socket/worker owners and retry externally.
                            }
                            try {
                                Thread.sleep(delayMillis)
                            } catch (_: InterruptedException) {
                                // Interruption accelerates the next retry.
                            }
                            delayMillis = (delayMillis * 2).coerceAtMost(
                                DEFERRED_RETRY_MAX_MILLIS,
                            )
                        }
                    } finally {
                        synchronized(lifecycleLock) {
                            if (retirementCoordinator === Thread.currentThread()) {
                                retirementCoordinator = null
                            }
                        }
                    }
                },
                "carplay-bonjour-retirement",
            ).apply { isDaemon = true }.also { retirementCoordinator = it }
        }
        try {
            coordinator.start()
        } catch (failure: Throwable) {
            synchronized(lifecycleLock) {
                if (retirementCoordinator === coordinator) retirementCoordinator = null
            }
            throw failure
        }
    }

    @Suppress("DEPRECATION")
    private fun registerAirPlay() {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = config.deviceName
            serviceType = AIRPLAY_SERVICE_TYPE
            port = config.port
            CarPlayBonjourProtocol.airPlayTxtRecords(config, identity).forEach { (key, value) ->
                setAttribute(key, value)
            }
            localAdvertisedAddress?.let(::setHost)
        }
        nsdManager.registerService(
            serviceInfo,
            NsdManager.PROTOCOL_DNS_SD,
            registrationListener,
        )
    }

    private fun advertisedHostAddress(): InetAddress? {
        val value = advertisedHost?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val address = try {
            InetAddress.getByName(value.removeSurrounding("[", "]"))
        } catch (error: Exception) {
            throw IllegalArgumentException("Invalid advertised host: $value", error)
        }
        require(!address.isLoopbackAddress) {
            "advertisedHost must not be a loopback address"
        }
        require(address !is Inet6Address || address.isLinkLocalAddress) {
            "advertisedHost must be link-local IPv6 or IPv4"
        }
        return address
    }

    private fun runWorker() {
        while (!closed) {
            val service = try {
                services.poll(WORKER_POLL_MILLIS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                return
            } ?: continue
            if (closed) return
            try {
                handleService(service)
            } catch (_: InterruptedException) {
                return
            } catch (error: Exception) {
                if (!closed) Log.w(TAG, "CarPlay control service handling failed", error)
            }
        }
    }

    private fun handleService(service: NsdServiceInfo) {
        val resolved = resolveWithRetry(service) ?: return
        val address = preferredAddress(resolved) ?: return
        val port = resolved.port
        if (port !in 1..65535) return
        val serviceName = resolved.serviceName ?: service.serviceName ?: return
        val host = address.hostAddress ?: return
        val bluetoothId = resolved.attributes
            ?.get("id")
            ?.let(::decodeTxtValue)
            ?.takeIf { it.isNotBlank() }
        val endpoint = CarPlayBonjourEndpoint(
            serviceName = serviceName,
            host = host,
            port = port,
            bluetoothId = bluetoothId,
        )
        emit(CarPlayBonjourEvent.Resolved(endpoint))
        probe(endpoint, address)?.let(::emit)
    }

    @Suppress("DEPRECATION")
    private fun resolveWithRetry(service: NsdServiceInfo): NsdServiceInfo? {
        repeat(RESOLVE_ATTEMPTS) { attempt ->
            if (closed) return null
            val latch = CountDownLatch(1)
            val resolved = AtomicReference<NsdServiceInfo?>()
            val failure = AtomicInteger(FAILURE_NONE)
            val listener = object : NsdManager.ResolveListener {
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    resolved.set(serviceInfo)
                    latch.countDown()
                }

                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    failure.set(errorCode)
                    latch.countDown()
                }
            }
            val submitted = try {
                synchronized(lifecycleLock) {
                    if (closed) {
                        false
                    } else {
                        nsdManager.resolveService(service, listener)
                        true
                    }
                }
            } catch (error: RuntimeException) {
                Log.w(TAG, "CarPlay control service resolution failed", error)
                false
            }
            if (!submitted) return null
            val completed = try {
                latch.await(RESOLVE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            } catch (error: InterruptedException) {
                throw error
            }
            if (!completed) {
                Log.w(TAG, "CarPlay control service resolution timed out")
                return null
            }
            resolved.get()?.let { return it }
            if (failure.get() != NsdManager.FAILURE_ALREADY_ACTIVE) {
                Log.w(TAG, "CarPlay control service resolution failed code=${failure.get()}")
                return null
            }
            if (!closed && attempt + 1 < RESOLVE_ATTEMPTS) {
                Thread.sleep(RESOLVE_RETRY_DELAY_MILLIS)
            }
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun preferredAddress(serviceInfo: NsdServiceInfo): InetAddress? {
        val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            serviceInfo.hostAddresses.orEmpty()
        } else {
            listOfNotNull(serviceInfo.host)
        }
        return addresses.firstOrNull { it is Inet6Address && it.isLinkLocalAddress }
            ?.let(::applyLocalScope)
            ?: addresses.firstOrNull { it is Inet4Address }
            ?: addresses.firstOrNull { it is Inet6Address }
            ?: addresses.firstOrNull()
    }

    private fun applyLocalScope(address: InetAddress): InetAddress {
        val scope = (localAdvertisedAddress as? Inet6Address)?.scopeId ?: return address
        if (address !is Inet6Address || address.scopeId != 0) return address
        return try {
            Inet6Address.getByAddress(null, address.address, scope)
        } catch (_: Exception) {
            address
        }
    }

    private fun probe(
        endpoint: CarPlayBonjourEndpoint,
        address: InetAddress,
    ): CarPlayBonjourEvent.Probed? {
        var lastError: IOException? = null
        repeat(MAX_PROBE_ATTEMPTS) { attempt ->
            if (closed) return null
            try {
                val statusLine = probeOnce(address, endpoint.port)
                return CarPlayBonjourEvent.Probed(
                    endpoint = endpoint,
                    attempts = attempt + 1,
                    statusLine = statusLine,
                    error = null,
                )
            } catch (error: IOException) {
                lastError = error
            } catch (error: RuntimeException) {
                lastError = IOException("AirPlay control probe failed", error)
            }
            if (closed) return null
            if (attempt + 1 < MAX_PROBE_ATTEMPTS) {
                Thread.sleep(PROBE_RETRY_DELAY_MILLIS)
            }
        }
        return CarPlayBonjourEvent.Probed(
            endpoint = endpoint,
            attempts = MAX_PROBE_ATTEMPTS,
            statusLine = null,
            error = lastError,
        )
    }

    private fun probeOnce(address: InetAddress, port: Int): String {
        val socket = Socket()
        synchronized(lifecycleLock) {
            check(!closed) { "CarPlayBonjour is closed" }
            check(activeSocket == null) { "A previous CarPlay Bonjour socket is not retired" }
            activeSocket = socket
        }
        try {
            localAdvertisedAddress
                ?.takeIf { canBindBonjourProbeTo(it, address) }
                ?.let { socket.bind(InetSocketAddress(it, 0)) }
            socket.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MILLIS)
            socket.soTimeout = READ_TIMEOUT_MILLIS
            val host = address.hostAddress
                ?: throw IOException("AirPlay control service has no host address")
            val request = CarPlayBonjourProtocol.connectProbeRequest(
                host = host,
                port = port,
                sourceVersion = config.sourceVersion,
                deviceId = config.deviceId,
            )
            val output = socket.getOutputStream()
            output.write(request.toByteArray(StandardCharsets.US_ASCII))
            output.flush()
            val reader = BufferedReader(
                InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII),
            )
            return reader.readLine()
                ?: throw IOException("AirPlay control probe returned no status line")
        } finally {
            try {
                socket.close()
                synchronized(lifecycleLock) {
                    if (ownsBonjourSocket(activeSocket, socket)) {
                        activeSocket = null
                        socketRetirementFailure = null
                    }
                }
            } catch (error: Exception) {
                synchronized(lifecycleLock) {
                    socketRetirementFailure = retainBonjourSocketCloseFailure(
                        activeSocket,
                        socket,
                        socketRetirementFailure,
                        error,
                    )
                }
                throw error
            }
        }
    }

    private fun emit(event: CarPlayBonjourEvent) {
        if (closed) return
        try {
            onEvent(event)
        } catch (error: RuntimeException) {
            Log.w(TAG, "CarPlay Bonjour event callback failed", error)
        }
    }

    private fun decodeTxtValue(value: ByteArray): String =
        String(value, StandardCharsets.UTF_8).trimEnd('\u0000')

    private fun joinWorkerStrict(worker: Thread) {
        proveCarPlayBonjourWorkerRetired(worker, JOIN_TIMEOUT_MILLIS)
    }

    private fun awaitNsdRetirement() {
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(NSD_STOP_TIMEOUT_MILLIS)
        synchronized(lifecycleLock) {
            while (registrationRequested || discoveryRequested) {
                registrationStopFailure?.let { throw it }
                discoveryStopFailure?.let { throw it }
                val remainingNanos = deadlineNanos - System.nanoTime()
                if (remainingNanos <= 0L) {
                    throw IOException("Timed out waiting for CarPlay Bonjour NSD retirement")
                }
                try {
                    lifecycleLock.wait(
                        remainingNanos / 1_000_000L,
                        (remainingNanos % 1_000_000L).toInt(),
                    )
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("Interrupted while waiting for CarPlay Bonjour NSD retirement", error)
                }
            }
        }
    }

    private fun combineCloseFailure(first: Exception?, next: Exception): Exception {
        if (first == null) return next
        if (first !== next && first.suppressed.size < 8) first.addSuppressed(next)
        return first
    }

    private companion object {
        const val TAG = "xcertplay-bonjour"
        const val WORKER_NAME = "carplay-bonjour"
        const val AIRPLAY_SERVICE_TYPE = "_airplay._tcp"
        const val CARPLAY_CONTROL_SERVICE_TYPE = "_carplay-ctrl._tcp"
        const val WORKER_POLL_MILLIS = 500L
        const val RESOLVE_ATTEMPTS = 3
        const val RESOLVE_TIMEOUT_MILLIS = 10_000L
        const val RESOLVE_RETRY_DELAY_MILLIS = 250L
        const val MAX_PROBE_ATTEMPTS = 7
        const val PROBE_RETRY_DELAY_MILLIS = 1_500L
        const val CONNECT_TIMEOUT_MILLIS = 3_000
        const val READ_TIMEOUT_MILLIS = 3_000
        const val JOIN_TIMEOUT_MILLIS = 2_000L
        const val NSD_STOP_TIMEOUT_MILLIS = 2_000L
        const val DEFERRED_RETRY_INITIAL_MILLIS = 25L
        const val DEFERRED_RETRY_MAX_MILLIS = 1_000L
        const val FAILURE_NONE = -1
    }
}

internal fun proveCarPlayBonjourWorkerRetired(worker: Thread, timeoutMillis: Long) {
    require(timeoutMillis > 0) { "timeoutMillis must be positive" }
    if (worker === Thread.currentThread()) {
        throw IOException("CarPlay Bonjour worker cannot prove its own retirement")
    }
    try {
        if (worker.state != Thread.State.NEW) worker.join(timeoutMillis)
    } catch (error: InterruptedException) {
        Thread.currentThread().interrupt()
        throw IOException("Interrupted while retiring the CarPlay Bonjour worker", error)
    }
    if (worker.isAlive) throw IOException("CarPlay Bonjour worker did not stop before the deadline")
}

internal fun startPublishedWorker(worker: Thread, publish: (Thread) -> Unit) {
    publish(worker)
    worker.start()
}

internal fun ownsBonjourSocket(active: Socket?, candidate: Socket): Boolean = active === candidate

internal fun retainBonjourSocketCloseFailure(
    active: Socket?,
    candidate: Socket,
    retained: Exception?,
    failure: Exception,
): Exception? = if (ownsBonjourSocket(active, candidate)) retained ?: failure else retained
