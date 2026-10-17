package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.AirPlayUiSyncInfo
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncChannelDescriptor
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncMode
import com.shilapi.xcertplay.airplay.rcs.uisync.RendererReadinessReceipt
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncBindingFingerprint
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncBoundedPresentationExecutor
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncCompleteRendererBindingContext
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncCompleteRendererCapability
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncCompleteRendererFactoryLease
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncCompleteRendererSessionBindingContext
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncCompleteRendererSessionLease
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncDisplayRuntimeSpec
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRendererScreenCandidate
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRendererScreenContext
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRendererIngressSpec
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRendererStaticPlan
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRuntimeAuthorizationExpectation
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRuntimeProvisioning
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncSchemaFingerprint
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncSemanticThemeProgramFingerprint
import com.shilapi.xcertplay.mfi.MfiAuthenticationIdentity
import com.shilapi.xcertplay.mfi.MfiAuthenticationReceipt
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphOutput
import com.shilapi.xcertplay.vehicle.ultra.profile.VerifiedUltraProfile
import java.io.Closeable
import java.util.ArrayDeque
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicReference

/**
 * Test-source only: static Profile authorization through the real issuer/coordinator.
 * No Host physical output batch is supplied, so this fixture never proves active UI Sync.
 */
internal class AuthorizedUltraDisplayLeaseTestFixture private constructor(
    val runtimeLease: AuthorizedUltraProfileProvisioning,
    val schemaFingerprint: UiSyncSchemaFingerprint,
    val bindingFingerprint: UiSyncBindingFingerprint,
    private val coordinator: UltraProfileActivationCoordinator,
    private val parentLease: AuthorizedUltraProfileLease,
    private val backendLease: TestLease,
    private val revokeReceipt: () -> Unit,
) : Closeable {
    fun revoke() = revokeReceipt()

    /** Test-only normal coordinator PROBE_LEASE, with a one-shot external getter hook. */
    fun setNextParentProbeHook(hook: () -> Unit) = backendLease.setNextIsLiveHook(hook)

    fun probeParentLease(): Boolean = parentLease.isLive

    fun openSecondRuntime(): AuthorizedUltraProfileProvisioning =
        (parentLease.openRuntime() as UltraProfileProvisioningOpenResult.Opened).runtimeLease

    override fun close() = coordinator.close()

    internal companion object {
        fun open(
            profile: VerifiedUltraProfile,
            authenticationIdentity: MfiAuthenticationIdentity,
            outputs: List<UltraDisplayGraphOutput>,
            assignments: List<CompiledUiSyncDisplayAssignment>,
        ): AuthorizedUltraDisplayLeaseTestFixture {
            val receipt = testReceipt(authenticationIdentity)
            val issuer = AuthorizedUltraCompiledRuntimeManifestIssuer(ByteArray(32) { 0x4a })
            val ingressSpecs = assignments.flatMap { assignment ->
                assignment.slotIngressAssignments + assignment.additionalVariants.flatMap {
                    it.slotIngressAssignments
                }
            }
                .map { it.ingressSpec }.distinct()
            val program = deriveVerifiedUiSyncSemanticThemeProgramFingerprint(profile, assignments)
            val uiSync = staticAuthorizedUiSyncProvisioning(outputs, ingressSpecs, program)
            val snapshot = requireNotNull(uiSync.authorizationSnapshot())
            val backendLease = AtomicReference<TestLease>()
            val childProvisioningFactory = {
                staticAuthorizedUiSyncProvisioning(outputs, ingressSpecs, program)
            }
            val backend = UltraProfileActivationBackend { request ->
                val manifest = issuer.issue(
                    request, profile,
                    CompiledUiSyncRuntimeContract.from(snapshot, assignments), null,
                )
                UltraProfileBackendPrepareResult.Prepared(
                    TestPreparation(request, profile, manifest, uiSync,
                        childProvisioningFactory) {
                        backendLease.set(it)
                    },
                )
            }
            val constructor = UltraProfileActivationCoordinator::class.java.getDeclaredConstructor(
                UltraProfileActivationBackend::class.java,
                AuthorizedUltraCompiledRuntimeManifestIssuer::class.java,
            ).apply { isAccessible = true }
            val coordinator = constructor.newInstance(backend, issuer)
            try {
                val session = (coordinator.beginSession("vehicle", receipt.first) as
                    UltraProfileSessionStartResult.Started).token
                val prepared = (coordinator.prepare(session, receipt.first) as
                    UltraProfilePrepareResult.Prepared).activation
                val lease = (coordinator.commit(prepared, receipt.first) as
                    UltraProfileCommitResult.Activated).lease
                val opened = lease.openRuntime()
                check(opened is UltraProfileProvisioningOpenResult.Opened) {
                    "Test provisioning did not open: $opened"
                }
                val child = opened.runtimeLease
                return AuthorizedUltraDisplayLeaseTestFixture(
                    child, snapshot.schemaFingerprint, snapshot.bindingFingerprint,
                    coordinator, lease, requireNotNull(backendLease.get()), receipt.second,
                )
            } catch (failure: Throwable) {
                coordinator.close()
                throw failure
            }
        }

        private fun testReceipt(identity: MfiAuthenticationIdentity):
            Pair<MfiAuthenticationReceipt, () -> Unit> {
            val authorityClass = Class.forName(
                "com.shilapi.xcertplay.mfi.MfiAuthenticationReceiptAuthority",
            )
            val authority = authorityClass.declaredConstructors.single { it.parameterCount == 1 }
                .apply { isAccessible = true }.newInstance(identity)
            val begin = authorityClass.declaredMethods.single { method ->
                method.name.startsWith("begin") && !method.name.startsWith("beginHandoff") &&
                    method.parameterCount == 2
            }.apply { isAccessible = true }
            val channel = Any()
            val attempt = begin.invoke(authority, channel, 1L)
            val issue = attempt.javaClass.declaredMethods.single { method ->
                method.name.startsWith("issue") && method.parameterCount == 2
            }.apply { isAccessible = true }
            val receipt = issue.invoke(attempt, channel, identity) as MfiAuthenticationReceipt
            return receipt to { begin.invoke(authority, Any(), 2L) }
        }

        private fun staticAuthorizedUiSyncProvisioning(
            outputs: List<UltraDisplayGraphOutput>,
            ingressSpecs: List<UiSyncRendererIngressSpec>,
            program: UiSyncSemanticThemeProgramFingerprint,
        ): UiSyncRuntimeProvisioning {
            val implementation = "test.renderer.assignment.v1"
            val capability = object : UiSyncCompleteRendererCapability {
                override fun acquire(context: UiSyncCompleteRendererBindingContext):
                    UiSyncCompleteRendererFactoryLease = object : UiSyncCompleteRendererFactoryLease {
                    private var live = true
                    override val isLive: Boolean get() = live
                    override val implementationId = implementation
                    override val schemaFingerprint: UiSyncSchemaFingerprint = context.schemaFingerprint
                    override val bindingFingerprint: UiSyncBindingFingerprint = context.bindingFingerprint
                    override val displayIds = context.displayIds
                    override val readinessReceipt: RendererReadinessReceipt =
                        context.issueReadinessReceipt(
                            program, emptySet(), emptySet(),
                            semanticCompilerActive = true, themeCompilerActive = true,
                            backendLive = true,
                        )
                    override fun openSession(context: UiSyncCompleteRendererSessionBindingContext):
                        UiSyncCompleteRendererSessionLease = object : UiSyncCompleteRendererSessionLease {
                        private var live = true
                        override val isLive: Boolean get() = live
                        override val implementationId = implementation
                        override val schemaFingerprint = context.schemaFingerprint
                        override val bindingFingerprint = context.bindingFingerprint
                        override val displayIds = context.displayIds
                        override val readinessReceipt: RendererReadinessReceipt =
                            context.issueReadinessReceipt(
                                program, emptySet(), emptySet(),
                                semanticCompilerActive = true, themeCompilerActive = true,
                                backendLive = true,
                            )
                        override fun prepareScreen(context: UiSyncRendererScreenContext):
                            UiSyncRendererScreenCandidate? = null
                        override fun retireScreens(): Boolean = true
                        override fun close() { live = false }
                    }
                    override fun close() { live = false }
                }
            }
            val pending = ArrayDeque<Runnable>()
            val callbackExecutor = object : UiSyncBoundedPresentationExecutor {
                override val maximumPendingCallbacks = 64
                override val parallelism = outputs.size
                override val mayExecuteInline = false
                override fun execute(command: Runnable) {
                    if (pending.size >= maximumPendingCallbacks) {
                        throw RejectedExecutionException("Test callback queue full")
                    }
                    pending.addLast(command)
                }
            }
            return UiSyncRuntimeProvisioning.fromAuthorizedRendererCapability(
                uiSyncInfo = AirPlayUiSyncInfo(mapOf("renderer" to "test")),
                displays = outputs.map { output ->
                    UiSyncDisplayRuntimeSpec(
                        CarPlayUiSyncChannelDescriptor(
                            requireNotNull(output.uiSyncDisplayId).value, "vehicle",
                        ),
                        CarPlayUiSyncMode.PASSIVE,
                    )
                },
                staticPlan = UiSyncRendererStaticPlan(outputs, ingressSpecs),
                rendererCapability = capability,
                callbackExecutor = callbackExecutor,
                authorizationExpectation = UiSyncRuntimeAuthorizationExpectation(
                    program, implementation,
                ),
            )
        }
    }

    private class TestPreparation(
        request: UltraProfileActivationRequest,
        override val verifiedProfile: VerifiedUltraProfile,
        override val compiledManifest: AuthorizedUltraCompiledRuntimeManifest,
        private val uiSync: UiSyncRuntimeProvisioning,
        private val childProvisioningFactory: () -> UiSyncRuntimeProvisioning,
        private val onCommit: (TestLease) -> Unit,
    ) : AuthorizedUltraProfilePreparation {
        override val requestIdentity = request.runtimeIdentity
        override val authenticationIdentity = request.authenticationIdentity
        private var transferred = false

        override fun commit(verificationIdentity: UltraProfileVerificationIdentity):
            AuthorizedUltraProfileBackendLease {
            transferred = true
            return TestLease(verificationIdentity, compiledManifest, uiSync,
                childProvisioningFactory).also(onCommit)
        }

        override fun close() {
            if (!transferred) uiSync.close()
        }
    }

    private class TestLease(
        override val verificationIdentity: UltraProfileVerificationIdentity,
        override val compiledManifest: AuthorizedUltraCompiledRuntimeManifest,
        private val uiSync: UiSyncRuntimeProvisioning,
        private val childProvisioningFactory: () -> UiSyncRuntimeProvisioning,
    ) : AuthorizedUltraProfileBackendLease {
        @Volatile private var live = true
        private val nextIsLiveHook = AtomicReference<(() -> Unit)?>(null)
        private val childProvisionings = ArrayList<UiSyncRuntimeProvisioning>()

        fun setNextIsLiveHook(hook: () -> Unit) {
            check(nextIsLiveHook.compareAndSet(null, hook))
        }

        override val isLive: Boolean get() {
            nextIsLiveHook.getAndSet(null)?.invoke()
            return live
        }
        override fun openProvisioning(): AuthorizedUltraProfileBackendProvisioning {
            val provisioning = synchronized(childProvisionings) {
                val candidate = if (childProvisionings.isEmpty()) uiSync else
                    childProvisioningFactory()
                childProvisionings += candidate
                candidate
            }
            return object : AuthorizedUltraProfileBackendProvisioning {
                private var childLive = true
                override val verificationIdentity = this@TestLease.verificationIdentity
                override val compiledManifest = this@TestLease.compiledManifest
                override val isLive: Boolean get() = childLive
                override val uiSyncProvisioning = provisioning
                override val cafProvisioning = null
                override fun close() {
                    childLive = false
                    provisioning.close()
                }
            }
        }
        override fun close() {
            live = false
            val provisionings = synchronized(childProvisionings) { childProvisionings.toList() }
            if (provisionings.isEmpty()) uiSync.close()
            else provisionings.forEach { it.close() }
        }
    }
}
