package com.shilapi.xcertplay.bench

import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Test

class BenchUltraProfileStageActivityTest {
    @Test fun failuresHaveActionableLabelsWithoutLeakingExceptionDetails() {
        assertEquals("Invalid bench vehicle ID", benchStageFailureLabel(
            IllegalArgumentException("Debug bench vehicle ID is invalid"),
        ))
        assertEquals("Invalid Profile ZIP", benchStageFailureLabel(
            IllegalArgumentException("Debug bench Profile ZIP was rejected by the package inspector"),
        ))
        assertEquals("Invalid P-256 public key", benchStageFailureLabel(
            IllegalArgumentException("Invalid P-256 public key: secret certificate bytes"),
        ))
        assertEquals("Selected file could not be opened", benchStageFailureLabel(
            FileNotFoundException("content://private/file?token=secret"),
        ))
        assertEquals("Could not read or stage the selected files", benchStageFailureLabel(
            IllegalStateException("private provider path /data/user/0/..."),
        ))
    }
}
