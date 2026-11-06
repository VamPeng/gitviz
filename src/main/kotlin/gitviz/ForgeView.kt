package gitviz

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*

@Composable internal fun ForgeView(cli: GitCli, modifier: Modifier = Modifier) {
    var provider by remember(cli) { mutableStateOf(ForgeProvider.GITHUB) }
    var server by remember(cli) { mutableStateOf("https://github.com") }
    var project by remember(cli) { mutableStateOf("") }
    var token by remember(cli, provider, server) { mutableStateOf("") }
    var category by remember(cli) { mutableStateOf(ForgeCategory.REVIEWS) }
    var edited by remember(cli) { mutableStateOf(false) }
    LaunchedEffect(cli) {
        val remote = runInterruptible(Dispatchers.IO) { cli.config("remote.origin.url")?.let(::parseRemoteProject) }
        if (!edited && remote?.host in setOf("github.com", "gitlab.com")) {
            provider = if (remote!!.host == "github.com") ForgeProvider.GITHUB else ForgeProvider.GITLAB
            server = "https://${remote.host}"; project = remote.path
        }
    }
    val target = ForgeTarget(provider, server, project)
    var request by remember(cli, target, category) { mutableStateOf<Pair<Int, Int>?>(null) }
    var result by remember(cli, target, category) { mutableStateOf<ForgePage?>(null) }
    var error by remember(cli, target, category) { mutableStateOf<String?>(null) }
    var loading by remember(cli, target, category) { mutableStateOf(false) }
    LaunchedEffect(cli, target, category, request) {
        val next = request ?: return@LaunchedEffect
        loading = true; error = null
        val authorization = token
        try { result = runInterruptible(Dispatchers.IO) { ForgeClient().load(target, category, next.first, authorization) } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: t("Request failed", "请求失败") }
        finally { loading = false }
    }
    val valid = remember(target) { runCatching { target.validate() }.isSuccess }
    fun load(page: Int) { request = page to ((request?.second ?: 0) + 1) }
    val uriHandler = LocalUriHandler.current
    Column(modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t("Hosting platform", "托管平台"), fontSize = TypeScale.Heading, modifier = Modifier.weight(1f))
            ForgeProvider.entries.forEach { choice -> TextButton(onClick = {
                edited = true; provider = choice; server = if (choice == ForgeProvider.GITHUB) "https://github.com" else "https://gitlab.com"
            }) { Text(if (choice == ForgeProvider.GITHUB) "GitHub" else "GitLab", color = if (provider == choice) Palette.Accent else Palette.Muted) } }
        }
        Text(t("Read-only. Refresh and paging contact the selected service; no local source files are sent. Tokens stay in this page's memory and are cleared when the host or repository view changes.", "只读。刷新与翻页时联系所选服务，不发送本地源码。令牌只保留在本页内存中，切换服务地址或离开仓库页面后清除。"), fontSize = TypeScale.Small, color = Palette.Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(server, { edited = true; server = it }, singleLine = true, label = { Text(t("Server URL (enterprise / self-hosted supported)", "服务 URL（支持企业版／自托管）")) }, modifier = Modifier.weight(1f).textInputFocus())
            OutlinedTextField(project, { edited = true; project = it }, singleLine = true, label = { Text(t("Owner / repository", "所有者／仓库")) }, modifier = Modifier.weight(1f).textInputFocus())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(token, { token = it }, label = { Text(t("Access token (optional for public repositories)", "访问令牌（公开仓库可留空）")) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.weight(1f).textInputFocus())
            TextButton(enabled = valid && !loading, onClick = { load(1) }) { Text(t("Refresh", "刷新")) }
            if (loading) TextButton(onClick = { request = null }) { Text(t("Cancel", "取消")) }
        }
        if (valid) Text("API: ${target.api()}", fontSize = TypeScale.Small, color = Palette.Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ForgeCategory.entries.forEach { choice -> TextButton(onClick = { category = choice }) { Text(when (choice) {
                ForgeCategory.REVIEWS -> if (provider == ForgeProvider.GITHUB) "Pull requests" else "Merge requests"
                ForgeCategory.CI -> if (provider == ForgeProvider.GITHUB) "Actions" else "Pipelines"
                ForgeCategory.ISSUES -> "Issues"
            }, color = if (category == choice) Palette.Accent else Palette.Muted) } }
            Spacer(Modifier.weight(1f))
            result?.let { page ->
                TextButton(enabled = !loading && page.page > 1, onClick = { load(page.page - 1) }) { Text(t("Previous", "上一页")) }
                Text(t("Page ${page.page}", "第 ${page.page} 页"), fontSize = TypeScale.Small, modifier = Modifier.align(Alignment.CenterVertically))
                TextButton(enabled = !loading && page.hasNext, onClick = { load(page.page + 1) }) { Text(t("Next", "下一页")) }
            }
        }
        HorizontalDivider()
        error?.let { Text(it, fontSize = TypeScale.Small, color = Palette.Removed) }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Palette.Accent)
        val page = result
        when {
            page == null -> Message(t("Choose a repository, then refresh", "选择仓库后点击刷新"), Modifier.weight(1f).fillMaxWidth())
            page.items.isEmpty() -> Message(t("No items on this page", "本页没有条目"), Modifier.weight(1f).fillMaxWidth())
            else -> LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(page.items, key = { it.id }) { item ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("#${item.id}  ${item.title}", fontSize = TypeScale.Body)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(listOf(item.state, item.author, item.updated).filter { it.isNotBlank() }.joinToString(" · "), fontSize = TypeScale.Small, color = Palette.Muted, modifier = Modifier.weight(1f))
                            TextButton(onClick = { uriHandler.openUri(item.url) }) { Text(t("Open details", "打开详情")) }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
