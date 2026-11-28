package com.example.betterclient;

import android.content.*;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.*;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.ipc.IBiTaskCallback;
import com.example.ipc.ILocalAiCoreService;
import org.junit.Test;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

public class MultimodalTaskTest {
    @Test public void verifyTaskProtocolAcrossAndroidUids() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String type = arguments.getString("task");
        assumeTrue(type != null);
        assertTrue(BuildConfig.BI_PACKAGE.endsWith(".validation"));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AtomicReference<ILocalAiCoreService> service = new AtomicReference<>();
        CountDownLatch connected = new CountDownLatch(1);
        ServiceConnection connection = new ServiceConnection() {
            public void onServiceConnected(ComponentName name, IBinder binder) {
                service.set(ILocalAiCoreService.Stub.asInterface(binder)); connected.countDown();
            }
            public void onServiceDisconnected(ComponentName name) { service.set(null); }
        };
        assertTrue(context.bindService(new Intent(BuildConfig.BI_PACKAGE + ".ACTION_BIND_AI_CORE")
            .setPackage(BuildConfig.BI_PACKAGE), connection, Context.BIND_AUTO_CREATE));
        File temporary = null;
        try {
            assertTrue(connected.await(15, TimeUnit.SECONDS));
            ILocalAiCoreService api = service.get();
            BiTaskClient client = new BiTaskClient(api);
            assertEquals(3, client.taskApiVersion());
            assertEquals(2, api.getServiceStatus().getInt("api_version"));
            assertEquals("SUCCESS", api.getCapabilities().getString("status"));
            assertEquals("SUCCESS", api.getInstalledModels().getString("status"));
            Capture capture = new Capture();
            if ("TEXT_ONLY_PERMISSIONS".equals(type)) {
                assertEquals("DENIED", client.submit("SPEECH_GENERATION", "One", capture, null, null, "en-GB").getString("status"));
                assertEquals("DENIED", api.getRecommendedModels("IMAGE_UNDERSTANDING").getString("status"));
                assertEquals("DENIED", api.requestModelDownload(1).getString("status"));
                return;
            }
            if ("PROTOCOL".equals(type)) {
                assertEquals("CAPABILITY_UNAVAILABLE",
                    client.submit("VIDEO_GENERATION", "A river", capture, null, null, null).getString("status"));
                assertEquals("TASK_NOT_FOUND", api.getTaskStatus("another-request").getString("status"));
                assertFalse(api.cancelTask("another-request"));
                Bundle old = new Bundle();
                old.putInt("api_version", 2);
                assertEquals("VERSION_UNSUPPORTED", api.submitTask(old, null, capture).getString("status"));
                ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
                pipe[1].close();
                try (ParcelFileDescriptor reading = pipe[0]) {
                    Bundle image = new Bundle();
                    image.putInt("api_version", 3);
                    image.putString("task_type", "IMAGE_UNDERSTANDING");
                    image.putString("media_mime", "image/png");
                    assertEquals("QUEUED", api.submitTask(image, reading, capture).getString("status"));
                    assertTrue(capture.done.await(15, TimeUnit.SECONDS));
                    assertEquals("INVALID_INPUT", capture.result.getString("status"));
                }
                return;
            }
            String text = "The lighthouse stands by the river.";
            String mime = null;
            if ("IMAGE_UNDERSTANDING".equals(type)) {
                temporary = File.createTempFile("scene-", ".png", context.getCacheDir());
                Bitmap scene = Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888);
                scene.eraseColor(Color.rgb(5, 45, 240));
                try (FileOutputStream output = new FileOutputStream(temporary)) {
                    assertTrue(scene.compress(Bitmap.CompressFormat.PNG, 100, output));
                } finally { scene.recycle(); }
                text = "What colour fills this entire image? Reply with the colour only.";
                mime = "image/png";
            } else if ("AUDIO_TRANSCRIPTION".equals(type) || "AUDIO_UNDERSTANDING".equals(type)) {
                temporary = new File(context.getFilesDir(), "bi-validation-speech.wav");
                assertTrue("Stage the controlled audio fixture", temporary.isFile());
                text = "What words can you hear in the recording? Answer briefly.";
                mime = "audio/wav";
            } else if ("TEXT_POLISH".equals(type)) {
                text = "The lights is on.";
            }
            String modelPrefix = arguments.getString("modelPrefix");
            long modelId = 0;
            if (modelPrefix != null) {
                java.util.ArrayList<Bundle> models = api.getInstalledModels().getParcelableArrayList("models");
                assertNotNull(models);
                modelId = models.stream().filter(model -> model.getString("name", "").startsWith(modelPrefix))
                    .findFirst().orElseThrow(() -> new IllegalStateException("Requested validation model is missing")).getLong("model_id");
            }
            Bundle queued = client.submit(type, text, capture, temporary, mime, "en-GB", modelId);
            assertEquals(queued.toString(), "QUEUED", queued.getString("status"));
            String id = queued.getString("task_id");
            assertNotNull(id);
            if ("true".equals(arguments.getString("cancelTask"))) {
                assertTrue(client.cancel(id));
                assertTrue(capture.done.await(120, TimeUnit.SECONDS));
                assertEquals("CANCELLED", capture.result.getString("status"));
                return;
            }
            assertTrue(capture.done.await(240, TimeUnit.SECONDS));
            Bundle response = capture.result;
            assertNotNull(response);
            String status = response.getString("status");
            android.util.Log.i("BiTaskValidation", "task=" + type + " status=" + status +
                " loadMs=" + response.getLong("load_ms") + " firstTokenMs=" + response.getLong("first_token_ms") +
                " peakMb=" + response.getFloat("observed_ram_mb") + " latencyMs=" + response.getLong("latency_ms") +
                " backend=" + response.getString("backend") + " detail=" + response.getString("message", ""));
            assertEquals(response.toString(), "SUCCESS", status);
            if (modelId > 0) assertEquals(modelId, response.getLong("model_id"));
            assertTrue(capture.phases.contains("PROCESSING"));
            assertEquals("SUCCESS", api.getTaskStatus(id).getString("status"));
            assertFalse(api.getTaskStatus(id).containsKey("text"));
            assertFalse(api.getTaskStatus(id).containsKey("embedding"));
            if ("SPEECH_GENERATION".equals(type)) {
                assertTrue(capture.audioBytes > 1000);
                assertEquals("audio/wav", response.getString("output_format"));
            } else if ("EMBEDDINGS".equals(type)) {
                float[] vector = response.getFloatArray("embedding");
                assertNotNull(vector);
                assertEquals(384, vector.length);
                double norm = 0;
                for (float component : vector) {
                    assertTrue(Float.isFinite(component));
                    norm += component * component;
                }
                assertEquals(1.0, norm, 0.002);
                assertTrue(similarity(vector, embed(client, "A beacon beside a river lights the way.")) >
                    similarity(vector, embed(client, "Pasta cooks in salted water.")));
            } else {
                assertTrue(capture.textChunks > 0);
                String output = response.getString("text", "").toLowerCase(java.util.Locale.ROOT);
                assertFalse(output.isBlank());
                if ("IMAGE_UNDERSTANDING".equals(type)) assertTrue(output, output.contains("blue"));
                if ("AUDIO_TRANSCRIPTION".equals(type) || "AUDIO_UNDERSTANDING".equals(type))
                    assertTrue(output, output.contains("lighthouse") && output.contains("river"));
            }
        } finally {
            if (temporary != null && temporary.getName().startsWith("scene-")) temporary.delete();
            context.unbindService(connection);
        }
    }

    private static float[] embed(BiTaskClient client, String text) throws Exception {
        Capture capture = new Capture();
        assertEquals("QUEUED", client.submit("EMBEDDINGS", text, capture, null, null, null).getString("status"));
        assertTrue(capture.done.await(15, TimeUnit.SECONDS));
        assertEquals("SUCCESS", capture.result.getString("status"));
        return capture.result.getFloatArray("embedding");
    }

    private static double similarity(float[] first, float[] second) {
        assertNotNull(second);
        assertEquals(first.length, second.length);
        double score = 0;
        for (int i = 0; i < first.length; i++) score += first[i] * second[i];
        return score;
    }

    private static final class Capture extends IBiTaskCallback.Stub {
        final CountDownLatch done = new CountDownLatch(1);
        final CopyOnWriteArrayList<String> phases = new CopyOnWriteArrayList<>();
        volatile Bundle result;
        volatile int textChunks;
        volatile int audioBytes;
        public void onProgress(Bundle status) { phases.add(status.getString("status")); }
        public void onText(String id, String text) { if (!text.isEmpty()) textChunks++; }
        public void onFinished(Bundle bundle, ParcelFileDescriptor audio) {
            result = bundle;
            if (audio != null) {
                try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(audio)) {
                    byte[] header = new byte[12];
                    int size = input.read(header);
                    if (size == 12 && "RIFF".equals(new String(header, 0, 4, java.nio.charset.StandardCharsets.US_ASCII))) {
                        byte[] block = new byte[8192];
                        int count;
                        audioBytes = size;
                        while ((count = input.read(block)) > 0) audioBytes += count;
                    }
                } catch (IOException ignored) { }
            }
            done.countDown();
        }
    }
}
