package com.example

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.example.ipc.LocalAiCaller
import com.example.ipc.LocalAiCoreService
import com.example.service.DownloadQueueStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReinstallConnectionTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun client(name: String, uid: Int, declared: Boolean) {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = name
            applicationInfo = ApplicationInfo().apply { packageName = name; this.uid = uid }
            requestedPermissions = if (declared) arrayOf(LocalAiCoreService.PERMISSION_BIND) else emptyArray()
            requestedPermissionsFlags = if (declared) intArrayOf(0) else intArrayOf()
        })
    }
    @Test fun declarationStillEnrollsClientWhenAndroidGrantWasLost() {
        client("example.keyboard", 20111, true)
        assertEquals(PackageManager.PERMISSION_DENIED,
            context.packageManager.checkPermission(LocalAiCoreService.PERMISSION_BIND, "example.keyboard"))
        assertEquals("example.keyboard", LocalAiCaller.packageForUid(context, 20111))
    }
    @Test fun unrelatedUidCannotBorrowAnotherInstalledAppsDeclaration() {
        client("example.approved", 20111, true)
        client("example.unrelated", 20112, false)
        assertThrows(SecurityException::class.java) { LocalAiCaller.packageForUid(context, 20112) }
        assertThrows(SecurityException::class.java) { LocalAiCaller.packageForUid(context, 29999) }
    }
    @Test fun sharedUidSelectsADeclaringIdentityDeterministically() {
        client("example.a", 20111, false); client("example.z", 20111, true)
        assertEquals("example.z", LocalAiCaller.packageForUid(context, 20111))
    }
    @Test fun ipcEnrollmentIsNotGatedOnTheRevocableInstallTimeGrant() {
        val manager = context.packageManager
        val service = manager.getServiceInfo(android.content.ComponentName(context, LocalAiCoreService::class.java), 0)
        val provider = manager.resolveContentProvider("${context.packageName}.provider", 0)!!
        assertNull(service.permission)
        assertNull(provider.readPermission); assertNull(provider.writePermission)
        assertEquals("normal", manager.getPermissionInfo(LocalAiCoreService.PERMISSION_BIND, 0).let {
            if (it.protectionLevel == android.content.pm.PermissionInfo.PROTECTION_NORMAL) "normal" else "other"
        })
    }
    @Test fun wifiToggleHasOneValueAndMigratesExistingMobilePreference() {
        val prefs = context.getSharedPreferences("model_download_queue", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        assertTrue(DownloadQueueStore(context).wifiOnly())
        prefs.edit().putBoolean("allow_mobile_data", true).commit()
        assertFalse(DownloadQueueStore(context).wifiOnly())
        prefs.edit().putBoolean("wifi_only", true).commit()
        assertTrue(DownloadQueueStore(context).wifiOnly())
        DownloadQueueStore(context).setWifiOnly(false)
        assertFalse(prefs.contains("allow_mobile_data"))
        assertFalse(DownloadQueueStore(context).wifiOnly())
        DownloadQueueStore(context).setWifiOnly(true)
        assertTrue(DownloadQueueStore(context).wifiOnly())
    }
}
