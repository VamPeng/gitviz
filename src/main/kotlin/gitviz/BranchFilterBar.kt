package gitviz

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun BranchFilterBar(refs: List<RefTarget>, selected: Set<String>, busy: Boolean, onChange: (Set<String>) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            TextButton(enabled = !busy, onClick = { expanded = true }) { Text(if (selected.isEmpty()) t("All branches ▾", "全部分支 ▾") else t("${selected.size} branches ▾", "${selected.size} 个分支 ▾")) }
            DropdownMenu(expanded, { expanded = false }) {
                refs.filter { it.label.kind in setOf(RefKind.BRANCH, RefKind.REMOTE) }.forEach { ref ->
                    val full = (if (ref.label.kind == RefKind.BRANCH) "refs/heads/" else "refs/remotes/") + ref.label.name
                    DropdownMenuItem(text = { Text((if (full in selected) "✓ " else "") + full.removePrefix("refs/")) }, enabled = !busy,
                        onClick = { onChange(if (full in selected) selected - full else selected + full) })
                }
            }
        }
        if (selected.isNotEmpty()) {
            Text(selected.joinToString { it.removePrefix("refs/") }, fontSize = TypeScale.Small, color = Palette.Muted, modifier = Modifier.weight(1f))
            TextButton(enabled = !busy, onClick = { onChange(emptySet()) }) { Text(t("Clear", "清除")) }
        }
    }
}
