package gitviz

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.prefs.Preferences

/** AWT 和 Compose 窗口均使用逻辑尺寸，Retina 屏不额外乘缩放倍数。 */
internal data class WindowSize(val width: Int, val height: Int) {
    fun fitWithin(width: Int, height: Int) = WindowSize(
        this.width.coerceAtMost(width.coerceAtLeast(1)),
        this.height.coerceAtMost(height.coerceAtLeast(1)),
    )

    companion object {
        val Default = WindowSize(1360, 860)
    }
}

internal class WindowSizeStore(private val prefs: Preferences) {
    fun read(): WindowSize = try {
        val parts = prefs.get("windowSize", "").split("x")
        val width = parts.getOrNull(0)?.toIntOrNull()
        val height = parts.getOrNull(1)?.toIntOrNull()
        if (parts.size == 2 && width != null && height != null && width > 0 && height > 0)
            WindowSize(width, height) else WindowSize.Default
    } catch (_: Exception) {
        WindowSize.Default
    }

    fun save(size: WindowSize) {
        if (size.width <= 0 || size.height <= 0) return
        try {
            // 单个偏好键避免读到不同次缩放的宽和高。
            prefs.put("windowSize", "${size.width}x${size.height}")
            prefs.flush()
        } catch (_: Exception) {
            // 体验优化：写入失败直接放弃，不重试、不影响窗口。
        }
    }
}

internal fun initialWindowSize(): WindowSize {
    val saved = Settings.windowSize.read()
    return try {
        val config = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
        val insets = Toolkit.getDefaultToolkit().getScreenInsets(config)
        saved.fitWithin(
            config.bounds.width - insets.left - insets.right,
            config.bounds.height - insets.top - insets.bottom,
        )
    } catch (_: Exception) {
        WindowSize.Default
    }
}

/** 由窗口事件线程调用；尺寸停止变化 1000ms 后才尝试写入，关闭时不补存或等待。 */
internal class WindowSizeSaver(private val save: (WindowSize) -> Unit) : AutoCloseable {
    private val writer = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "gitviz-window-size").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    private var pending: java.util.concurrent.ScheduledFuture<*>? = null
    private var closed = false

    fun resize(size: WindowSize) {
        if (closed || size.width <= 0 || size.height <= 0) return
        pending?.cancel(false)
        try {
            pending = writer.schedule({
                try { save(size) } catch (_: Exception) { /* 保存失败不影响应用。 */ }
            }, 1000, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            // 无法安排后台保存时直接放弃。
        }
    }

    override fun close() {
        closed = true
        pending?.cancel(false)
        writer.shutdownNow()
    }
}

@Composable
internal fun RememberWindowSize(window: Frame) {
    DisposableEffect(window) {
        val saver = WindowSizeSaver(Settings.windowSize::save)
        fun capture() {
            if (!window.isShowing || window.extendedState and Frame.ICONIFIED != 0) return
            saver.resize(WindowSize(window.width, window.height))
        }
        val listener = object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = capture()
        }
        window.addComponentListener(listener)
        onDispose {
            window.removeComponentListener(listener)
            saver.close()
        }
    }
}
