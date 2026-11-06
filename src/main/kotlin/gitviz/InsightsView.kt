package gitviz

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import java.io.File
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicLong

@Composable internal fun InsightsView(directory: File, modifier: Modifier) {
    var days by remember(directory) { mutableStateOf(365) }
    var generation by remember(directory) { mutableStateOf(0) }
    var result by remember(directory, days, generation) { mutableStateOf<Result<RepositoryInsights>?>(null) }
    var scanned by remember(directory, days, generation) { mutableStateOf(0L) }
    var stopped by remember(directory, days, generation) { mutableStateOf(false) }
    var task by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(directory, days, generation) {
        val owner = currentCoroutineContext().job
        task = owner
        val progress = AtomicLong()
        val ticker = launch { while (isActive) { scanned = progress.get(); delay(150) } }
        try {
            val through = LocalDate.now()
            result = Result.success(withContext(Dispatchers.IO) {
                collectInsights(directory, if (days == 0) null else through.minusDays(days.toLong() - 1), through,
                    cancelled = { !owner.isActive }, progress = progress::set)
            })
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { result = Result.failure(e) }
        finally { ticker.cancel(); if (task === owner) task = null }
    }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(t("Insights", "统计和洞察"), fontSize = TypeScale.Title, fontWeight = FontWeight.SemiBold)
            Segmented(listOf(30 to t("30 days", "30 天"), 90 to t("90 days", "90 天"), 365 to t("1 year", "一年"), 0 to t("All", "全部")), days, onSelect = { days = it })
            TextButton(onClick = { generation++ }) { Text(t("Refresh", "刷新")) }
            if (task != null) TextButton(onClick = { stopped = true; task?.cancel() }) { Text(t("Cancel", "取消")) }
        }
        Text(t("All locally available branches, tags and HEAD; each commit counted once. Snapshot refresh is manual.", "本地已有分支、标签及 HEAD 可达历史；同一提交只计一次。手动刷新快照。"), fontSize = TypeScale.Small, color = Palette.Muted, modifier = Modifier.padding(horizontal = 16.dp))
        HorizontalDivider(Modifier.padding(top = 10.dp))
        val loaded = result
        when {
            stopped -> Message(t("Calculation cancelled. Refresh to start again.", "已取消计算，点击刷新重新开始。"), Modifier.fillMaxSize())
            loaded == null -> Message(t("Reading history… $scanned commits scanned", "正在读取历史… 已扫描 $scanned 个提交"), Modifier.fillMaxSize())
            loaded.isFailure -> Message(loaded.exceptionOrNull()?.message.orEmpty(), Modifier.fillMaxSize())
            else -> InsightsContents(loaded.getOrThrow(), Modifier.fillMaxWidth().weight(1f))
        }
    }
}

@Composable internal fun InsightsContents(data: RepositoryInsights, modifier: Modifier) {
    var year by remember(data) { mutableStateOf(data.through.year) }
    var contributorLimit by remember(data) { mutableStateOf(30) }
    var fileLimit by remember(data) { mutableStateOf(30) }
    val firstYear = (data.from ?: data.days.keys.minOrNull() ?: data.through).year
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(t("${data.commits} commits · ${data.contributors.size} contributors · ${data.files.size} paths", "${data.commits} 个提交 · ${data.contributors.size} 位贡献者 · ${data.files.size} 个路径"), fontSize = TypeScale.Title)
            Text("${data.from ?: t("Beginning", "最早历史")} → ${data.through} · ${data.zone.id}", color = Palette.Muted, fontSize = TypeScale.Small)
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t("Commit activity", "提交热力图"), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(enabled = year > firstYear, onClick = { year-- }) { Text("‹") }
                Text(year.toString(), fontSize = TypeScale.Body)
                TextButton(enabled = year < data.through.year, onClick = { year++ }) { Text("›") }
            }
            ActivityHeatmap(data, year)
            Text(t("Author dates in the displayed time zone; merges count toward activity and contributors.", "按显示时区的作者日期统计；热力图和贡献者计入合并提交。"), fontSize = TypeScale.Small, color = Palette.Muted)
        }
        if (data.commits == 0L) item { Text(t("No commits in this period.", "这个时间范围内没有提交。"), color = Palette.Muted) }
        item { HorizontalDivider(); Text(t("Contributors · by commit count", "贡献者 · 按提交次数"), fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp)) }
        items(data.contributors.take(contributorLimit), key = { "author:${it.email}:${it.name}" }) { author ->
            ActivityRow("${author.name} <${author.email}>", author.commits, data.contributors.firstOrNull()?.commits ?: 1)
        }
        if (data.contributors.size > contributorLimit) item { TextButton(onClick = { contributorLimit += 30 }) { Text(t("More contributors", "更多贡献者")) } }
        item {
            HorizontalDivider()
            Text(t("Hot files · commits touching each path", "热点文件 · 改动路径的提交次数"), fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
            Text(t("Excludes ${data.merges} merge commits; includes deleted files. Renamed paths are separate. Counts are not a measure of productivity.", "排除 ${data.merges} 个合并提交；包含已删除文件。重命名前后路径分别统计，次数不代表工作量。"), fontSize = TypeScale.Small, color = Palette.Muted)
        }
        items(data.files.take(fileLimit), key = { "file:${it.path}" }) { file -> ActivityRow(file.path, file.commits, data.files.firstOrNull()?.commits ?: 1) }
        if (data.files.size > fileLimit) item { TextButton(onClick = { fileLimit += 30 }) { Text(t("More files", "更多文件")) } }
    }
}

@Composable private fun ActivityRow(label: String, count: Long, maximum: Long) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, modifier = Modifier.weight(1f), fontSize = TypeScale.Body)
        Box(Modifier.width(150.dp).height(5.dp).background(Palette.Line)) {
            Box(Modifier.fillMaxWidth((count.toFloat() / maximum).coerceIn(0f, 1f)).fillMaxHeight().background(Palette.Accent))
        }
        Text(count.toString(), modifier = Modifier.width(65.dp), fontSize = TypeScale.Body)
    }
}

@Composable private fun ActivityHeatmap(data: RepositoryInsights, year: Int) {
    val start = LocalDate.of(year, 1, 1)
    val monday = start.minusDays(start.dayOfWeek.value.toLong() - 1)
    val end = LocalDate.of(year, 12, 31)
    val weeks = ((java.time.temporal.ChronoUnit.DAYS.between(monday, end) + 7) / 7).toInt()
    val maximum = data.days.filterKeys { it.year == year }.values.maxOrNull() ?: 1L
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Spacer(Modifier.height(18.dp))
            repeat(7) { day -> Text(if (day % 2 == 0) t(listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")[day], listOf("一", "二", "三", "四", "五", "六", "日")[day]) else "", fontSize = TypeScale.Caption, color = Palette.Muted, modifier = Modifier.width(32.dp).height(11.dp)) }
        }
        repeat(weeks) { week -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            val weekStart = monday.plusDays(week * 7L)
            val month = (0L..6L).map(weekStart::plusDays).firstOrNull { it.dayOfMonth == 1 && it.year == year }?.monthValue
            Text(month?.toString().orEmpty(), fontSize = 9.sp, softWrap = false, color = Palette.Muted, modifier = Modifier.width(11.dp).height(18.dp))
            repeat(7) { weekday ->
                val date = monday.plusDays((week * 7 + weekday).toLong())
                val inRange = date.year == year && date <= data.through && (data.from == null || date >= data.from)
                val count = data.days[date] ?: 0L
                val color = when { !inRange -> Palette.Ground; count == 0L -> Palette.Line; else -> Palette.Accent.copy(alpha = 0.25f + 0.75f * count.toFloat() / maximum) }
                Hint(if (inRange) "$date · $count " + t("commits", "个提交") else "$date · " + t("outside range", "范围外")) {
                    Box(Modifier.size(11.dp).background(color))
                }
            }
        } }
    }
}
