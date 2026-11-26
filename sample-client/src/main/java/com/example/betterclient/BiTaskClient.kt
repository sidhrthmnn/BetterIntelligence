package com.example.betterclient

import android.os.Bundle
import android.os.ParcelFileDescriptor
import com.example.ipc.IBiTaskCallback
import com.example.ipc.ILocalAiCoreService
import java.io.File

/**
 * Use the app's existing explicit Binder connection. If taskApiVersion returns
 * 2, continue using generateTextSync or polishText on an older installation.
 * Call methods off the main thread and move work from callbacks to your UI.
 */
class BiTaskClient(private val service: ILocalAiCoreService) {
    fun taskApiVersion(): Int = runCatching { service.apiVersion }.getOrDefault(2)

    fun submit(type: String, text: String, callback: IBiTaskCallback, media: File? = null,
        mime: String? = null, languageTag: String? = null): Bundle {
        require(taskApiVersion() >= 3) { "The installed Better Intelligence version only supports text IPC v2" }
        val request = Bundle().apply {
            putInt("api_version", 3)
            putString("task_type", type)
            putString("text", text)
            putString("media_mime", mime)
            putString("language_tag", languageTag)
        }
        if (media == null) return service.submitTask(request, null, callback)
        return ParcelFileDescriptor.open(media, ParcelFileDescriptor.MODE_READ_ONLY).use { reading ->
            service.submitTask(request, reading, callback)
        }
    }

    fun cancel(id: String): Boolean = service.cancelTask(id)
}
