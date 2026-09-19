package com.example.engine

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * High-performance binary parser for GGUF model files.
 * Adheres to the GGUF specification with support for version 2 and version 3 formats.
 */
class GgufParser {

    fun parseFromFile(file: File, maxTensorsToRead: Int = 100): Result<GgufMetadata> {
        return try {
            FileInputStream(file).use { fis ->
                val channel = fis.channel
                parseFromChannel(channel, file.length(), maxTensorsToRead)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun parseFromStream(inputStream: InputStream, totalSize: Long = 0L, maxTensorsToRead: Int = 100): Result<GgufMetadata> {
        return try {
            // Read header bytes into buffer
            val buffer = ByteArray(64 * 1024)
            val bytesRead = inputStream.read(buffer)
            if (bytesRead < 16) {
                return Result.failure(IllegalArgumentException("File too small to be a valid GGUF"))
            }
            val byteBuffer = ByteBuffer.wrap(buffer, 0, bytesRead).order(ByteOrder.LITTLE_ENDIAN)
            parseFromByteBuffer(byteBuffer, totalSize, maxTensorsToRead)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun parseFromChannel(channel: FileChannel, totalSize: Long, maxTensorsToRead: Int = 100): Result<GgufMetadata> {
        return try {
            // Map header into memory (first 2MB is ample for metadata)
            val headerSize = minOf(totalSize, 4L * 1024L * 1024L)
            val buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, headerSize).order(ByteOrder.LITTLE_ENDIAN)
            parseFromByteBuffer(buffer, totalSize, maxTensorsToRead)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun parseFromByteBuffer(buffer: ByteBuffer, totalSizeBytes: Long, maxTensorsToRead: Int): Result<GgufMetadata> {
        if (buffer.remaining() < 16) {
            return Result.failure(IllegalArgumentException("Buffer underflow reading GGUF header"))
        }

        val magic = buffer.int
        if (magic != GgufConstants.GGUF_MAGIC) {
            return Result.failure(IllegalArgumentException("Invalid GGUF magic bytes: 0x${Integer.toHexString(magic)}"))
        }

        val version = buffer.int
        if (version != GgufConstants.GGUF_VERSION_2 && version != GgufConstants.GGUF_VERSION_3) {
            return Result.failure(IllegalArgumentException("Unsupported GGUF version: $version. Expected v2 or v3."))
        }

        val tensorCount = buffer.long
        val kvCount = buffer.long

        val kvPairs = mutableMapOf<String, String>()
        var architecture = "llama"
        var modelName = "GGUF Model"
        var contextLength = 2048
        var embeddingLength = 2048
        var blockCount = 24
        var headCount = 16
        var headCountKv = 16
        var vocabSize = 32000
        var quantizationVersion = 2
        var ropeFreqBase = 10000.0f
        var feedForwardLength = 5632
        var primaryQuant = QuantizationType.Q4_K_M

        // Parse KV metadata
        for (i in 0 until kvCount) {
            if (buffer.remaining() < 4) break
            val key = readString(buffer) ?: break
            val type = buffer.int
            val value = readMetadataValue(buffer, type)

            kvPairs[key] = value

            when (key) {
                "general.architecture" -> architecture = value
                "general.name" -> modelName = value
                "general.quantization_version" -> quantizationVersion = value.toIntOrNull() ?: 2
                "general.file_type" -> {
                    val fileType = value.toIntOrNull() ?: 15
                    primaryQuant = QuantizationType.fromTypeId(fileType)
                }
                "$architecture.context_length" -> contextLength = value.toIntOrNull() ?: contextLength
                "$architecture.embedding_length" -> embeddingLength = value.toIntOrNull() ?: embeddingLength
                "$architecture.block_count" -> blockCount = value.toIntOrNull() ?: blockCount
                "$architecture.attention.head_count" -> headCount = value.toIntOrNull() ?: headCount
                "$architecture.attention.head_count_kv" -> headCountKv = value.toIntOrNull() ?: headCountKv
                "$architecture.feed_forward_length" -> feedForwardLength = value.toIntOrNull() ?: feedForwardLength
                "$architecture.rope.freq_base" -> ropeFreqBase = value.toFloatOrNull() ?: ropeFreqBase
                "tokenizer.ggml.tokens" -> {
                    // Array of tokens - length gives vocab size
                    if (value.startsWith("[") && value.endsWith(" items]")) {
                        vocabSize = value.substring(1, value.indexOf(" ")).toIntOrNull() ?: vocabSize
                    }
                }
            }
        }

        // Parse Tensor info descriptors
        val tensors = mutableListOf<GgufTensorInfo>()
        val tensorsToRead = minOf(tensorCount, maxTensorsToRead.toLong()).toInt()

        for (i in 0 until tensorsToRead) {
            if (buffer.remaining() < 16) break
            val tensorName = readString(buffer) ?: break
            val nDims = buffer.int
            val dims = LongArray(nDims)
            var totalElements = 1L
            for (d in 0 until nDims) {
                dims[d] = buffer.long
                totalElements *= dims[d]
            }
            val tensorTypeId = buffer.int
            val tensorType = QuantizationType.fromTypeId(tensorTypeId)
            val offset = buffer.long

            val sizeBytes = (totalElements * tensorType.bitsPerWeight / 8).toLong()
            tensors.add(
                GgufTensorInfo(
                    name = tensorName,
                    dimensions = dims,
                    type = tensorType,
                    offset = offset,
                    sizeBytes = sizeBytes
                )
            )
        }

        val metadata = GgufMetadata(
            version = version,
            tensorCount = tensorCount,
            kvCount = kvCount,
            architecture = architecture,
            modelName = modelName,
            contextLength = contextLength,
            embeddingLength = embeddingLength,
            blockCount = blockCount,
            headCount = headCount,
            headCountKv = headCountKv,
            vocabSize = vocabSize,
            quantizationVersion = quantizationVersion,
            primaryQuantization = primaryQuant,
            ropeFreqBase = ropeFreqBase,
            feedForwardLength = feedForwardLength,
            customKvPairs = kvPairs,
            tensors = tensors,
            totalSizeBytes = totalSizeBytes
        )

        return Result.success(metadata)
    }

    private fun readString(buffer: ByteBuffer): String? {
        if (buffer.remaining() < 8) return null
        val length = buffer.long.toInt()
        if (length < 0 || length > buffer.remaining()) return null
        val bytes = ByteArray(length)
        buffer.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun readMetadataValue(buffer: ByteBuffer, type: Int): String {
        return when (type) {
            GgufConstants.GGUF_TYPE_UINT8, GgufConstants.GGUF_TYPE_INT8 -> buffer.get().toString()
            GgufConstants.GGUF_TYPE_UINT16, GgufConstants.GGUF_TYPE_INT16 -> buffer.short.toString()
            GgufConstants.GGUF_TYPE_UINT32, GgufConstants.GGUF_TYPE_INT32 -> buffer.int.toString()
            GgufConstants.GGUF_TYPE_FLOAT32 -> buffer.float.toString()
            GgufConstants.GGUF_TYPE_BOOL -> (buffer.get() != 0.toByte()).toString()
            GgufConstants.GGUF_TYPE_STRING -> readString(buffer) ?: ""
            GgufConstants.GGUF_TYPE_UINT64, GgufConstants.GGUF_TYPE_INT64 -> buffer.long.toString()
            GgufConstants.GGUF_TYPE_FLOAT64 -> buffer.double.toString()
            GgufConstants.GGUF_TYPE_ARRAY -> {
                val arrayType = buffer.int
                val arrayLen = buffer.long.toInt()
                // Skip reading array items if too large to avoid memory consumption
                if (arrayLen > 50) {
                    skipArray(buffer, arrayType, arrayLen)
                    "[$arrayLen items]"
                } else {
                    val items = (0 until arrayLen).map { readMetadataValue(buffer, arrayType) }
                    items.joinToString(", ", "[", "]")
                }
            }
            else -> "Unknown"
        }
    }

    private fun skipArray(buffer: ByteBuffer, arrayType: Int, count: Int) {
        val itemSize = when (arrayType) {
            GgufConstants.GGUF_TYPE_UINT8, GgufConstants.GGUF_TYPE_INT8, GgufConstants.GGUF_TYPE_BOOL -> 1
            GgufConstants.GGUF_TYPE_UINT16, GgufConstants.GGUF_TYPE_INT16 -> 2
            GgufConstants.GGUF_TYPE_UINT32, GgufConstants.GGUF_TYPE_INT32, GgufConstants.GGUF_TYPE_FLOAT32 -> 4
            GgufConstants.GGUF_TYPE_UINT64, GgufConstants.GGUF_TYPE_INT64, GgufConstants.GGUF_TYPE_FLOAT64 -> 8
            else -> -1
        }
        if (itemSize > 0) {
            val totalBytes = itemSize.toLong() * count
            val skip = minOf(totalBytes, buffer.remaining().toLong()).toInt()
            buffer.position(buffer.position() + skip)
        } else if (arrayType == GgufConstants.GGUF_TYPE_STRING) {
            for (i in 0 until count) {
                if (buffer.remaining() < 8) break
                val len = buffer.long.toInt()
                if (len < 0 || len > buffer.remaining()) break
                buffer.position(buffer.position() + len)
            }
        }
    }
}
