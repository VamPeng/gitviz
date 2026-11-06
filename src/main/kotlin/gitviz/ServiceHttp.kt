package gitviz

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.Flow
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.serialization.json.*

internal fun serviceUri(value: String): URI {
    val uri = try { URI(value) } catch (_: Exception) { error(t("Enter a valid service URL", "请输入有效的服务 URL")) }
    require(uri.host != null && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
        (uri.scheme == "https" || uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1", "[::1]"))) {
        t("Use HTTPS, or HTTP on localhost; credentials must not appear in the URL", "请使用 HTTPS 或本机 HTTP，URL 中不得包含凭证")
    }
    require(uri.path.split('/').none { it == "." || it == ".." }) { "Service URL cannot contain relative path segments" }
    return uri
}

internal data class ServiceReply(val status: Int, val headers: Map<String, List<String>>, val body: String) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()
    fun requireSuccess(): String {
        check(status in 200..299) { when (status) {
            401 -> t("Authentication failed. Check the token.", "身份验证失败，请检查令牌。")
            403 -> t("Access denied or rate limited. Check permissions and try later.", "访问被拒绝或已限流，请检查权限后稍后重试。")
            404 -> t("Repository or resource not found, or not accessible to this token.", "仓库或资源不存在，或此令牌无权访问。")
            429 -> t("Rate limited. Wait before trying again.", "请求已限流，请等待后重试。")
            in 300..399 -> t("Service redirected the request. Check the configured URL; credentials were not forwarded.", "服务重定向了请求，请检查配置的 URL；凭证未被转发。")
            else -> t("Service returned HTTP $status", "服务返回 HTTP $status")
        } }
        return body
    }
}

/** No redirect following, bounded body, finite timeout, and no logging of credentials or response errors. */
internal object ServiceHttp {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build()
    fun request(uri: URI, headers: Map<String, String> = emptyMap(), body: String? = null, timeoutSeconds: Long = 45): ServiceReply {
        serviceUri(uri.toString().substringBefore('?'))
        val builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(timeoutSeconds)).header("Accept", "application/json").header("User-Agent", "GitViz")
        try { headers.forEach { (key, value) -> builder.header(key, value) } }
        catch (_: IllegalArgumentException) {
            // HttpClient's validation error can include the entire Authorization value.
            error(t("Invalid request header. Check the token for unsupported characters.", "请求头无效，请检查令牌是否包含不支持的字符。"))
        }
        if (body == null) builder.GET() else builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body))
        // The future completes only after LimitedBody finishes. HttpRequest.timeout alone
        // stops guarding the exchange once response headers have arrived on JDK 17.
        val pending = client.sendAsync(builder.build()) { LimitedBody(8 * 1024 * 1024) }
        val response = try { pending.get(timeoutSeconds, TimeUnit.SECONDS) }
        catch (_: TimeoutException) {
            pending.cancel(true)
            error(t("Service request timed out", "服务请求超时"))
        }
        catch (_: InterruptedException) {
            pending.cancel(true)
            Thread.currentThread().interrupt()
            throw java.util.concurrent.CancellationException("Request cancelled")
        }
        catch (e: ExecutionException) {
            if (e.cause is java.net.http.HttpTimeoutException) error(t("Service request timed out", "服务请求超时"))
            error(t("Service connection failed or response exceeded 8 MiB", "服务连接失败或响应超过 8 MiB"))
        }
        return ServiceReply(response.statusCode(), response.headers().map(), response.body().decodeToString())
    }

    private class LimitedBody(private val limit: Int) : HttpResponse.BodySubscriber<ByteArray> {
        private val delegate = HttpResponse.BodySubscribers.ofByteArray()
        private var subscription: Flow.Subscription? = null
        private var received = 0L
        override fun getBody() = delegate.body
        override fun onSubscribe(value: Flow.Subscription) { subscription = value; delegate.onSubscribe(value) }
        override fun onNext(items: List<ByteBuffer>) {
            received += items.sumOf { it.remaining().toLong() }
            if (received > limit) { subscription?.cancel(); delegate.onError(java.io.IOException("Response too large")) }
            else delegate.onNext(items)
        }
        override fun onError(error: Throwable) = delegate.onError(error)
        override fun onComplete() = delegate.onComplete()
    }
}

internal fun parseServiceJson(text: String): JsonElement = try { Json.parseToJsonElement(text) }
catch (_: Exception) { error(t("Service returned invalid JSON", "服务返回了无效 JSON")) }
internal fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
internal fun encodeUrlSegment(value: String): String = java.net.URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
