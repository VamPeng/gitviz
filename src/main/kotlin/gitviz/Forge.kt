package gitviz

import java.net.URI
import kotlinx.serialization.json.*

internal enum class ForgeProvider { GITHUB, GITLAB }
internal enum class ForgeCategory { REVIEWS, CI, ISSUES }
internal data class RemoteProject(val host: String, val path: String)

internal fun parseRemoteProject(remote: String): RemoteProject? = runCatching {
    val uri = if ("://" in remote) URI(remote) else {
        val match = Regex("(?:[^@/:]+@)?([^/:]+):(.+)").matchEntire(remote) ?: return null
        URI("ssh", null, match.groupValues[1], -1, "/" + match.groupValues[2], null, null)
    }
    if (uri.scheme !in setOf("https", "http", "ssh", "git") || uri.host == null) return null
    val path = uri.path.trim('/').removeSuffix(".git")
    if (path.split('/').size < 2 || path.split('/').any { it.isBlank() || it in setOf(".", "..") }) return null
    RemoteProject(uri.host.lowercase(), path)
}.getOrNull()

internal data class ForgeTarget(val provider: ForgeProvider, val server: String, val project: String) {
    val base: URI get() = serviceUri(server.trimEnd('/'))
    fun validate() {
        base
        val parts = project.split('/')
        require(parts.size >= 2 && (provider != ForgeProvider.GITHUB || parts.size == 2) && parts.none { it.isBlank() || it in setOf(".", "..") || '\\' in it || it.any(Char::isISOControl) }) {
            t("Enter owner/repository (GitLab also accepts nested groups)", "请输入所有者／仓库；GitLab 支持多级群组")
        }
    }
    fun api(): String {
        validate()
        return if (provider == ForgeProvider.GITHUB && base.host == "github.com" && base.path.isNullOrEmpty()) "https://api.github.com"
        else server.trimEnd('/') + if (provider == ForgeProvider.GITHUB) "/api/v3" else "/api/v4"
    }
}
internal data class ForgeItem(val id: String, val title: String, val state: String, val author: String, val updated: String, val url: String)
internal data class ForgePage(val items: List<ForgeItem>, val page: Int, val hasNext: Boolean)
internal typealias ForgeTransport = (URI, Map<String, String>) -> ServiceReply

internal class ForgeClient(private val transport: ForgeTransport = { uri, headers -> ServiceHttp.request(uri, headers) }) {
    fun load(target: ForgeTarget, category: ForgeCategory, page: Int, token: String): ForgePage {
        target.validate(); require(page in 1..10000)
        val github = target.provider == ForgeProvider.GITHUB
        val project = if (github) target.project.split('/').joinToString("/", transform = ::encodeUrlSegment) else encodeUrlSegment(target.project)
        val resource = when (category) { ForgeCategory.REVIEWS -> if (github) "pulls" else "merge_requests"; ForgeCategory.CI -> if (github) "actions/runs" else "pipelines"; ForgeCategory.ISSUES -> "issues" }
        val state = if (category == ForgeCategory.CI) "" else if (github) "&state=open" else "&state=opened"
        val uri = URI(target.api() + (if (github) "/repos/" else "/projects/") + project + "/$resource?per_page=50&page=$page$state")
        val headers = buildMap {
            if (github) put("X-GitHub-Api-Version", "2022-11-28")
            if (token.isNotBlank()) put(if (github) "Authorization" else "PRIVATE-TOKEN", if (github) "Bearer $token" else token)
        }
        val reply = transport(uri, headers)
        val json = parseServiceJson(reply.requireSuccess())
        val array = (if (github && category == ForgeCategory.CI) (json as? JsonObject)?.get("workflow_runs") else json) as? JsonArray
            ?: error(t("Unexpected service response", "服务响应格式不符合预期"))
        val items = array.mapNotNull { it as? JsonObject }.filterNot { github && category == ForgeCategory.ISSUES && "pull_request" in it }.map { obj ->
            val id = obj.text(if (category == ForgeCategory.CI) "id" else if (github) "number" else "iid")
            check(id.matches(Regex("[0-9]+"))) { "Service returned an invalid item number" }
            val route = when (category) { ForgeCategory.REVIEWS -> if (github) "pull" else "-/merge_requests"; ForgeCategory.CI -> if (github) "actions/runs" else "-/pipelines"; ForgeCategory.ISSUES -> if (github) "issues" else "-/issues" }
            val title = if (category != ForgeCategory.CI) obj.text("title") else if (github) obj.text("display_title").ifEmpty { obj.text("name") } else obj.text("ref") + " · " + obj.text("sha").take(8)
            val author = (obj[if (github) "user" else "author"] as? JsonObject)?.text(if (github) "login" else "username").orEmpty()
            ForgeItem(id, title.take(2000), obj.text("conclusion").ifEmpty { obj.text("state").ifEmpty { obj.text("status") } }, author.take(200),
                obj.text("updated_at").ifEmpty { obj.text("created_at") }, target.server.trimEnd('/') + "/" + target.project.split('/').joinToString("/", transform = ::encodeUrlSegment) + "/$route/$id")
        }
        val next = if (github) reply.header("Link")?.contains(Regex("rel=\"next\"")) == true
        else reply.header("X-Next-Page")?.toIntOrNull()?.let { it > page } ?: (array.size == 50)
        return ForgePage(items, page, next)
    }
}
