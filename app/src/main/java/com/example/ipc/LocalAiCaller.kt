package com.example.ipc

import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Process

/** Stable enrollment across provider reinstalls. Android may revoke a custom
 * normal permission's grant when its defining app is removed, even though the
 * already-installed client still declares it. Identity always comes from Binder
 * and PackageManager; inference/memory access still requires BI's scoped policy. */
object LocalAiCaller {
    fun packageForUid(context: Context, uid: Int = Binder.getCallingUid()): String {
        if (uid == Process.myUid()) return context.packageName
        val manager = context.packageManager
        // Shared-UID packages are one Android security principal. Select a
        // declaring package deterministically, never an identity in client input.
        return manager.getPackagesForUid(uid).orEmpty().sorted().firstOrNull { name ->
            runCatching {
                val info = manager.getPackageInfo(name, PackageManager.GET_PERMISSIONS)
                info.applicationInfo?.uid == uid &&
                    info.requestedPermissions.orEmpty().contains(LocalAiCoreService.PERMISSION_BIND)
            }.getOrDefault(false)
        } ?: throw SecurityException("Declare the Better Intelligence local AI permission in the calling app")
    }
}
