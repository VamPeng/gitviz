package gitviz

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class ForgeTest {
    @Test fun `remote parsing and targets support SSH nested groups and enterprise safely`() {
        assertEquals(RemoteProject("github.com", "owner/repo"), parseRemoteProject("git@github.com:owner/repo.git"))
        assertEquals(RemoteProject("gitlab.example", "group/sub/repo"), parseRemoteProject("ssh://git@gitlab.example:2222/group/sub/repo.git"))
        assertNull(parseRemoteProject("/tmp/local")); assertNull(parseRemoteProject("https://example/a/../repo"))
        assertEquals("https://api.github.com", ForgeTarget(ForgeProvider.GITHUB, "https://github.com", "o/r").api())
        assertEquals("https://enterprise.test/api/v3", ForgeTarget(ForgeProvider.GITHUB, "https://enterprise.test", "o/r").api())
        assertEquals("https://host.test/gitlab/api/v4", ForgeTarget(ForgeProvider.GITLAB, "https://host.test/gitlab", "group/sub/repo").api())
        listOf("http://external.test", "https://user:secret@host", "https://host?token=secret", "file:///tmp/repo").forEach { assertFails { serviceUri(it) } }
        assertFails { ForgeTarget(ForgeProvider.GITHUB, "https://github.com", "o/r/extra").validate() }
    }

    @Test fun `github filters PRs from issues paginates and constructs safe detail links`() {
        var seen: URI? = null
        val client = ForgeClient { uri, headers ->
            seen = uri; assertEquals("Bearer secret", headers["Authorization"])
            ServiceReply(200, mapOf("Link" to listOf("<https://evil.test/leak>; rel=\"next\"")), """[{"number":1,"title":"issue","state":"open","html_url":"javascript:bad","user":{"login":"dev"}},{"number":2,"title":"PR","pull_request":{}}]""")
        }
        val page = client.load(ForgeTarget(ForgeProvider.GITHUB, "https://github.com", "owner/repo"), ForgeCategory.ISSUES, 2, "secret")
        assertEquals(1, page.items.size); assertTrue(page.hasNext)
        assertEquals("https://github.com/owner/repo/issues/1", page.items.single().url)
        assertEquals("api.github.com", seen!!.host); assertTrue("page=2" in seen!!.query)
    }

    @Test fun `gitlab encodes full project and all list kinds map correctly`() {
        val paths = mutableListOf<String>()
        val client = ForgeClient { uri, headers ->
            paths += uri.rawPath; assertEquals("session-token", headers["PRIVATE-TOKEN"])
            ServiceReply(200, mapOf("X-Next-Page" to listOf("")), """[{"id":10,"iid":2,"title":"review","ref":"main","sha":"abcdef123456","state":"opened","status":"success","author":{"username":"dev"}}]""")
        }
        val target = ForgeTarget(ForgeProvider.GITLAB, "https://gitlab.test", "group/sub/repo")
        ForgeCategory.entries.forEach { category ->
            val page = client.load(target, category, 1, "session-token")
            assertEquals(1, page.items.size); assertFalse(page.hasNext)
            assertTrue(paths.last().contains("group%2Fsub%2Frepo"))
            assertTrue(page.items.single().url.startsWith("https://gitlab.test/group/sub/repo/-/"))
        }
    }

    @Test fun `HTTP integration rejects redirects errors invalid JSON and oversized response without leaking token`() {
        val calls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            calls.incrementAndGet()
            when (exchange.requestURI.path) {
                "/redirect" -> { exchange.responseHeaders.add("Location", "/secret"); exchange.sendResponseHeaders(302, -1) }
                "/large" -> { exchange.sendResponseHeaders(200, 9L * 1024 * 1024); runCatching { exchange.responseBody.write(ByteArray(9 * 1024 * 1024)) } }
                else -> { val data = "{\"message\":\"secret-token\"}".toByteArray(); exchange.sendResponseHeaders(401, data.size.toLong()); exchange.responseBody.write(data) }
            }
            exchange.close()
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val redirect = ServiceHttp.request(URI("$base/redirect"), mapOf("Authorization" to "Bearer secret-token"))
            assertEquals(302, redirect.status); assertEquals(1, calls.get())
            assertFalse(assertFails { redirect.requireSuccess() }.message.orEmpty().contains("secret-token"))
            val unauthorized = ServiceHttp.request(URI(base)); assertFalse(assertFails { unauthorized.requireSuccess() }.message.orEmpty().contains("secret-token"))
            assertFails { ServiceHttp.request(URI("$base/large")) }
            for (code in listOf(403, 404, 429, 500)) assertFails { ServiceReply(code, emptyMap(), "secret-token").requireSuccess() }
            assertFails { parseServiceJson("token <not-json>") }
        } finally { server.stop(0) }
    }
}
