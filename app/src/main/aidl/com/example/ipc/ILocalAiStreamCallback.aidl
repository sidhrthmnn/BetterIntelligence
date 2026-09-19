package com.example.ipc;

interface ILocalAiStreamCallback {
    void onToken(String token, int tokenIndex);
    void onComplete(String fullText, long latencyMs, float tokensPerSecond, int promptTokens, int completionTokens);
    void onError(int errorCode, String errorMessage);
}
