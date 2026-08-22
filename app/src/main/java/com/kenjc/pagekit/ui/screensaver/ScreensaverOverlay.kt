package com.kenjc.pagekit.ui.screensaver

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 音频/视频播放中不遮挡内容，按此间隔复查。 */
const val AUDIO_RECHECK_MS = 30_000L

/** 时钟位置漂移间隔，避免时钟自身在同一位置留下残影。 */
private const val CLOCK_DRIFT_MS = 60_000L

/** 时钟在九宫格位置间轮换，任何单一位置连续停留不超过 CLOCK_DRIFT_MS。 */
private val CLOCK_ANCHORS = listOf(
    Alignment.TopStart, Alignment.TopCenter, Alignment.TopEnd,
    Alignment.CenterStart, Alignment.Center, Alignment.CenterEnd,
    Alignment.BottomStart, Alignment.BottomCenter, Alignment.BottomEnd,
)

private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")
private val DATE_FORMAT = DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINESE)

/**
 * 闲置屏保：近黑全屏遮罩 + 低亮度漂移时钟，防止长时间静止画面烧屏（AMOLED 尤甚）。
 *
 * - 任意触摸/按键（含 WebView 内交互，经 Activity.onUserInteraction）即退出；
 * - 首次触摸只用于唤醒，不会点击穿透到底层 UI；
 * - 时钟每分钟换一个九宫格锚点，时钟自身也不留残影。
 */
@Composable
fun ScreensaverOverlay(
    active: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!active) return
    BackHandler(onBack = onDismiss)

    var driftTick by remember { mutableIntStateOf(0) }
    var now by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(CLOCK_DRIFT_MS)
            driftTick++
            now = LocalTime.now()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // 鼠标不唤醒屏保（kiosk 场景防误触）；触摸/手写笔正常退出。
                    if (down.type != PointerType.Mouse) onDismiss()
                }
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(48.dp),
            contentAlignment = CLOCK_ANCHORS[driftTick % CLOCK_ANCHORS.size],
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = now.format(TIME_FORMAT),
                    fontSize = 56.sp,
                    color = Color.White.copy(alpha = 0.30f),
                )
                Text(
                    text = LocalDate.now().format(DATE_FORMAT),
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.22f),
                )
            }
        }
    }
}
