package gitviz

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class ReflogEntry(val ref: String, val oldId: String, val newId: String, val message: String, val actor: String, val time: Long)
internal data class ReflogPage(val entries: List<ReflogEntry>, val hasMore: Boolean)

@Composable
internal fun ReflogView(repo: GitRepo, actions: GitActions, onOpen: (String) -> Unit, modifier: Modifier, busy: Boolean) {
    var referenceError by remember(repo) { mutableStateOf<String?>(null) }
    val references by produceState<List<String>>(listOf("HEAD"), repo) {
        try {
            value = withContext(Dispatchers.IO) { listOf("HEAD") + repo.repository.refDatabase.refs.map { it.name }.filter { it.startsWith("refs/heads/") || it.startsWith("refs/remotes/") || it == "refs/stash" } }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { referenceError = e.message ?: e.toString() }
    }
    var ref by remember(repo.repository.directory) { mutableStateOf("HEAD") }
    var limit by remember(repo, ref) { mutableStateOf(200) }
    var revision by remember { mutableStateOf(0) }
    var choosing by remember(repo) { mutableStateOf(false) }
    var picked by remember(repo, ref, revision) { mutableStateOf<ReflogEntry?>(null) }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(t("Reflog · recover commits", "Reflog · 找回提交"), fontSize = TypeScale.Heading)
            Spacer(Modifier.weight(1f))
            Box {
                TextButton(onClick = { choosing = true }) { Text(ref) }
                DropdownMenu(choosing, { choosing = false }) {
                    references.distinct().forEach { name ->
                        DropdownMenuItem(text = { Text(name) }, onClick = { ref = name; choosing = false })
                    }
                }
            }
            TextButton(onClick = { revision++ }) { Text(t("Refresh", "刷新")) }
        }
        referenceError?.let { Text(it, color = Palette.Removed, modifier = Modifier.padding(12.dp)) }
        HorizontalDivider()
        Loaded(Triple(repo, ref, revision to limit), Modifier.weight(1f).fillMaxWidth(), { repo.reflog(ref, limit) }) { page ->
            if (page.entries.isEmpty()) Message(t("No reflog entries for this reference", "该引用没有 Reflog 记录"), Modifier.weight(1f).fillMaxWidth())
            else LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                itemsIndexed(page.entries) { i, entry ->
                    Column(Modifier.fillMaxWidth().rowHighlight(picked == entry).clickable { picked = entry }.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text("${entry.ref}@{$i} · ${entry.message}", fontSize = TypeScale.Body)
                        Text("${entry.oldId.take(8)} → ${entry.newId.take(8)} · ${entry.actor} · ${formatTime(entry.time)}", fontSize = TypeScale.Small, color = Palette.Muted)
                    }
                }
                if (page.hasMore) item { TextButton(onClick = { limit += 200 }) { Text(t("Load more", "加载更多")) } }
            }
        }
        picked?.let { entry ->
            HorizontalDivider()
            Row(Modifier.padding(8.dp)) {
                TextButton(onClick = { onOpen(entry.newId) }, enabled = entry.newId.any { it != '0' }) { Text(t("View commit", "查看提交")) }
                TextButton(onClick = { onOpen(entry.oldId) }, enabled = entry.oldId.any { it != '0' }) { Text(t("View previous", "查看操作前提交")) }
                listOf(entry.oldId to t("Recover before", "从操作前恢复"), entry.newId to t("Recover after", "从操作后恢复")).forEach { (id, label) ->
                    TextButton(enabled = !busy && id.any { it != '0' }, onClick = {
                        actions.ask(DialogSpec(label, "${id.take(12)} · ${entry.message}", t("Create branch", "创建分支"), input = t("Branch name", "分支名")) { name, _ ->
                            actions.run(t("Recovering", "正在恢复"), t("Recovery branch created", "已创建恢复分支")) { createBranch(name, id) }
                        })
                    }) { Text(label) }
                }
            }
        }
    }
}
