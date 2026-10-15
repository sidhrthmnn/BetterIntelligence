package com.example.ipc;

import com.example.ipc.ILocalAiStreamCallback;

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
}
