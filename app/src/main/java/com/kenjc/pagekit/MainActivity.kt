package com.kenjc.pagekit

import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.kenjc.pagekit.ui.home.HomeScreen
import com.kenjc.pagekit.ui.theme.PageKitTheme
import com.kenjc.pagekit.mcp.McpServerService
import com.kenjc.pagekit.ui.screensaver.AUDIO_RECHECK_MS
import com.kenjc.pagekit.ui.screensaver.ScreensaverSettings
import com.kenjc.pagekit.ui.screensaver.ScreensaverOverlay

class MainActivity : ComponentActivity() {

    // ---- 闲置屏保（防烧屏）：详见 ui/screensaver/ScreensaverOverlay.kt ----
    private var screensaverActive by mutableStateOf(false)
    private val screensaverHandler = Handler(Looper.getMainLooper())
    private val startScreensaver = Runnable { maybeStartScreensaver() }
    private lateinit var screensaverSettings: ScreensaverSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val serviceIntent = Intent(this, McpServerService::class.java)
        // 透传 --es mcp_bind（all/lan/loopback），未指定时 Service 自行决定（默认 all）。
        intent.getStringExtra(McpServerService.EXTRA_BIND_MODE)?.let {
            serviceIntent.putExtra(McpServerService.EXTRA_BIND_MODE, it)
        }
        startForegroundService(serviceIntent)
        enableEdgeToEdge()
        handleIntents(intent)
        screensaverSettings = ScreensaverSettings(this)
        val initialUrl = intent.initialUrl
        val autoExtract = intent.autoExtract
        Log.i("PageKit", "MainActivity: initialUrl=$initialUrl autoExtract=$autoExtract")
        setContent {
            PageKitTheme {
                Box {
                    HomeScreen(
                        initialUrl = initialUrl,
                        autoExtract = autoExtract,
                        onEnterScreensaver = ::enterScreensaverNow,
                    )
                    ScreensaverOverlay(
                        active = screensaverActive,
                        onDismiss = ::dismissScreensaver,
                    )
                }
            }
        }
        // 启动即进入屏保（kiosk 防烧屏）；等 decorView 挂载后隐藏系统栏。
        window.decorView.post { enterScreensaverNow() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntents(intent)
    }

    // ---- 闲置屏保（防烧屏）----

    /**
     * 任意用户交互（触摸/按键/轨迹球，含 WebView 内部的点击与手势）都会回调，
     * 以此重置闲置计时；正在播放音频时不遮挡内容，只推迟屏保。
     * 鼠标事件被 [dispatchTouchEvent]/[dispatchGenericMotionEvent] 闸门拦截，不影响屏保。
     */
    override fun onUserInteraction() {
        super.onUserInteraction()
        if (suppressInteractionForMouse) return
        if (screensaverActive) {
            dismissScreensaver()
        } else {
            scheduleScreensaver()
        }
    }

    /** 鼠标来源事件（点击/悬停/滚轮）不唤醒屏保、不重置闲置计时。 */
    private var suppressInteractionForMouse = false

    private fun MotionEvent.isFromMouse(): Boolean =
        isFromSource(InputDevice.SOURCE_MOUSE) ||
            (pointerCount > 0 && getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE)

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.isFromMouse()) {
            suppressInteractionForMouse = true
            try {
                return super.dispatchTouchEvent(ev)
            } finally {
                suppressInteractionForMouse = false
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.isFromMouse()) {
            suppressInteractionForMouse = true
            try {
                return super.dispatchGenericMotionEvent(ev)
            } finally {
                suppressInteractionForMouse = false
            }
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun onStart() {
        super.onStart()
        // 息屏/切走再回来时恢复屏保应有的沉浸状态
        if (screensaverActive) setSystemBarsVisible(false)
        scheduleScreensaver()
    }

    override fun onStop() {
        super.onStop()
        screensaverHandler.removeCallbacks(startScreensaver)
        setSystemBarsVisible(true)
    }

    override fun onDestroy() {
        super.onDestroy()
        screensaverHandler.removeCallbacks(startScreensaver)
    }

    /** 屏保期间隐藏状态栏/导航栏：SystemUI 的按钮悬浮在应用内容之上，黑色遮罩盖不住，必须整体隐藏才防烧屏。 */
    private fun setSystemBarsVisible(visible: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (visible) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun scheduleScreensaver() {
        screensaverHandler.removeCallbacks(startScreensaver)
        val timeout = screensaverSettings.load()
        if (timeout > 0) screensaverHandler.postDelayed(startScreensaver, timeout)
    }

    /** 手动/启动立即进入屏保：跳过音频检查（显式意图优先）。 */
    fun enterScreensaverNow() {
        screensaverActive = true
        setSystemBarsVisible(false)
    }

    private fun maybeStartScreensaver() {
        if (isAudioActive()) {
            // 正在播放音频/视频：不遮挡内容，稍后复查。
            screensaverHandler.postDelayed(startScreensaver, AUDIO_RECHECK_MS)
            return
        }
        screensaverActive = true
        setSystemBarsVisible(false)
    }

    private fun isAudioActive(): Boolean =
        runCatching {
            (getSystemService(AUDIO_SERVICE) as AudioManager).isMusicActive
        }.getOrDefault(false)

    private fun dismissScreensaver() {
        screensaverHandler.removeCallbacks(startScreensaver)
        screensaverActive = false
        setSystemBarsVisible(true)
        // 首次触摸只用于退出屏保；重新起表，防止误点到底层 UI。
        scheduleScreensaver()
    }

    private fun handleIntents(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            // adb 浏览器控制固定操作 UI 的 default Session。
            CONTROL_ACTION -> {
                val op = intent.getStringExtra("op") ?: return
                val eid = intent.getStringExtra("eid")
                val text = intent.getStringExtra("text")
                val dx = intent.getIntExtra("dx", 0)
                val dy = intent.getIntExtra("dy", 600)
                // navigate op：直接在当前 WebView 导航（绕开 Compose LaunchedEffect key 问题）
                val navUrl = intent.getStringExtra("url")
                Log.i("PageKit", "CONTROL intent: op=$op eid=$eid text=$text dx=$dx dy=$dy navUrl=$navUrl")
                (applicationContext as PageKitApp).homeViewModel.controlOp(op, eid, text, intent.getStringExtra("intent"), dx, dy, navUrl)
            }

            else -> Unit
        }
    }

    private val Intent.initialUrl: String?
        get() = data?.toString()?.takeIf { it.isNotBlank() } ?: getStringExtra("url")

    private val Intent.autoExtract: Boolean
        get() = getBooleanExtra("extract", false) || getStringExtra("extract") == "true"

    companion object {
        const val CONTROL_ACTION = "com.kenjc.pagekit.CONTROL"
    }
}
