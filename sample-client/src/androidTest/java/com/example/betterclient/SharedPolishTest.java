package com.example.betterclient;

import android.content.*;
import android.net.Uri;
import android.os.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.ipc.ILocalAiCoreService;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Runs under the sample app's UID, across a real process boundary. */
@RunWith(AndroidJUnit4.class)
public class SharedPolishTest {
    @Test public void providerAndBinderRespectTheCallingApp() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Uri uri = Uri.parse("content://com.aistudio.betterintelligence.mobile.provider");
        String expected = InstrumentationRegistry.getArguments().getString("expectedStatus", "APPROVAL_REQUIRED");
        boolean rateLimited = "true".equals(InstrumentationRegistry.getArguments().getString("rateLimited"));
        Bundle spoof = new Bundle();
        spoof.putString("calling_package", "com.aistudio.betterintelligence.mobile");
        Bundle status = context.getContentResolver().call(uri, "status", null, spoof);
        assertNotNull(status);
        for (int i = 0; "MODEL_REQUIRED".equals(status.getString("status")) && "READY".equals(expected) && i < 60; i++) {
            Thread.sleep(500);
            status = context.getContentResolver().call(uri, "status", null, spoof);
        }
        assertEquals(expected, status.getString("status"));
        assertTrue(status.getBoolean("supports_polish"));
        Bundle result = context.getContentResolver().call(uri, "polish", "i has 2 apples.", spoof);
        if ("READY".equals(expected)) {
            assertEquals(result.toString(), "SUCCESS", result.getString("status"));
            assertTrue(result.getString("text").contains("2"));
            assertFalse(result.getString("text").trim().isEmpty());
            assertEquals("I have 2 apples.", result.getString("text").trim());
            Bundle invalid = context.getContentResolver().call(uri, "polish", "", null);
            assertEquals("INVALID_REQUEST", invalid.getString("status"));
        } else {
            assertEquals(expected, result.getString("status"));
            assertNull(result.getString("text"));
            Bundle legacy = context.getContentResolver().call(uri, "generate", "Do not run this request", spoof);
            assertEquals("DENIED", legacy.getString("status"));
        }

        CountDownLatch connected = new CountDownLatch(1);
        AtomicReference<ILocalAiCoreService> service = new AtomicReference<>();
        ServiceConnection connection = new ServiceConnection() {
            public void onServiceConnected(ComponentName name, IBinder binder) {
                service.set(ILocalAiCoreService.Stub.asInterface(binder)); connected.countDown();
            }
            public void onServiceDisconnected(ComponentName name) { service.set(null); }
        };
        boolean bound = context.bindService(new Intent("com.aistudio.betterintelligence.mobile.ACTION_BIND_AI_CORE")
            .setPackage("com.aistudio.betterintelligence.mobile"), connection, Context.BIND_AUTO_CREATE);
        assertTrue(bound);
        try {
            assertTrue(connected.await(10, TimeUnit.SECONDS));
            assertEquals(expected, service.get().getServiceStatus().getString("status"));
            Bundle binderResult = service.get().polishText("she have 3 books.");
            assertEquals("READY".equals(expected) ? (rateLimited ? "RATE_LIMITED" : "SUCCESS") : expected, binderResult.getString("status"));
            if ("READY".equals(expected) && !rateLimited) assertEquals("She has 3 books.", binderResult.getString("text").trim());
            else assertNull(binderResult.getString("text"));
        } finally { context.unbindService(connection); }
    }
}
