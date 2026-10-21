package com.example

import com.example.grounding.SearchHttp
import kotlinx.coroutines.*
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Actual local sockets exercise cancellation/response ownership, without a provider key. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SearchHttpTest {
    @Test fun jsonAndOversizedBodiesAreBounded() = runBlocking {
        for (payload in listOf("{\"ok\":true}", "x".repeat(1024 * 1024 + 64))) {
            ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { server ->
                val responder = thread(isDaemon = true) {
                    server.accept().use { socket ->
                        val input = socket.getInputStream().bufferedReader()
                        while (!input.readLine().isNullOrEmpty()) { }
                        val output = socket.getOutputStream()
                        output.write("HTTP/1.1 200 OK\r\nContent-Length: ${payload.length}\r\nConnection: close\r\n\r\n".toByteArray())
                        runCatching { output.write(payload.toByteArray()); output.flush() }
                    }
                }
                val result = runCatching { SearchHttp(okhttp3.OkHttpClient(), allowTestUrls = true).json(Request.Builder().url("http://localhost:${server.localPort}/synthetic").build()) }
                if (payload.startsWith("{")) assertTrue(result.getOrThrow().getBoolean("ok"))
                else assertEquals("Search response is too large", result.exceptionOrNull()?.message)
                responder.join(3000)
            }
        }
    }

    @Test fun cancellingWhileReadingClosesTheProviderConnection() = runBlocking {
        ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { server ->
            val reading = CountDownLatch(1); val disconnected = CountDownLatch(1)
            val responder = thread(isDaemon = true) {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val input = socket.getInputStream().bufferedReader()
                    while (!input.readLine().isNullOrEmpty()) { }
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: 400000\r\n\r\n{".toByteArray()); flush()
                    }
                    reading.countDown()
                    if (runCatching { input.read() }.getOrDefault(0) == -1) disconnected.countDown()
                }
            }
            val request = launch(Dispatchers.IO) {
                SearchHttp(okhttp3.OkHttpClient(), allowTestUrls = true).json(Request.Builder().url("http://localhost:${server.localPort}/synthetic").build())
            }
            assertTrue(reading.await(5, TimeUnit.SECONDS))
            withTimeout(3000) { request.cancelAndJoin() }
            assertTrue("Cancelled search socket remained open", disconnected.await(3, TimeUnit.SECONDS))
            responder.join(3000)
        }
    }
}
