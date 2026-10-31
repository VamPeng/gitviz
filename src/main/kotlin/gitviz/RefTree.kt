package gitviz

/** 侧边栏树的节点。path 是完整路径；ref 为 null 表示这是文件夹。 */
class RefNode(val name: String, val path: String, val ref: RefTarget?, val children: List<RefNode>)

class RefRow(val node: RefNode, val depth: Int)

/** 按 / 把引用名拆成树，同一层文件夹在前，其余按名字排序。 */
fun buildRefTree(refs: List<RefTarget>, prefix: String = ""): List<RefNode> =
    refs.groupBy { it.label.name.removePrefix(prefix).substringBefore('/') }
        .map { (segment, group) ->
            val path = prefix + segment
            val leaf = group.singleOrNull()?.takeIf { it.label.name == path }
            if (leaf != null) RefNode(segment, path, leaf, emptyList())
            else RefNode(segment, path, null, buildRefTree(group, "$path/"))
        }
        .sortedWith(compareBy({ it.ref != null }, { it.name.lowercase() }))

/** 展开成要显示的行，折叠的文件夹不展开子节点。 */
fun flattenRefTree(nodes: List<RefNode>, depth: Int = 0, isCollapsed: (String) -> Boolean): List<RefRow> =
    nodes.flatMap { node ->
        val children =
            if (node.ref == null && !isCollapsed(node.path)) flattenRefTree(node.children, depth + 1, isCollapsed)
            else emptyList()
        listOf(RefRow(node, depth)) + children
    }
