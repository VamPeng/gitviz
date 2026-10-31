package gitviz

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.AwtWindow
import androidx.compose.ui.window.FrameWindowScope
import com.sun.jna.Callback
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.Structure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

/**
 * macOS 的透明窗口，保留系统标题栏，内容铺到标题栏下面，只留红绿灯。
 * Compose 的 Window() 不允许有边框的窗口透明，这里自己建窗口：
 * 那个检查只看设置透明时的状态，所以创建时临时标成无边框。
 */
@Composable
fun GlassWindow(
    title: String,
    size: DpSize,
    onClose: () -> Unit,
    content: @Composable FrameWindowScope.(glass: Boolean) -> Unit,
) {
    AwtWindow(
        create = {
            ComposeWindow().apply {
                this.title = title
                setSize(size.width.value.toInt(), size.height.value.toInt())
                isLocationByPlatform = true
                isUndecorated = true
                isTransparent = true
                // 不能调 setUndecorated(false)：AWT 不许有边框的 Frame 用半透明背景，而窗口层必须保持透明，
                // 否则会是一层黑底。这里直接改字段，需要 build.gradle.kts 里的 --add-opens
                java.awt.Frame::class.java.getDeclaredField("undecorated").apply { isAccessible = true }
                    .setBoolean(this, false)
                with(rootPane) {
                    putClientProperty("apple.awt.fullWindowContent", true)
                    putClientProperty("apple.awt.transparentTitleBar", true)
                    putClientProperty("apple.awt.windowTitleVisible", false)
                    putClientProperty("Window.shadow", true) // 透明窗口默认没有阴影
                }
                addWindowListener(object : WindowAdapter() {
                    override fun windowClosing(e: WindowEvent) = onClose()
                })
                setContent {
                    var glass by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) {
                        while (!window.isShowing) delay(16)
                        glass = Vibrancy.install(window)
                    }
                    content(glass)
                }
            }
        },
        dispose = ComposeWindow::dispose,
    )
}

/**
 * macOS 毛玻璃：在窗口内容下面插一个 NSVisualEffectView。
 * AppKit 只能在主线程操作，而 AWT 的事件线程不是主线程，所以要派发到主队列。
 */
object Vibrancy {
    private const val MATERIAL_SIDEBAR = 7L
    private const val BLENDING_BEHIND_WINDOW = 0L
    private const val STATE_ACTIVE = 1L
    private const val RESIZE_WIDTH_AND_HEIGHT = 18L
    private const val ORDER_BELOW = -1L

    private val process by lazy { NativeLibrary.getProcess() }
    private val msgSend by lazy { process.getFunction("objc_msgSend") }

    private var pendingTask: MainThreadTask? = null // 派发期间要留着引用，否则回调可能被 GC

    private fun interface MainThreadTask : Callback {
        fun invoke(context: Pointer?)
    }

    @Structure.FieldOrder("x", "y", "width", "height")
    class NSRect(
        @JvmField var x: Double = 0.0,
        @JvmField var y: Double = 0.0,
        @JvmField var width: Double = 0.0,
        @JvmField var height: Double = 0.0,
    ) : Structure(), Structure.ByValue

    /** 返回是否成功；失败时调用方应该退回不透明的样式。 */
    suspend fun install(window: ComposeWindow): Boolean {
        val done = CompletableDeferred<Boolean>()
        val task = MainThreadTask { done.complete(runCatching { insertEffectView(window) }.getOrDefault(false)) }
        pendingTask = task
        runCatching {
            val mainQueue = process.getGlobalVariableAddress("_dispatch_main_q")
            process.getFunction("dispatch_async_f").invokeVoid(arrayOf(mainQueue, null, task))
        }.onFailure { done.complete(false) }
        return done.await().also { pendingTask = null }
    }

    private fun insertEffectView(window: ComposeWindow): Boolean {
        val nsWindow = findNSWindow(window.title) ?: return false
        val content = nsWindow.send("contentView") ?: return false
        val frame = content.send("superview") ?: return false
        val bounds = NSRect(width = window.width.toDouble(), height = window.height.toDouble())
        val effect = objcClass("NSVisualEffectView").send("alloc")?.send("initWithFrame:", bounds) ?: return false
        effect.call("setMaterial:", MATERIAL_SIDEBAR)
        effect.call("setBlendingMode:", BLENDING_BEHIND_WINDOW)
        effect.call("setState:", STATE_ACTIVE)
        effect.call("setAutoresizingMask:", RESIZE_WIDTH_AND_HEIGHT)
        frame.call("addSubview:positioned:relativeTo:", effect, ORDER_BELOW, content)
        return true
    }

    private fun findNSWindow(title: String): Pointer? {
        val windows = objcClass("NSApplication").send("sharedApplication")?.send("windows") ?: return null
        val count = msgSend.invokeLong(arrayOf(windows, selector("count")))
        return (0 until count).mapNotNull { windows.send("objectAtIndex:", it) }
            .firstOrNull { it.send("title")?.send("UTF8String")?.getString(0, "UTF-8") == title }
    }

    private fun objcClass(name: String) = process.getFunction("objc_getClass").invokePointer(arrayOf(name))
    private fun selector(name: String) = process.getFunction("sel_registerName").invokePointer(arrayOf(name))

    private fun Pointer.send(name: String, vararg args: Any?): Pointer? =
        msgSend.invokePointer(arrayOf(this, selector(name), *args))

    private fun Pointer.call(name: String, vararg args: Any?) =
        msgSend.invokeVoid(arrayOf(this, selector(name), *args))
}
