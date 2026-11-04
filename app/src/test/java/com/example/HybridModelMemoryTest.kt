package com.example

import com.example.engine.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class HybridModelMemoryTest {
    private fun metadata(arch: String, blocks: Int = 24, heads: Int = 4, dimension: Int = 128,
        extra: Map<String, String> = emptyMap()) = GgufMetadata(3, 0, 0, arch, "Synthetic geometry",
        262144, 2048, blocks, 16, heads, 128000, 2, QuantizationType.Q4_0, 10000f, 7680,
        extra + mapOf("$arch.attention.key_length" to "$dimension", "$arch.attention.value_length" to "$dimension"))
    @Test fun lfmPerLayerZeroHeadsDoNotAcquireAFullGrowingCache() {
        val headCounts = listOf(0, 0, 4, 0, 4, 0, 4, 0, 4, 0, 4, 0, 4, 0, 4, 0, 4, 0, 4, 0, 4, 0, 4, 0)
        val meta = metadata("lfm3", extra = mapOf("lfm3.attention.head_count_kv" to headCounts.toString()))
        assertEquals(22528L, ModelMemoryPlanner.kvBytesPerToken(meta))
        assertEquals(49152L, ModelMemoryPlanner.kvBytesPerToken(metadata("llama")))
        assertEquals(12288L, ModelMemoryPlanner.kvBytesPerToken(metadata("qwen35", heads = 2, dimension = 256)))
        assertEquals(32768L, ModelMemoryPlanner.kvBytesPerToken(metadata("qwen35", blocks = 32, heads = 4, dimension = 256)))
    }
    @Test fun ggufParserRetainsTheActualHybridHeadArrayAndMaximum() {
        val buffer = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
        fun string(value: String) { val bytes = value.toByteArray(); buffer.putLong(bytes.size.toLong()); buffer.put(bytes) }
        buffer.putInt(0x46554747).putInt(3).putLong(0).putLong(3)
        string("general.architecture"); buffer.putInt(8); string("lfm3")
        string("lfm3.block_count"); buffer.putInt(4).putInt(4)
        string("lfm3.attention.head_count_kv"); buffer.putInt(9).putInt(4).putLong(4)
        listOf(0, 4, 0, 4).forEach(buffer::putInt)
        buffer.flip()
        val meta = GgufParser().parseFromByteBuffer(buffer, buffer.remaining().toLong(), 0).getOrThrow()
        assertEquals(4, meta.headCountKv)
        assertEquals("[0, 4, 0, 4]", meta.customKvPairs["lfm3.attention.head_count_kv"])
    }
}
