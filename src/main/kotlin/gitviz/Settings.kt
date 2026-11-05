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

/** 界面语言。默认英文。 */
internal enum class Language { EN, ZH }

/** 按当前语言选一句界面文字。在 Compose 里读到时，切换语言会自动重组。 */
internal fun t(en: String, zh: String): String = if (Settings.language == Language.ZH) zh else en

/** 英文里的数量，比如 1 file、3 files。 */
internal fun countOf(n: Int, one: String, many: String = one + "s"): String = "$n ${if (n == 1) one else many}"

/** 保存在系统偏好设置里的用户选项。 */
internal object Settings {
    private val prefs = Preferences.userRoot().node("gitviz")

    /** 测试里可以直接设，不写进偏好设置。 */
    var language by mutableStateOf(Language.entries.firstOrNull { it.name == prefs.get("language", "") } ?: Language.EN)
        internal set

    var rowHeight by mutableStateOf(prefs.getInt("rowHeight", 28))
        private set

    var defaultDiffMode by mutableStateOf(if (prefs.getBoolean("splitDiff", false)) DiffMode.Split else DiffMode.Unified)
        private set

    private var recent by mutableStateOf(prefs.get("recentRepos", "").split(PathSeparator).filter { it.isNotEmpty() })

    /** 最近打开的仓库，已经不存在的不显示。 */
    val recentRepos: List<String> get() = recent.filter { File(it).isDirectory }

    fun updateLanguage(value: Language) {
        language = value
        prefs.put("language", value.name)
    }

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
        Text(t("Settings", "设置"), fontSize = TypeScale.Title, fontWeight = FontWeight.SemiBold)
        Section(t("Appearance", "外观")) {
            OptionRow(t("Language", "语言"), "") {
                Segmented(listOf(Language.EN to "English", Language.ZH to "中文"), Settings.language, onSelect = Settings::updateLanguage)
            }
            OptionRow(t("Theme", "主题"), t("Light or dark", "应用的明暗")) {
                Segmented(listOf(false to t("Light", "浅色"), true to t("Dark", "深色")), Palette.isDark) { if (it != Palette.isDark) Palette.toggle() }
            }
            OptionRow(t("Commit row height", "提交列表行高"), t("Compact, standard or relaxed", "紧凑、标准或宽松")) {
                Segmented(listOf(28 to t("Compact", "紧凑"), 32 to t("Standard", "标准"), 36 to t("Relaxed", "宽松")), Settings.rowHeight, onSelect = Settings::updateRowHeight)
            }
            OptionRow(t("Default diff view", "默认 diff 视图"), t("Used when you open a file", "打开文件时使用的视图")) {
                Segmented(listOf(DiffMode.Unified to t("Unified", "统一"), DiffMode.Split to t("Split", "并排")), Settings.defaultDiffMode, onSelect = Settings::updateDiffMode)
            }
        }
        if (cli != null) {
            val identity by produceState<List<String?>?>(null, cli) {
                value = withContext(Dispatchers.IO) {
                    listOf("user.name", "user.email", "commit.gpgsign").map(cli::config)
                }
            }
            Section(t("Git (read from git config, display only)", "Git（读取自 git config，这里只显示）")) {
                val loaded = identity
                OptionRow(t("Name", "用户名"), "") { Value(if (loaded == null) t("Loading…", "读取中…") else loaded[0] ?: t("Not set", "未设置")) }
                OptionRow(t("Email", "邮箱"), "") { Value(if (loaded == null) t("Loading…", "读取中…") else loaded[1] ?: t("Not set", "未设置")) }
                OptionRow(t("Sign commits", "提交时签名"), t("Change with git config commit.gpgsign", "用 git config commit.gpgsign 修改")) {
                    Value(if (loaded == null) t("Loading…", "读取中…") else if (loaded[2] == "true") t("On", "开启") else t("Off", "关闭"))
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
