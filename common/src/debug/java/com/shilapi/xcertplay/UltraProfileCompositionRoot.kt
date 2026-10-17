package com.shilapi.xcertplay

import android.content.Context
import android.content.SharedPreferences
import com.shilapi.xcertplay.bench.BenchUltraProfileDeploymentProvider
import com.shilapi.xcertplay.bench.BenchUltraProfileImportMutationFence
import com.shilapi.xcertplay.bench.BenchUltraProfileImportStore
import java.io.Closeable
import java.lang.ref.WeakReference
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest

/** Debug-only bench composition. User-owned signature acceptance is NOT Apple/OEM authorization. */
internal object UltraProfileCompositionRoot {
    private val manifestIssuer = AuthorizedUltraCompiledRuntimeManifestIssuer(
        MessageDigest.getInstance("SHA-256").digest(
            "xcertplay-debug-user-pinned-bench-policy-v1\u0000".toByteArray(StandardCharsets.US_ASCII),
        ),
    )
    @Volatile private var bench: BenchUltraProfileDeploymentProvider? = null
    private val retiredBenchOwners = ArrayList<BenchUltraProfileDeploymentProvider>()
    private val capturedEpochs = ArrayList<WeakReference<UltraProfilePolicyEpoch>>()
    private val pendingEpochs = LinkedHashSet<UltraProfilePolicyEpoch>()
    private var activeMutationToken: Any? = null
    private var selectionGeneration = 0L
    private var consentSelection: ConsentSelection? = null
    private var selectedImportRoot: Path? = null
    private var importMutationObserver: Closeable? = null
    private var importPublicationTicket: Pair<Path, Closeable>? = null

    private class ConsentSelection(
        val preferences: SharedPreferences,
        val applicationContext: Context,
        val ultraEnabled: Boolean,
        val persistedUltraEnabled: Boolean,
        val benchOptedIn: Boolean,
        val uiSyncOptedIn: Boolean,
    ) {
        fun isCurrent(): Boolean =
            ultraEnabled == persistedUltraEnabled &&
                AirPlayPersistence.peekCarPlayUltraEnabled(applicationContext) ==
                    persistedUltraEnabled &&
                preferences.getBoolean(BENCH_OPT_IN_KEY, false) == benchOptedIn &&
                preferences.getBoolean(UI_SYNC_BENCH_OPT_IN_KEY, false) == uiSyncOptedIn
    }

    /** Called only by Host immediately before constructing one Controller authorization lifecycle. */
    fun configureHost(
        context: Context,
        ultraEnabled: Boolean,
        inputs: HostUltraCompositionInputs,
    ): Boolean {
        val ticket = beginPolicyMutation() ?: return false
        return try {
            val preferences = context.getSharedPreferences(BENCH_PREFERENCES, Context.MODE_PRIVATE)
            val persistedUltraEnabled = AirPlayPersistence.peekCarPlayUltraEnabled(context)
            val optedIn = preferences.getBoolean(BENCH_OPT_IN_KEY, false)
            val uiSyncOptedIn = preferences.getBoolean(UI_SYNC_BENCH_OPT_IN_KEY, false)
            val importRoot = context.applicationContext.noBackupFilesDir.toPath()
                .resolve(BenchUltraProfileImportStore.DIRECTORY).toAbsolutePath().normalize()
            val oldOwners = synchronized(this) {
                importMutationObserver?.close()
                importMutationObserver = null
                consentSelection = ConsentSelection(
                    preferences, context.applicationContext, ultraEnabled,
                    persistedUltraEnabled, optedIn, uiSyncOptedIn,
                )
                selectedImportRoot = importRoot
                importMutationObserver = BenchUltraProfileImportMutationFence.observe(importRoot,
                    object : BenchUltraProfileImportMutationFence.Observer {
                        override fun beforePublication(
                            state: BenchUltraProfileImportMutationFence.State,
                        ): Boolean {
                            if (synchronized(this@UltraProfileCompositionRoot) {
                                selectedImportRoot != importRoot
                            }) return false
                            val oldTicket = beginPolicyMutation() ?: return false
                            val retained = synchronized(this@UltraProfileCompositionRoot) {
                                if (selectedImportRoot == importRoot &&
                                    importPublicationTicket == null
                                ) {
                                    importPublicationTicket = importRoot to oldTicket
                                    true
                                } else false
                            }
                            if (!retained) oldTicket.close()
                            return retained
                        }

                        override fun afterPublication(
                            state: BenchUltraProfileImportMutationFence.State,
                        ) {
                            val oldTicket = synchronized(this@UltraProfileCompositionRoot) {
                                importPublicationTicket?.takeIf { it.first == importRoot }
                                    ?.also { importPublicationTicket = null }?.second
                            }
                            oldTicket?.close()
                        }
                    },
                )
                bench?.let(retiredBenchOwners::add)
                bench = null
                retiredBenchOwners.toList()
            }
            // A provider close may wait for an old Host callback. Never hold the root monitor
            // while retiring it; captured epochs remain denied by this exact mutation ticket.
            val retired = oldOwners.filter { runCatching { it.close() }.isSuccess }
            val cleared = synchronized(this) {
                retiredBenchOwners.removeAll { candidate -> retired.any { it === candidate } }
                retiredBenchOwners.isEmpty()
            }
            if (!cleared) false else {
                val next = if (debugBenchSelected(ultraEnabled && persistedUltraEnabled,
                        optedIn)) {
                    BenchUltraProfileDeploymentProvider(
                        context.applicationContext, manifestIssuer, inputs,
                        allowUiSyncBench = uiSyncOptedIn,
                    )
                } else null
                synchronized(this) { bench = next }
                next != null
            }
        } finally {
            ticket.close()
        }
    }

    /**
     * iAP2 identification (`themeAssetsCapable`) is built before any session can prove Ultra
     * readiness. Claiming theme assets while the session then falls back to Classic can make
     * the iPhone record an Ultra opt-out at first pairing, so the Debug bench claims it only
     * when every input of the later activation is already present: Ultra on (persisted), the
     * bench and UI Sync opt-ins, and an active signed import. Activation still verifies all of it.
     */
    fun themeAssetsAdvertisable(context: Context, ultraEnabled: Boolean): Boolean = runCatching {
        val preferences = context.getSharedPreferences(BENCH_PREFERENCES, Context.MODE_PRIVATE)
        ultraEnabled && AirPlayPersistence.peekCarPlayUltraEnabled(context) &&
            preferences.getBoolean(BENCH_OPT_IN_KEY, false) &&
            preferences.getBoolean(UI_SYNC_BENCH_OPT_IN_KEY, false) &&
            BenchUltraProfileImportStore(context.applicationContext.noBackupFilesDir.toPath()
                .resolve(BenchUltraProfileImportStore.DIRECTORY)).hasActiveSignedGeneration()
    }.getOrDefault(false)

    fun deploymentProvider(): UltraProfileDeploymentProvider =
        bench ?: DisabledUltraProfileDeploymentProvider

    fun vehicleIdentityAuthority(): TrustedUltraVehicleIdentityAuthority =
        bench ?: UnavailableTrustedUltraVehicleIdentityAuthority

    /** Captures the complete Debug selection under the same monitor as [configureHost]. */
    @Synchronized
    fun capturePolicyEpoch(): UltraProfilePolicyEpoch {
        val selected = bench
        val generation = selectionGeneration
        val consent = consentSelection
        val importRoot = selectedImportRoot
        val importState = importRoot?.let(BenchUltraProfileImportMutationFence::state)
        return UltraProfilePolicyEpoch(
            vehicleIdentityAuthority = selected ?: UnavailableTrustedUltraVehicleIdentityAuthority,
            deploymentProvider = selected ?: DisabledUltraProfileDeploymentProvider,
            manifestIssuer = manifestIssuer,
            ultraConsentSelected = selected != null && consent?.ultraEnabled == true &&
                consent?.benchOptedIn == true,
            uiSyncConsentSelected = selected != null && consent?.uiSyncOptedIn == true,
            currentSelection = {
                synchronized(this) {
                    generation == selectionGeneration && selected === bench &&
                        activeMutationToken == null && pendingEpochs.isEmpty() &&
                        consent === consentSelection && (consent?.isCurrent() ?: true) &&
                        (importRoot == null ||
                            BenchUltraProfileImportMutationFence.state(importRoot) == importState &&
                            importState?.pendingMutations == 0)
                }
            },
        ).also { capturedEpochs += WeakReference(it) }
    }

    /** A preference writer holds this ticket until `.apply()` has completed. */
    fun beginConsentMutation(): Closeable? = beginPolicyMutation()

    private fun beginPolicyMutation(): Closeable? {
        val token = Any()
        val oldEpochs = synchronized(this) {
            if (activeMutationToken != null) return null
            activeMutationToken = token
            LinkedHashSet<UltraProfilePolicyEpoch>().also { selected ->
                selected += pendingEpochs
                pendingEpochs.clear()
                capturedEpochs.forEach { it.get()?.let(selected::add) }
                capturedEpochs.clear()
            }
        }
        var acknowledged = true
        oldEpochs.forEach { if (!it.markRevoked()) acknowledged = false }
        if (acknowledged) oldEpochs.forEach { if (!it.coverAck()) acknowledged = false }
        if (acknowledged) {
            try {
                oldEpochs.forEach { it.releaseRetirementAfterCover() }
            } catch (_: Exception) {
                acknowledged = false
            }
        }
        if (!acknowledged) {
            synchronized(this) {
                pendingEpochs += oldEpochs
                if (activeMutationToken === token) activeMutationToken = null
            }
            return null
        }
        return Closeable {
            synchronized(this) {
                if (activeMutationToken === token) {
                    selectionGeneration = Math.addExact(selectionGeneration, 1L)
                    activeMutationToken = null
                }
            }
        }
    }

    /** A signed test Profile is not an independent hardware/connector attestation. */
    fun externalPhysicalDisplayTrustProvider(): ExternalPhysicalDisplayTrustProvider =
        ExternalPhysicalDisplayTrustProvider.DENY_ALL

    /** Only the explicit Host DDP probe may keep an unready panel for Debug diagnostics. */
    fun allowDiagnosticPhysicalPanelsWithoutUiSync(): Boolean = true

    internal fun manifestIssuer(): AuthorizedUltraCompiledRuntimeManifestIssuer = manifestIssuer

    fun storeRoot(noBackupFilesDirectory: Path): Path =
        ultraProfileStoreRoot(noBackupFilesDirectory)

    const val BENCH_PREFERENCES = "debug-ultra-bench-v1"
    const val BENCH_OPT_IN_KEY = "caf-only-explicit-opt-in"
    const val UI_SYNC_BENCH_OPT_IN_KEY = "signed-physical-uisync-explicit-opt-in"
}

internal fun debugBenchSelected(ultraEnabled: Boolean, explicitBenchOptIn: Boolean): Boolean =
    ultraEnabled && explicitBenchOptIn
