package com.example.betterclient;

import android.content.*;
import android.net.Uri;
import android.os.*;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.ipc.ILocalAiCoreService;
import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Actual cross-UID contract test, without judging a small model's grammar. */
public class RustServiceCompatibilityTest {
    @Test public void approvedClientUsesExistingProviderAndBinderWithRustInternals() throws Exception {
        assertTrue(BuildConfig.BI_PACKAGE.endsWith(".validation"));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Uri uri = Uri.parse("content://" + BuildConfig.BI_PACKAGE + ".provider");
        Bundle status = context.getContentResolver().call(uri, "prepare_polish", null, null);
        for (int i = 0; i < 80; i++) {
            if (status != null && "READY".equals(status.getString("status"))) break;
            Thread.sleep(500);
            status = context.getContentResolver().call(uri, "status", null, null);
        }
        assertNotNull(status); assertEquals(status.toString(), "READY", status.getString("status"));
        assertEquals("RUST", status.getString("intelligence_engine"));
        Bundle polished = context.getContentResolver().call(uri, "polish", "i has two apples.", null);
        assertNotNull(polished); assertEquals(polished.toString(), "SUCCESS", polished.getString("status"));
        assertNotNull(polished.getString("text")); assertFalse(polished.getString("text").trim().isEmpty());
        CountDownLatch connected = new CountDownLatch(1);
        AtomicReference<ILocalAiCoreService> service = new AtomicReference<>();
        ServiceConnection connection = new ServiceConnection() {
            public void onServiceConnected(ComponentName name, IBinder binder) { service.set(ILocalAiCoreService.Stub.asInterface(binder)); connected.countDown(); }
            public void onServiceDisconnected(ComponentName name) { service.set(null); }
        };
        assertTrue(context.bindService(new Intent(BuildConfig.BI_PACKAGE + ".ACTION_BIND_AI_CORE").setPackage(BuildConfig.BI_PACKAGE), connection, Context.BIND_AUTO_CREATE));
        try {
            assertTrue(connected.await(10, TimeUnit.SECONDS));
            assertEquals("READY", service.get().getServiceStatus().getString("status"));
            Bundle result = service.get().polishText("the cat sit on a mat.");
            assertEquals(result.toString(), "SUCCESS", result.getString("status"));
            assertNotNull(result.getString("text")); assertFalse(result.getString("text").trim().isEmpty());
        } finally { context.unbindService(connection); }
    }
}
