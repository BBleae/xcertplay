package com.shilapi.xcertplay.ultra

import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayOutputId
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileDigest
import com.shilapi.xcertplay.vehicle.ultra.profile.VerifiedUltraProfile
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeStructuralFingerprint
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe

class InstrumentOnlyOutputBindingTest {
    @Test fun clusterReferenceBindsWhenNoLocalCanvasExists() {
        // Opaque sentinels are never rendered or dereferenced: this tests the controller's
        // no-Canvas composition boundary, while issuer/lease semantics have separate tests.
        val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").apply {
            isAccessible = true
        }.get(null) as Unsafe
        val profile = unsafe.allocateInstance(VerifiedUltraProfile::class.java) as
            VerifiedUltraProfile
        val reference = unsafe.allocateInstance(UltraThemeInstrumentReferenceBinding::class.java)
            as UltraThemeInstrumentReferenceBinding
        val base = UltraThemeOutputSceneBinding(
            UltraThemeVerifiedProgramSource(profile, "signed-phone-package",
                UltraProfileDigest(ByteArray(32)),
                CarPlayThemeStructuralFingerprint(ByteArray(32)), 0, 0),
            DisplayDestination.CLUSTER,
            UltraDisplayOutputId.Custom("cluster-output"), PixelSize(800, 480),
            CarPlayLogicalPixelMapper(
                AxisAlignedPixelAffine(1.0, 1.0, 0.0, 0.0),
                NegativeExtentPolicy.REJECT,
                PixelEdgeQuantization.REQUIRE_INTEGRAL,
                PixelEdgeQuantization.REQUIRE_INTEGRAL,
            ),
            emptyList(),
        )

        val rebound = rebindSelectedThemeOutput(base, emptyList(), reference)
        assertSame(reference, rebound.instrumentReference)
        assertTrue(rebound.localCanvasSlots.isEmpty())
        assertSame(base.source, rebound.source)
        assertSame(base.outputId, rebound.outputId)
    }
}
