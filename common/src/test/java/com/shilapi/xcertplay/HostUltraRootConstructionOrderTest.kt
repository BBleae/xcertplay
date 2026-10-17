package com.shilapi.xcertplay

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the fixed Host composition point and exact sink-taking API. */
class HostUltraRootConstructionOrderTest {
    private fun hostSource(): String =
        File("src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt").readText()
            .substringAfter("private fun startCarPlay(size: DisplaySize)")

    @Test
    fun fixedRootIsConfiguredAfterExactMediaOwnersAndBeforeController() {
        val construction = hostSource()
            .substringBefore("private fun attachActivityCallbacks(")
        val steps = listOf(
            "renderer = createMediaSink(",
            "val media = createMediaEngine(renderer, fallbackRuntime)",
            "mediaOwner = media",
            "HostUltraCompositionInputs(",
            "val lifecycle = AuthorizedUltraProductSession()",
            "authorizationOwner = ControllerUltraAuthorizationRuntime(",
            "CarPlayController(",
            "controller = next",
            "startThenPublishBackgroundSession(",
        )
        var previous = -1
        steps.forEach { step ->
            val index = construction.indexOf(step)
            assertTrue("Missing Host construction step: $step", index >= 0)
            assertTrue("Host construction step out of order: $step", index > previous)
            previous = index
        }
        val compositionInputs = construction.indexOf("HostUltraCompositionInputs(")
        val authorizationGuard = construction.indexOf(
            "if (ultraPlan.createAuthorization)", compositionInputs + 1,
        )
        assertTrue(
            "Ultra opt-out must retire the Debug root before constructing an ordinary Controller",
            compositionInputs >= 0 && authorizationGuard > compositionInputs &&
                authorizationGuard < construction.indexOf("CarPlayController("),
        )
    }

    @Test
    fun externalOwnerWaitIsGatedAndSinkOwnsItsFinalRetirement() {
        val source = hostSource()
        assertTrue(source.contains("shouldAwaitTrustedExternalOutput("))
        assertTrue(source.contains("owner.awaitReady(staged, configTicket)"))
        assertTrue(source.contains("externalClusterRoutes = externalOutputOwner?.routeFactory"))
        val sinkLifecycle = File("src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt")
            .readText().substringAfter("internal class HostSinkOutputLifecycle(")
            .substringBefore("private class PendingHostExternalBootstrap(")
        assertTrue(sinkLifecycle.contains("owner.closeAndRequireRetired()"))
        assertTrue(File("src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt")
            .readText().contains("onClosed = outputLifecycle::close"))
        assertTrue(source.contains("cancelPendingHostExternalBootstrap()"))
    }

    @Test
    fun onlyIndependentFixedTrustProviderCanDelayUltraExternalBootstrap() {
        val denied = ExternalPhysicalDisplayTrustProvider.DENY_ALL
        val candidate = ExternalPhysicalDisplayTrustProvider { null }
        assertFalse(shouldAwaitTrustedExternalOutput(true, true, true, denied))
        assertFalse(shouldAwaitTrustedExternalOutput(false, true, true, candidate))
        assertFalse(shouldAwaitTrustedExternalOutput(true, false, true, candidate))
        assertFalse(shouldAwaitTrustedExternalOutput(true, true, false, candidate))
        assertTrue(shouldAwaitTrustedExternalOutput(true, true, true, candidate))
    }

    @Test
    fun activityDestroyDrivesEarlierCancelledConstructionFromProcessBarrier() {
        val onDestroy = File("src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt")
            .readText().substringAfter("override fun onDestroy() {")
            .substringBefore("super.onDestroy()")
        assertTrue(onDestroy.contains("cancelPendingHostExternalBootstrap()"))
        assertTrue(onDestroy.contains(
            "HostTeardownProcessBarrier.coordinator.state() != HostTeardownBarrierState.CLEAR",
        ))
        assertTrue(onDestroy.contains("ProcessHostConstructionRetirer.request()"))
    }

    @Test
    fun failedRootConstructionRetainsBothMediaOwnersWithoutPublishingSession() {
        val construction = hostSource()
        val failureCleanup = construction.substringAfter("} catch (failure: Throwable) {")
            .substringBefore("val renderer = checkNotNull(rendererOwner)")
        val cleanupSteps = listOf(
            "mediaOwner?.let { resource ->",
            "authorizationOwner?.let { resource ->",
            "rendererOwner?.let { resource ->",
            "retireHostConstructionFailure(cleanup, failure, controllerGeneration)",
            "return",
        )
        var previous = -1
        cleanupSteps.forEach { step ->
            val index = failureCleanup.indexOf(step)
            assertTrue("Missing construction cleanup step: $step", index >= 0)
            assertTrue("Construction cleanup step out of order: $step", index > previous)
            previous = index
        }
        assertTrue(
            "A failed construction must return before Host session publication",
            construction.indexOf("controller = next") >
                construction.indexOf("} catch (failure: Throwable) {"),
        )
    }
}
