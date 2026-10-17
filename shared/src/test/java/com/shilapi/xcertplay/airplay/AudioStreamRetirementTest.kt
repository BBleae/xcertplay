package com.shilapi.xcertplay.airplay

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AudioStreamRetirementTest {
    private val key = ByteArray(32) { (it + 7).toByte() }
    private val ipv6Loopback = InetAddress.getByName("::1")

    @Test
    fun closeRetainsABlockedWorkerUntilAnExactRetryProvesRetirement() {
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val stream = AudioStream(key, retirementJoinTimeoutMillis = 50)
        val (dataPort) = stream.listen(
            object : AudioStream.Listener {
                override fun onPacket(
                    wire: ByteArray,
                    rtp: ByteArray?,
                    sample: Int?,
                    error: Throwable?,
                ) {
                    callbackEntered.countDown()
                    awaitUninterruptibly(releaseCallback)
                }
            },
        )

        sendShortPacket(dataPort)
        assertTrue(callbackEntered.await(2, TimeUnit.SECONDS))
        assertTrue(closeFailure(stream) is IOException)
        assertTrue(closeFailure(stream) is IOException)

        releaseCallback.countDown()
        stream.close()
        stream.close()
    }

    @Test
    fun workerCannotClaimItsOwnRetirement() {
        val failure = AtomicReference<Throwable?>()
        val callbackDone = CountDownLatch(1)
        val stream = AudioStream(key, retirementJoinTimeoutMillis = 100)
        val (dataPort) = stream.listen(
            object : AudioStream.Listener {
                override fun onPacket(
                    wire: ByteArray,
                    rtp: ByteArray?,
                    sample: Int?,
                    error: Throwable?,
                ) {
                    failure.set(closeFailure(stream))
                    callbackDone.countDown()
                }
            },
        )

        sendShortPacket(dataPort)
        assertTrue(callbackDone.await(2, TimeUnit.SECONDS))
        assertTrue(failure.get() is IOException)
        stream.close()
    }

    @Test
    fun listenIsOneShotEvenAfterRetirement() {
        val stream = AudioStream(key, retirementJoinTimeoutMillis = 100)
        stream.listen(object : AudioStream.Listener {})
        stream.close()
        try {
            stream.listen(object : AudioStream.Listener {})
            fail("a retired audio stream must not restart")
        } catch (_: IllegalStateException) {
            // Expected: the stream owns only its original endpoint and worker generation.
        }
    }

    private fun sendShortPacket(port: Int) {
        DatagramSocket().use { sender ->
            val payload = byteArrayOf(1)
            sender.send(DatagramPacket(payload, payload.size, ipv6Loopback, port))
        }
    }

    private fun closeFailure(stream: AudioStream): Throwable? = try {
        stream.close()
        null
    } catch (failure: Throwable) {
        failure
    }

    private fun awaitUninterruptibly(latch: CountDownLatch) {
        var interrupted = false
        while (latch.count != 0L) {
            try {
                latch.await()
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }
}
