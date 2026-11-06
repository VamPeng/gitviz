package gitviz

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class ServiceHttpTest {
    private fun withOpenBody(drip: Boolean, action: (URI, CountDownLatch, CountDownLatch) -> Unit) {
        val started = CountDownLatch(1); val release = CountDownLatch(1); val disconnected = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            try {
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.write('['.code); exchange.responseBody.flush(); started.countDown()
                if (drip) {
                    while (!release.await(75, TimeUnit.MILLISECONDS)) {
                        exchange.responseBody.write(' '.code); exchange.responseBody.flush()
                    }
                } else release.await(8, TimeUnit.SECONDS)
            } catch (_: java.io.IOException) { disconnected.countDown() }
            finally { exchange.close() }
        }
        server.start()
        try { action(URI("http://127.0.0.1:${server.address.port}/"), started, disconnected) }
        finally { release.countDown(); server.stop(0) }
    }

    @Test fun `total deadline covers stalled and continuously arriving response bodies`() {
        for (drip in listOf(false, true)) withOpenBody(drip) { uri, started, disconnected ->
            val error = AtomicReference<Throwable?>()
            val worker = Thread { try { ServiceHttp.request(uri, timeoutSeconds = 1) } catch (e: Throwable) { error.set(e) } }.apply { isDaemon = true; start() }
            try {
                assertTrue(started.await(3, TimeUnit.SECONDS))
                worker.join(2500)
                assertFalse(worker.isAlive, "Response body exceeded the total deadline (drip=$drip)")
                assertIs<IllegalStateException>(error.get())
                assertTrue(error.get()!!.message.orEmpty().let { "timed out" in it || "超时" in it })
                if (drip) assertTrue(disconnected.await(3, TimeUnit.SECONDS), "Timed-out request must close the exchange")
            } finally { worker.interrupt(); worker.join(3000) }
        }
    }

    @Test fun `interrupt cancels an open response body and preserves interrupt status`() = withOpenBody(true) { uri, started, disconnected ->
        val error = AtomicReference<Throwable?>()
        val interrupted = java.util.concurrent.atomic.AtomicBoolean()
        val worker = Thread {
            try { ServiceHttp.request(uri) }
            catch (e: Throwable) { error.set(e); interrupted.set(Thread.currentThread().isInterrupted) }
        }.apply { isDaemon = true; start() }
        try {
            assertTrue(started.await(3, TimeUnit.SECONDS)); worker.interrupt(); worker.join(3000)
            assertFalse(worker.isAlive); assertIs<java.util.concurrent.CancellationException>(error.get())
            assertTrue(interrupted.get())
            assertTrue(disconnected.await(3, TimeUnit.SECONDS), "Interrupted request must close the exchange")
        } finally { worker.interrupt(); worker.join(3000) }
    }

    @Test fun `successful body and failed connection keep synchronous results`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val bytes = exchange.requestBody.readAllBytes().takeIf { it.isNotEmpty() } ?: "{}".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        val uri = URI("http://127.0.0.1:${server.address.port}/")
        try {
            assertEquals("{}", ServiceHttp.request(uri).requireSuccess())
            assertEquals("{\"ok\":true}", ServiceHttp.request(uri, body = "{\"ok\":true}").requireSuccess())
        } finally { server.stop(0) }
        val failure = assertFailsWith<IllegalStateException> { ServiceHttp.request(uri, timeoutSeconds = 1) }
        assertTrue(failure.message.orEmpty().let { "connection failed" in it || "连接失败" in it })
    }

    @Test fun `invalid credentials are rejected without exposing their value`() {
        for (header in listOf("Authorization", "PRIVATE-TOKEN")) {
            val error = assertFailsWith<IllegalStateException> {
                ServiceHttp.request(URI("http://127.0.0.1:1/"), mapOf(header to "private-secret\r\ninvalid"))
            }
            assertFalse(error.message.orEmpty().contains("private-secret"))
            assertNull(error.cause)
        }
    }

    @Test fun `slow request times out and interrupted generation cancels promptly`() {
        val received = CountDownLatch(1); val release = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            received.countDown(); release.await(5, TimeUnit.SECONDS)
            runCatching { exchange.sendResponseHeaders(200, 2); exchange.responseBody.write("{}".toByteArray()) }
            exchange.close()
        }
        server.start()
        try {
            val uri = URI("http://127.0.0.1:${server.address.port}/")
            val error = AtomicReference<Throwable?>()
            val worker = Thread { try { ServiceHttp.request(uri) } catch (e: Throwable) { error.set(e) } }.apply { isDaemon = true; start() }
            assertTrue(received.await(3, TimeUnit.SECONDS)); worker.interrupt(); worker.join(3000)
            assertFalse(worker.isAlive); assertIs<java.util.concurrent.CancellationException>(error.get())
            assertFailsWith<IllegalStateException> { ServiceHttp.request(uri, timeoutSeconds = 1) }
        } finally { release.countDown(); server.stop(0) }
    }
}
