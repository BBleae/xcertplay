package com.shilapi.xcertplay

import java.nio.file.Path

/** Shared pure path calculation; the composition object itself is variant-specific. */
internal fun ultraProfileStoreRoot(noBackupFilesDirectory: Path): Path {
    require(noBackupFilesDirectory.isAbsolute) {
        "Android no-backup directory must be an absolute path"
    }
    val normalizedParent = noBackupFilesDirectory.normalize()
    require(normalizedParent.parent != null) {
        "Android no-backup directory must not be a filesystem root"
    }

    val child = normalizedParent.resolve(ULTRA_PROFILE_STORE_DIRECTORY_NAME).normalize()
    check(child.parent == normalizedParent && child.startsWith(normalizedParent)) {
        "Ultra profile Store must remain an immediate no-backup child"
    }
    return child
}

internal const val ULTRA_PROFILE_STORE_DIRECTORY_NAME = "ultra-profile-v1"
