package gitviz

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*

@Composable internal fun BlameTraceView(repo: GitRepo, commit: CommitInfo, file: FileChange, onOpenCommit: (String) -> Unit, modifier: Modifier, parentIndex: Int = 0) {
    key(repo, commit.id, file, parentIndex) {
        Loaded(Triple(repo, commit.id, file), modifier, { if (file.type == org.eclipse.jgit.diff.DiffEntry.ChangeType.DELETE) repo.beforeDeletion(commit, file, parentIndex) else TraceLocation(commit.id, file.path) }) { initial ->
            TraceContents(repo, initial, onOpenCommit, modifier)
        }
    }
}
@Composable private fun TraceContents(repo: GitRepo, initial: TraceLocation, onOpenCommit: (String) -> Unit, modifier: Modifier) {
    var history by remember { mutableStateOf(listOf(initial)) }
    var position by remember { mutableStateOf(0) }
    val current = history[position]
    var picked by remember(current) { mutableStateOf<BlameLine?>(null) }
    var parent by remember(picked?.commitId) { mutableStateOf(0) }
    var notice by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val positions = remember { mutableMapOf<TraceLocation, Pair<Int, Int>>() }
    fun savePosition() { positions[current] = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
    Column(modifier) {
        Row(Modifier.fillMaxWidth()) {
            TextButton(enabled = position > 0 && !busy, onClick = { savePosition(); position--; notice = "" }) { Text(t("Back", "后退")) }
            TextButton(enabled = position < history.lastIndex && !busy, onClick = { savePosition(); position++; notice = "" }) { Text(t("Forward", "前进")) }
            Text("${current.commit.take(8)} · ${current.path}" + if (current.firstLine > 0) " · ${current.firstLine}–${current.lastLine}" else "", fontSize = TypeScale.Small, modifier = Modifier.padding(12.dp))
        }
        picked?.let { line ->
            Loaded(repo to line.commitId, Modifier.fillMaxWidth().heightIn(min = 36.dp), { repo.commit(line.commitId) }) { source ->
                Row {
                    TextButton(onClick = { onOpenCommit(line.commitId) }) { Text(t("Open source commit", "打开来源提交") + " ${line.commitId.take(8)}") }
                    if (source.parents.size > 1) Segmented(source.parents.indices.map { it to t("Parent ${it + 1}", "父版本 ${it + 1}") }, parent, onSelect = { if (!busy) parent = it })
                    TextButton(enabled = !busy, onClick = {
                        val selectedLine = line
                        val selectedParent = parent
                        savePosition()
                        busy = true
                        scope.launch {
                            try {
                                val result = withContext(Dispatchers.IO) { repo.tracePrevious(selectedLine, selectedParent) }
                                notice = result.message
                                result.location?.let { history = history.take(position + 1) + it; position++ }
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { notice = e.message.orEmpty() }
                            finally { busy = false }
                        }
                    }) { Text(t("Previous version", "上一版本")) }
                }
            }
        }
        Text(notice.ifEmpty { t("Select a line to trace its source path and line number", "选择一行，按其来源路径与行号连续追溯") }, fontSize = TypeScale.Small, color = Palette.Muted, modifier = Modifier.padding(horizontal = 12.dp))
        HorizontalDivider()
        val body = Modifier.weight(1f).fillMaxWidth()
        Loaded(repo to current, body, { repo.blame(current.commit, current.path) }) { lines ->
            if (lines.isEmpty()) Message(t("Binary or empty file", "二进制文件或空文件"), body)
            else {
                LaunchedEffect(current, lines) {
                    val saved = positions[current]
                    listState.scrollToItem((saved?.first ?: (current.firstLine - 1).coerceAtLeast(0)).coerceAtMost(lines.lastIndex), saved?.second ?: 0)
                }
                CodeList(lines.maxOf { it.text.length }, 270.dp, body, listState) {
                itemsIndexed(lines) { i, line ->
                    val first = i == 0 || lines[i - 1].commitId != line.commitId
                    val inRange = current.firstLine > 0 && line.lineNo in current.firstLine..current.lastLine
                    Row(Modifier.fillMaxWidth().height(22.dp).background(if (picked == line || inRange) Palette.Raised else Palette.Panel).clickable(enabled = !busy) { picked = line }) {
                        Text(if (first) "${line.commitId.take(7)} ${line.author}" else "", fontFamily = CodeFont, fontSize = TypeScale.Small, color = Palette.Link, modifier = Modifier.width(220.dp).padding(start = 12.dp))
                        LineNumber(line.lineNo)
                        CodeText(line.text, Palette.Ink)
                    }
                }
            }
            }
        }
    }
}
