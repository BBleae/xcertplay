package com.shilapi.xcertplay

import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HostUpdateDataQuarantineWiringTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun ordinaryCarPlayNeverCreatesAQuarantineRoot() {
        val missingParent = temporaryFolder.root.toPath().resolve("not-created").toFile()
        var failures = 0
        assertNull(acquireHostUpdateDataQuarantine(false, missingParent) { failures++ })
        assertFalse(missingParent.exists())
        assertEquals(0, failures)
    }

    @Test
    fun explicitOptInCreatesBoundedRootAndRetiresItWhenConstructionFails() {
        val noBackup = temporaryFolder.newFolder("no-backup")
        val acquired = checkNotNull(acquireHostUpdateDataQuarantine(true, noBackup) {
            throw AssertionError("Unexpected root creation failure", it)
        })
        val root = noBackup.toPath().resolve("carplay-update-data-quarantine-v1")
        assertTrue(Files.isDirectory(root))
        assertFalse(Files.isSymbolicLink(root))
        assertNotNull(acquired.overlayFactory)
        val first = checkNotNull(acquired.owner.tryReserveSession())
        val second = checkNotNull(acquired.owner.tryReserveSession())
        assertNull(acquired.owner.tryReserveSession())
        first.close()
        second.close()

        val cleanup = HostConstructionCleanup(
            listOf(HostConstructionCleanupStage("UpdateData root", acquired::close)),
        )
        cleanup.close()
        assertNull(cleanup.pendingStageName())
        assertTrue(acquired.owner.awaitDrained(0))

        // Failure cleanup releases process ownership without deleting any quarantine entries.
        val reopened = checkNotNull(acquireHostUpdateDataQuarantine(true, noBackup) {
            throw AssertionError("Unexpected retry failure", it)
        })
        reopened.close()
        assertTrue(Files.isDirectory(root))
    }

    @Test
    fun ownershipTransfersOnlyAfterControllerConstructionSucceeds() {
        val noBackup = temporaryFolder.newFolder("no-backup")
        val acquired = checkNotNull(acquireHostUpdateDataQuarantine(true, noBackup) {
            throw AssertionError("Unexpected root creation failure", it)
        })
        acquired.transferToController()
        acquired.close() // Host no longer retires the borrowed Controller owner.
        var unavailable: Exception? = null
        assertNull(acquireHostUpdateDataQuarantine(true, noBackup) { unavailable = it })
        assertNotNull(unavailable)
        assertEquals(0, acquired.owner.activeTransferCount)
        retireHostUpdateDataQuarantineRoot(acquired.owner)
        val reopened = checkNotNull(acquireHostUpdateDataQuarantine(true, noBackup) {
            throw AssertionError("Controller retirement did not release the exact root", it)
        })
        reopened.close()
    }

    @Test
    fun symlinkRootFailsClosedAndFallsBackToOrdinaryCarPlay() {
        val noBackup = temporaryFolder.newFolder("no-backup")
        val target = temporaryFolder.newFolder("target")
        Files.createSymbolicLink(
            noBackup.toPath().resolve("carplay-update-data-quarantine-v1"),
            target.toPath(),
        )
        var failure: Exception? = null
        assertNull(acquireHostUpdateDataQuarantine(true, noBackup) { failure = it })
        assertTrue(failure is IOException)
        assertTrue(Files.isSymbolicLink(noBackup.toPath().resolve("carplay-update-data-quarantine-v1")))
    }

    @Test
    fun symlinkNoBackupDirectoryCannotRedirectTheQuarantineRoot() {
        val target = temporaryFolder.newFolder("outside")
        val link = temporaryFolder.root.toPath().resolve("no-backup-link")
        Files.createSymbolicLink(link, target.toPath())
        var failure: Exception? = null
        assertNull(acquireHostUpdateDataQuarantine(true, link.toFile()) { failure = it })
        assertTrue(failure is IOException)
        assertFalse(Files.exists(target.toPath().resolve("carplay-update-data-quarantine-v1")))
    }

    @Test
    fun explicitExitCanTerminateOnlyAfterTheProcessBarrierIsClear() {
        assertTrue(canCompleteHostTermination(HostTeardownDrainResult.Clear))
        assertFalse(canCompleteHostTermination(HostTeardownDrainResult.InFlight))
        assertFalse(
            canCompleteHostTermination(
                HostTeardownDrainResult.Failed(
                    HostTeardownOutcome.Failed(HostTeardownStage.CONTROLLER_AWAIT),
                ),
            ),
        )

        val coordinator = HostTeardownCoordinator()
        val exactOwner = Any()
        var released = false
        coordinator.submit(
            HostTeardownActions(
                controllerIdentity = exactOwner,
                sinkIdentity = null,
                requestControllerClose = {},
                awaitControllerClosed = { released },
                closeMediaSink = null,
            ),
        )
        val failed = drainHostTeardowns(coordinator)
        assertFalse(canCompleteHostTermination(failed))
        assertEquals(1, coordinator.pendingCount())
        released = true
        assertSame(HostTeardownDrainResult.Clear, drainHostTeardowns(coordinator))
        assertEquals(0, coordinator.pendingCount())
    }
}
