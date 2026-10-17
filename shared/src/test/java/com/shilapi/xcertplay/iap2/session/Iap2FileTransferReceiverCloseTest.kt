package com.shilapi.xcertplay.iap2.session

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Iap2FileTransferReceiverCloseTest {
    @Test
    fun selfWorkerCloseReportsDeferredUntilExternalCoordinatorProvesExit() {
        val selfCloseFailure = AtomicReference<Throwable?>()
        val selfCloseReturned = CountDownLatch(1)
        lateinit var receiver: Iap2FileTransferReceiver
        receiver = Iap2FileTransferReceiver(
            receiveFileTransfer = {
                try {
                    receiver.close()
                } catch (failure: Throwable) {
                    selfCloseFailure.set(failure)
                } finally {
                    selfCloseReturned.countDown()
                }
                null
            },
            sendFileTransfer = { _, _ -> },
            isSessionClosed = { false },
        )

        receiver.start()

        assertTrue(selfCloseReturned.await(2, TimeUnit.SECONDS))
        assertTrue(selfCloseFailure.get() is IOException)
        assertNotNull(selfCloseFailure.get())
        await(3_000) { retired(receiver) }
        receiver.close()
    }

    private fun retired(receiver: Iap2FileTransferReceiver): Boolean =
        Iap2FileTransferReceiver::class.java.getDeclaredField("retired").run {
            isAccessible = true
            getBoolean(receiver)
        }

    private fun await(timeoutMillis: Long, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "condition was not met in ${timeoutMillis}ms" }
            Thread.sleep(10)
        }
    }
}
