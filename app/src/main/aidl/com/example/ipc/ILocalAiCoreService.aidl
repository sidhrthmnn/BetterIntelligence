package com.example.ipc;

import com.example.ipc.ILocalAiStreamCallback;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import com.example.ipc.IBiTaskCallback;

interface ILocalAiCoreService {
    boolean isModelLoaded();
    String getActiveModelName();
    String getActiveModelQuantization();
    int getActiveContextSize();
    String generateTextSync(String prompt, String systemPrompt, float temperature, int maxTokens);
    void generateStream(String prompt, String systemPrompt, float temperature, float topP, int maxTokens, ILocalAiStreamCallback callback);
    float[] getEmbeddings(String text);
    String summarizeText(String text, int maxWords);
    String classifyText(String text, in String[] candidateLabels);
    String extractStructuredJson(String text, in String[] schemaKeys);
    String routeIntent(String utterance, in String[] candidateRoutes);
    boolean storeSharedMemory(String title, String content, String category);
    String querySharedMemoriesJson(String query, int limit);
    void cancelActiveGeneration();
    String getEngineTelemetryJson();
    // Append new methods to preserve existing transaction IDs.
    Bundle getServiceStatus();
    Bundle polishText(String text);

    // Task protocol v3. Keep all earlier methods in their original order.
    int getApiVersion();
    Bundle getCapabilities();
    Bundle getInstalledModels();
    Bundle getRecommendedModels(String capability);
    Bundle getModelStatus(long modelId);
    Bundle requestModelDownload(long modelId);
    Bundle submitTask(in Bundle request, in ParcelFileDescriptor mediaInput, IBiTaskCallback callback);
    Bundle getTaskStatus(String taskId);
    boolean cancelTask(String taskId);
}
