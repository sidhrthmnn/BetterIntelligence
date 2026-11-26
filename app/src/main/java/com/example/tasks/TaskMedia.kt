package com.example.tasks

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Never read unbounded pipes or trust a client's path, size or MIME claim. */
internal object TaskMedia {
    private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
    private const val MAX_IMAGE_PIXELS = 24_000_000L
    private const val MAX_AUDIO_BYTES = 16_000 * 2 * 12 + 8192

    suspend fun read(input: ParcelFileDescriptor, type: TaskType, mime: String?): ByteArray {
        val fd = input.fileDescriptor
        val stat = Os.fstat(fd)
        val maximum = if (type.input == "image") MAX_IMAGE_BYTES else MAX_AUDIO_BYTES
        require(OsConstants.S_ISREG(stat.st_mode) && stat.st_size in 1..maximum.toLong()) {
            "Provide a bounded, seekable regular media file"
        }
        val bytes = ByteArray(stat.st_size.toInt())
        var offset = 0
        while (offset < bytes.size) {
            currentCoroutineContext().ensureActive()
            val read = Os.pread(fd, bytes, offset, minOf(65536, bytes.size - offset), offset.toLong())
            require(read > 0) { "Media input changed while being read" }
            offset += read
        }
        return when (mime) {
            "image/png", "image/jpeg" -> prepareImage(bytes, mime)
            "audio/wav", "audio/x-wav" -> normaliseWave(bytes)
            else -> throw IllegalArgumentException("Use JPEG, PNG or mono 16 kHz PCM WAV for this task")
        }
    }

    private fun prepareImage(bytes: ByteArray, mime: String): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outMimeType == mime && bounds.outWidth in 1..8192 && bounds.outHeight in 1..8192 &&
            bounds.outWidth.toLong() * bounds.outHeight <= MAX_IMAGE_PIXELS) { "Invalid image or dimensions" }
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            while (maxOf(bounds.outHeight, bounds.outWidth) / inSampleSize.coerceAtLeast(1) > 768) {
                inSampleSize = inSampleSize.coerceAtLeast(1) * 2
            }
        }
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)) { "Could not decode image" }
        try {
            val ratio = (384f / maxOf(bitmap.width, bitmap.height)).coerceAtMost(1f)
            val scaled = if (ratio < 1f) Bitmap.createScaledBitmap(bitmap,
                (bitmap.width * ratio).toInt().coerceAtLeast(1), (bitmap.height * ratio).toInt().coerceAtLeast(1), true) else bitmap
            try {
                return ByteArrayOutputStream().use {
                    check(scaled.compress(Bitmap.CompressFormat.PNG, 100, it))
                    it.toByteArray()
                }
            } finally { if (scaled !== bitmap) scaled.recycle() }
        } finally { bitmap.recycle() }
    }

    /** Accept PCM samples only and strip RIFF metadata the inference engine does not need. */
    internal fun normaliseWave(bytes: ByteArray): ByteArray {
        require(bytes.size in 44..MAX_AUDIO_BYTES) { "Audio must be at most 12 seconds of mono 16 kHz PCM WAV" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun fourCc(): String = ByteArray(4).also { buffer.get(it) }.toString(Charsets.US_ASCII)
        require(fourCc() == "RIFF" && buffer.int == bytes.size - 8 && fourCc() == "WAVE") { "Invalid WAV container" }
        var format = false
        var pcm: ByteArray? = null
        while (buffer.remaining() >= 8) {
            val id = fourCc()
            val size = buffer.int
            require(size >= 0 && size <= buffer.remaining()) { "Invalid WAV chunk" }
            val end = buffer.position() + size
            when (id) {
                "fmt " -> {
                    require(!format && size >= 16 && buffer.short.toInt() == 1 && buffer.short.toInt() == 1 &&
                        buffer.int == 16_000 && buffer.int == 32_000 && buffer.short.toInt() == 2 && buffer.short.toInt() == 16) {
                        "Use mono 16 kHz, 16-bit PCM WAV"
                    }
                    format = true
                }
                "data" -> {
                    require(format && pcm == null && size in 2..(16_000 * 2 * 12) && size % 2 == 0) { "Invalid WAV samples" }
                    pcm = ByteArray(size).also { buffer.get(it) }
                }
            }
            val next = end + (size and 1)
            require(next <= bytes.size) { "Missing WAV alignment byte" }
            buffer.position(next)
        }
        require(!buffer.hasRemaining()) { "Invalid trailing WAV content" }
        return pcmWave(requireNotNull(pcm) { "No PCM audio was found" })
    }

    internal fun pcmWave(pcm: ByteArray): ByteArray =
        ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(16_000).putInt(32_000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(pcm.size).put(pcm).array()
}
