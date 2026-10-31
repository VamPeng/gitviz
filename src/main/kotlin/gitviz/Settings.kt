package gitviz

import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.prefs.Preferences

private const val MAX_RECENT = 8
private const val PathSeparator = "\n"

/** 新打开的放最前面，已有的挪到最前，超过上限丢掉最旧的。 */
internal fun pushRecent(list: List<String>, path: String, max: Int = MAX_RECENT): List<String> =
    (listOf(path) + list.filter { it != path }).take(max)

/** 保存在系统偏好设置里的用户选项。 */
internal object Settings {
    private val prefs = Preferences.userRoot().node("gitviz")

    var rowHeight by mutableStateOf(prefs.getInt("rowHeight", 28))
        private set

    var defaultDiffMode by mutableStateOf(if (prefs.getBoolean("splitDiff", false)) DiffMode.Split else DiffMode.Unified)
        private set

    private var recent by mutableStateOf(prefs.get("recentRepos", "").split(PathSeparator).filter { it.isNotEmpty() })

    /** 最近打开的仓库，已经不存在的不显示。 */
    val recentRepos: List<String> get() = recent.filter { File(it).isDirectory }

    fun updateRowHeight(height: Int) {
        rowHeight = height
        prefs.putInt("rowHeight", height)
    }

    fun updateDiffMode(mode: DiffMode) {
        defaultDiffMode = mode
        prefs.putBoolean("splitDiff", mode == DiffMode.Split)
    }

    fun addRecentRepo(path: String) {
        recent = pushRecent(recent, path)
        prefs.put("recentRepos", recent.joinToString(PathSeparator))
    }
}

@Composable
internal fun SettingsView(cli: GitCli?, modifier: Modifier) {
    Column(modifier.padding(horizontal = 32.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text("设置", fontSize = TypeScale.Title, fontWeight = FontWeight.SemiBold)
        Section("外观") {
            OptionRow("主题", "应用的明暗") {
                Segmented(listOf(false to "浅色", true to "深色"), Palette.isDark) { if (it != Palette.isDark) Palette.toggle() }
            }
            OptionRow("提交列表行高", "紧凑、标准或宽松") {
                Segmented(listOf(28 to "紧凑", 32 to "标准", 36 to "宽松"), Settings.rowHeight, onSelect = Settings::updateRowHeight)
            }
            OptionRow("默认 diff 视图", "打开文件时使用的视图") {
                Segmented(listOf(DiffMode.Unified to "统一", DiffMode.Split to "并排"), Settings.defaultDiffMode, onSelect = Settings::updateDiffMode)
            }
        }
        if (cli != null) {
            val identity by produceState<List<String?>?>(null, cli) {
                value = withContext(Dispatchers.IO) {
                    listOf("user.name", "user.email", "commit.gpgsign").map(cli::config)
                }
            }
            Section("Git（读取自 git config，这里只显示）") {
                val loaded = identity
                OptionRow("用户名", "") { Value(if (loaded == null) "读取中…" else loaded[0] ?: "未设置") }
                OptionRow("邮箱", "") { Value(if (loaded == null) "读取中…" else loaded[1] ?: "未设置") }
                OptionRow("提交时签名", "用 git config commit.gpgsign 修改") {
                    Value(if (loaded == null) "读取中…" else if (loaded[2] == "true") "开启" else "关闭")
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.widthIn(max = 640.dp)) {
        Text(title, fontSize = TypeScale.Small, color = Palette.Muted, modifier = Modifier.padding(bottom = 6.dp))
        HorizontalDivider()
        content()
    }
}

@Composable
private fun OptionRow(label: String, hint: String, control: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = TypeScale.Body)
            if (hint.isNotEmpty()) Text(hint, fontSize = TypeScale.Small, color = Palette.Muted)
        }
        control()
    }
    HorizontalDivider()
}

@Composable
private fun Value(text: String) {
    Text(text, fontSize = TypeScale.Small, fontFamily = CodeFont, color = Palette.Muted)
}
