package com.shilapi.xcertplay.bench

import com.shilapi.xcertplay.airplay.BplistCodec
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfilePackageFiles
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BenchUltraProfileImportMutationFenceTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun activePointerSwitchRevokesBeforePublicationAndPendingCaptureCannotBecomeLive() {
        val root = newRoot()
        val store = BenchUltraProfileImportStore(root)
        val observed = mutableListOf<BenchUltraProfileImportMutationFence.State>()
        val watcher = BenchUltraProfileImportMutationFence.observe(root,
            object : BenchUltraProfileImportMutationFence.Observer {
                override fun beforePublication(
                    state: BenchUltraProfileImportMutationFence.State,
                ): Boolean {
                    observed += state
                    if (observed.size == 1) assertNull(store.loadActive())
                    return true
                }

                override fun afterPublication(state: BenchUltraProfileImportMutationFence.State) {
                    observed += state
                }
            }
        )
        try {
            val generation = store.stageBase(archive(1).inputStream(), pin().inputStream(),
                "vehicle-1")
            assertEquals(2, observed.size)
            assertEquals(1, observed[0].pendingMutations)
            assertEquals(0, observed[1].pendingMutations)
            assertEquals(generation, store.loadActive()?.generation)
            val firstRevision = observed.last().revision

            val secondWatcher = BenchUltraProfileImportMutationFence.observe(root,
                object : BenchUltraProfileImportMutationFence.Observer {
                    override fun beforePublication(
                        state: BenchUltraProfileImportMutationFence.State,
                    ): Boolean {
                    assertEquals(generation, store.loadActive()?.generation)
                    assertTrue(state.revision > firstRevision)
                        return true
                    }

                    override fun afterPublication(
                        state: BenchUltraProfileImportMutationFence.State,
                    ) = Unit
                }
            )
            val second = try {
                store.stageBase(archive(2).inputStream(), pin().inputStream(), "vehicle-2")
            } finally {
                secondWatcher.close()
            }
            assertNotEquals(generation, second)
            assertEquals(second, store.loadActive()?.generation)
            assertTrue(BenchUltraProfileImportMutationFence.state(root).revision > firstRevision)
        } finally {
            watcher.close()
        }
    }

    @Test
    fun detachedSignatureReplacementInSameGenerationRevokesBeforeAtomicFileSwap() {
        val root = newRoot()
        val store = BenchUltraProfileImportStore(root)
        val generation = store.stageBase(archive(1).inputStream(), pin().inputStream(),
            "vehicle-1")
        store.stageDetachedSignature(byteArrayOf(1, 2, 3).inputStream())
        val before = BenchUltraProfileImportMutationFence.state(root)
        var sawOldSignatureBeforePublication = false
        val watcher = BenchUltraProfileImportMutationFence.observe(root,
            object : BenchUltraProfileImportMutationFence.Observer {
                override fun beforePublication(
                    state: BenchUltraProfileImportMutationFence.State,
                ): Boolean {
                // Observer is outside the Store monitor: another thread can read the old active
                // snapshot while this callback blocks publication.
                val readerDone = CountDownLatch(1)
                Thread {
                    store.loadActive()
                    readerDone.countDown()
                }.start()
                assertTrue(readerDone.await(2, TimeUnit.SECONDS))
                assertEquals(generation, store.loadActive()?.generation)
                assertArrayEquals(byteArrayOf(1, 2, 3), store.loadActive()?.detachedSignature)
                sawOldSignatureBeforePublication = true
                    return true
                }

                override fun afterPublication(
                    state: BenchUltraProfileImportMutationFence.State,
                ) = Unit
            }
        )
        try {
            store.stageDetachedSignature(byteArrayOf(4, 5, 6).inputStream())
        } finally {
            watcher.close()
        }

        assertTrue(sawOldSignatureBeforePublication)
        assertEquals(generation, store.loadActive()?.generation)
        assertArrayEquals(byteArrayOf(4, 5, 6), store.loadActive()?.detachedSignature)
        assertEquals(before.revision + 2L,
            BenchUltraProfileImportMutationFence.state(root).revision)
        assertEquals(0, BenchUltraProfileImportMutationFence.state(root).pendingMutations)
    }

    @Test
    fun refusedCoverKeepsOldSignatureAndSameGenerationRetryPublishesOnlyAfterAck() {
        val root = newRoot()
        val store = BenchUltraProfileImportStore(root)
        val generation = store.stageBase(archive(1).inputStream(), pin().inputStream(),
            "vehicle-1")
        store.stageDetachedSignature(byteArrayOf(1, 2, 3).inputStream())
        val allowCover = AtomicBoolean(false)
        val watcher = BenchUltraProfileImportMutationFence.observe(root,
            object : BenchUltraProfileImportMutationFence.Observer {
                override fun beforePublication(
                    state: BenchUltraProfileImportMutationFence.State,
                ): Boolean = allowCover.get()

                override fun afterPublication(
                    state: BenchUltraProfileImportMutationFence.State,
                ) = Unit
            },
        )
        try {
            assertThrows(IllegalStateException::class.java) {
                store.stageDetachedSignature(byteArrayOf(4, 5, 6).inputStream())
            }
            assertEquals(generation, store.loadActive()?.generation)
            assertArrayEquals(byteArrayOf(1, 2, 3), store.loadActive()?.detachedSignature)
            assertEquals(1, BenchUltraProfileImportMutationFence.state(root).pendingMutations)

            allowCover.set(true)
            store.stageDetachedSignature(byteArrayOf(4, 5, 6).inputStream())
            assertEquals(generation, store.loadActive()?.generation)
            assertArrayEquals(byteArrayOf(4, 5, 6), store.loadActive()?.detachedSignature)
            assertEquals(0, BenchUltraProfileImportMutationFence.state(root).pendingMutations)
        } finally {
            watcher.close()
        }
    }

    @Test
    fun repeatedRefusedCoverDoesNotAccumulateUnpublishedProfileCopies() {
        val root = newRoot()
        val store = BenchUltraProfileImportStore(root)
        val oldGeneration = store.stageBase(archive(1).inputStream(), pin().inputStream(),
            "vehicle-1")
        val originalCopies = profileDirectoryCount(root)
        val allowCover = AtomicBoolean(false)
        val watcher = BenchUltraProfileImportMutationFence.observe(root,
            object : BenchUltraProfileImportMutationFence.Observer {
                override fun beforePublication(
                    state: BenchUltraProfileImportMutationFence.State,
                ): Boolean = allowCover.get()

                override fun afterPublication(
                    state: BenchUltraProfileImportMutationFence.State,
                ) = Unit
            },
        )
        try {
            repeat(3) {
                assertThrows(IllegalStateException::class.java) {
                    store.stageBase(archive(2).inputStream(), pin().inputStream(), "vehicle-2")
                }
                assertEquals(oldGeneration, store.loadActive()?.generation)
                assertEquals(originalCopies, profileDirectoryCount(root))
            }
            allowCover.set(true)
            val next = store.stageBase(archive(2).inputStream(), pin().inputStream(), "vehicle-2")
            assertNotEquals(oldGeneration, next)
            assertEquals(originalCopies + 1L, profileDirectoryCount(root))
            assertEquals(0, BenchUltraProfileImportMutationFence.state(root).pendingMutations)
        } finally {
            watcher.close()
        }
    }

    private fun newRoot(): Path = temporaryFolder.newFolder().toPath().resolve("bench-import")

    private fun profileDirectoryCount(root: Path): Long = Files.list(root).use { files ->
        files.filter { it.fileName.toString().startsWith("profile-") && Files.isDirectory(it) }
            .count()
    }

    private fun pin(): ByteArray = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair().public.encoded

    private fun archive(version: Int): ByteArray = ByteArrayOutputStream().also { bytes ->
        val entries = listOf(
            UltraProfilePackageFiles.CARPLAY_PHONE_ASSETS_DIRECTORY to byteArrayOf(),
            UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON to "{}".encodeToByteArray(),
            UltraProfilePackageFiles.CARPLAY_PHONE_THEME_JSON to "{}".encodeToByteArray(),
            UltraProfilePackageFiles.CARPLAY_PHONE_INFO_PLIST to
                BplistCodec.encode(mapOf("Bundle" to "Phone")),
            "CarPlayPhone.assets/layout.bin" to "layout-$version".encodeToByteArray(),
            UltraProfilePackageFiles.CARPLAY_ACCESSORY_ASSETS to
                "accessory-$version".encodeToByteArray(),
            UltraProfilePackageFiles.CERTIFICATE_CHAIN to "certificate".encodeToByteArray(),
            UltraProfilePackageFiles.ACCESSORY_ASSETS_SHA256 to "opaque".encodeToByteArray(),
            UltraProfilePackageFiles.ACCESSORY_ADDITIONS to "additions".encodeToByteArray(),
            UltraProfilePackageFiles.INFO_PLIST to BplistCodec.encode(mapOf("Version" to version)),
        )
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
    }.toByteArray()
}
