package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.message.Iap2AppLinksLifecycle
import com.shilapi.xcertplay.iap2.message.Iap2AppLinksMessages
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Iap2AppLinksIconChannelTest {
    private val bundle = "com.example.navigation"

    @Test
    fun identificationDefaultsOffAndAdvertisesIconsOnlyWithExplicitSubscriptionOptIn() {
        val base = Iap2IdentificationConfig(
            name = "bench",
            modelIdentifier = "bench",
            manufacturer = "test",
            serialNumber = "1",
            firmwareVersion = "1",
            hardwareVersion = "1",
            carPlayUsbInterfaceNumber = 0,
        )
        val baseOnly = base.copy(appLinksEnabled = true)
        val withIcons = baseOnly.copy(appLinksIconsEnabled = true)
        listOf(base, baseOnly, withIcons).forEach { config ->
            val identification = Iap2BodyReader.of(Iap2IdentificationClient.identificationInformation(config))
            val sent = identification.u16List(6)
            val received = identification.u16List(7)
            assertEquals(config.appLinksEnabled, Iap2AppLinksMessages.START_ID in sent)
            assertEquals(config.appLinksEnabled, Iap2AppLinksMessages.UPDATE_ID in received)
            assertEquals(config.appLinksIconsEnabled, Iap2AppLinksMessages.ICON_REQUEST_ID in sent)
            assertEquals(config.appLinksIconsEnabled, Iap2AppLinksMessages.ICON_RESPONSE_ID in received)
        }
        assertThrows(IllegalArgumentException::class.java) {
            base.copy(appLinksIconsEnabled = true)
        }
    }

    @Test
    fun authenticatedOneShotRequestDeliversOnlyRequestedBundleOnce() {
        val sent = mutableListOf<Iap2Frame>()
        var authorized = true
        val lifecycle = Iap2AppLinksLifecycle({ sent += it }, { authorized })
        val channel = Iap2AppLinksIconChannel(lifecycle, { sent += it }, { authorized })
        assertFalse(channel.request(setOf(bundle)))
        assertTrue(lifecycle.start(setOf(1)))
        assertTrue(channel.request(setOf(bundle)))
        assertEquals(
            listOf(Iap2AppLinksMessages.START_ID, Iap2AppLinksMessages.ICON_REQUEST_ID),
            sent.map { it.messageId },
        )
        val delivered = mutableListOf<String>()
        assertEquals(
            Iap2AppLinksIconChannel.ReceiveOutcome.STALE,
            channel.receive(response("com.example.other")) { delivered += it.bundleIdentifier },
        )
        assertEquals(
            Iap2AppLinksIconChannel.ReceiveOutcome.DELIVERED,
            channel.receive(response(bundle)) { delivered += it.bundleIdentifier },
        )
        assertEquals(listOf(bundle), delivered)
        assertEquals(
            Iap2AppLinksIconChannel.ReceiveOutcome.UNREQUESTED,
            channel.receive(response(bundle)) { delivered += it.bundleIdentifier },
        )
        assertFalse(channel.request(setOf("com.example.second")))
        authorized = false
        assertNull(channel.activeRequestGeneration())
    }

    @Test
    fun stopStartAbaCannotReviveCapturedResponseOrReuseCsm() {
        val lifecycle = Iap2AppLinksLifecycle({}, { true })
        val channel = Iap2AppLinksIconChannel(lifecycle, {}, { true })
        assertTrue(lifecycle.start(setOf(1)))
        assertTrue(channel.request(setOf(bundle)))
        val oldGeneration = requireNotNull(channel.activeRequestGeneration())
        assertTrue(lifecycle.stop())
        channel.onStopped()
        assertTrue(lifecycle.start(setOf(1)))
        assertFalse(channel.deliver(Iap2AppLinksMessages.iconResponse(response(bundle)), oldGeneration) {
            throw AssertionError("old icon response reached a new subscription")
        })
        assertFalse(channel.request(setOf(bundle)))
        assertEquals(
            Iap2AppLinksIconChannel.ReceiveOutcome.UNREQUESTED,
            channel.receive(response(bundle)) { throw AssertionError("old icon response delivered") },
        )
    }

    @Test
    fun malformedResponseIsRejectedWithoutConsumingLegitimatePendingBundle() {
        val lifecycle = Iap2AppLinksLifecycle({}, { true })
        val channel = Iap2AppLinksIconChannel(lifecycle, {}, { true })
        assertTrue(lifecycle.start(setOf(1)))
        assertTrue(channel.request(setOf(bundle)))
        val malformed = Iap2Messages.buildRaw(Iap2AppLinksMessages.ICON_RESPONSE_ID) {
            string(0, bundle)
            u8(1, 1)
            // status=present requires both the transfer buffer ID and hash.
        }
        assertEquals(
            Iap2AppLinksIconChannel.ReceiveOutcome.MALFORMED,
            channel.receive(malformed) { throw AssertionError("malformed AD04 delivered") },
        )
        assertEquals(
            Iap2AppLinksIconChannel.ReceiveOutcome.DELIVERED,
            channel.receive(response(bundle)) {},
        )
    }

    @Test
    fun uncertainAd03SendPermanentlyConsumesThisCsmAndIgnoresResponse() {
        val lifecycle = Iap2AppLinksLifecycle({}, { true })
        var sends = 0
        val channel = Iap2AppLinksIconChannel(lifecycle, {
            sends++
            throw IllegalStateException("write timed out after possible partial send")
        }, { true })
        assertTrue(lifecycle.start(setOf(1)))
        assertFalse(channel.request(setOf(bundle)))
        assertFalse(channel.request(setOf(bundle)))
        assertEquals(1, sends)
        assertEquals(
            Iap2AppLinksIconChannel.ReceiveOutcome.UNREQUESTED,
            channel.receive(response(bundle)) { throw AssertionError("uncertain AD03 response delivered") },
        )
    }

    @Test
    fun oldCsmCannotDeliverIntoReplacementIconOwner() {
        val oldLifecycle = Iap2AppLinksLifecycle({}, { true })
        val oldChannel = Iap2AppLinksIconChannel(oldLifecycle, {}, { true })
        assertTrue(oldLifecycle.start(setOf(1)))
        assertTrue(oldChannel.request(setOf(bundle)))
        val oldGeneration = requireNotNull(oldChannel.activeRequestGeneration())

        val replacementLifecycle = Iap2AppLinksLifecycle({}, { true })
        val replacementChannel = Iap2AppLinksIconChannel(replacementLifecycle, {}, { true })
        assertTrue(replacementLifecycle.start(setOf(1)))
        assertTrue(replacementChannel.request(setOf(bundle)))
        oldChannel.close()
        oldLifecycle.close()

        assertFalse(oldChannel.deliver(Iap2AppLinksMessages.iconResponse(response(bundle)), oldGeneration) {
            throw AssertionError("old CSM delivered into replacement")
        })
        assertEquals(
            Iap2AppLinksIconChannel.ReceiveOutcome.UNREQUESTED,
            oldChannel.receive(response(bundle)) { throw AssertionError("old CSM callback") },
        )
        val delivered = mutableListOf<String>()
        assertEquals(
            Iap2AppLinksIconChannel.ReceiveOutcome.DELIVERED,
            replacementChannel.receive(response(bundle)) { delivered += it.bundleIdentifier },
        )
        assertEquals(listOf(bundle), delivered)
    }

    @Test
    fun closedOrDeauthorizedChannelNeverSendsOrDelivers() {
        var authorized = true
        val lifecycle = Iap2AppLinksLifecycle({}, { authorized })
        val sent = mutableListOf<Iap2Frame>()
        val channel = Iap2AppLinksIconChannel(lifecycle, { sent += it }, { authorized })
        assertTrue(lifecycle.start(setOf(1)))
        authorized = false
        assertFalse(channel.request(setOf(bundle)))
        authorized = true
        assertTrue(channel.request(setOf(bundle)))
        val generation = requireNotNull(channel.activeRequestGeneration())
        authorized = false
        assertFalse(channel.deliver(Iap2AppLinksMessages.iconResponse(response(bundle)), generation) {
            throw AssertionError("deauthorized callback delivered")
        })
        channel.close()
        authorized = true
        assertFalse(channel.request(setOf(bundle)))
        assertNull(channel.activeRequestGeneration())
        assertEquals(1, sent.size)
    }

    @Test
    fun crossSubfeatureBundleAmbiguityIsRejectedBeforeSend() {
        val lifecycle = Iap2AppLinksLifecycle({}, { true })
        val channel = Iap2AppLinksIconChannel(lifecycle, {}, { true })
        assertTrue(lifecycle.start(setOf(1)))
        assertThrows(IllegalArgumentException::class.java) {
            channel.request(setOf(bundle), setOf(bundle))
        }
        assertTrue(channel.request(setOf(bundle)))
    }

    private fun response(identifier: String): Iap2Frame =
        Iap2Messages.buildRaw(Iap2AppLinksMessages.ICON_RESPONSE_ID) {
            string(0, identifier)
            u8(1, 0)
        }
}
