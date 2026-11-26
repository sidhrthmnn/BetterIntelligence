package com.example.ipc;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;

oneway interface IBiTaskCallback {
    void onProgress(in Bundle status);
    void onText(String taskId, String delta);
    void onFinished(in Bundle result, in ParcelFileDescriptor mediaOutput);
}
