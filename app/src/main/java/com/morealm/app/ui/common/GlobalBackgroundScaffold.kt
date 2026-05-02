package com.morealm.app.ui.common

import android.graphics.Paint
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.hilt.navigation.compose.hiltViewModel
import com.morealm.app.domain.render.BgImageManager
import com.morealm.app.presentation.appearance.GlobalBgViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Global background image scaffold for the 4 main tabs (书架/发现/听书/我的).
 * Reader screen 自成一套（ReaderBgImage*），不受此影响。
 *
 * 语义（v2）：alpha + blur 都作用于"背景图自身"，**卡片不动**。
 *  - alpha：用 Paint.alpha 直接把背景 bitmap 画得半透，露出主题 background 底色。
 *  - blur：交给 BgImageManager 的 box-blur（缓存 key 含 blurRadius，切档不重新解码原图）。
 *
 * `LocalCardAlpha` / `LocalCardBlur` 保留作为 hook 点（默认值 1.0 / 0），
 * 当前没有任何 Card 消费它——保留以防后续要做"双层叠加"风格。
 */

/** 兼容保留；当前不再实际生效（默认 1.0）。 */
val LocalCardAlpha = staticCompositionLocalOf { 1.0f }

/** 兼容保留；当前不再实际生效（默认 0）。 */
val LocalCardBlur = staticCompositionLocalOf { 0f }

@Composable
fun GlobalBackgroundScaffold(
    content: @Composable () -> Unit,
) {
    val vm: GlobalBgViewModel = hiltViewModel()
    val uri by vm.globalBgImage.collectAsState()
    val bgAlpha by vm.globalBgCardAlpha.collectAsState()   // prefs 键沿用历史名
    val bgBlur by vm.globalBgCardBlur.collectAsState()     // prefs 键沿用历史名

    val context = LocalContext.current
    var size by remember { mutableStateOf(IntSize.Zero) }
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

    // 背景 bitmap 缓存：uri / 尺寸 / 模糊半径 任一变化都重算。
    // alpha 不进 key —— alpha 只是 Paint 参数，每帧实时调即可，不需要重新 decode。
    LaunchedEffect(uri, size.width, size.height, bgBlur) {
        if (uri.isBlank() || size.width <= 0 || size.height <= 0) {
            bitmap = null
            return@LaunchedEffect
        }
        val blurRadius = bgBlur.toInt().coerceIn(0, 25)
        bitmap = withContext(Dispatchers.IO) {
            BgImageManager.getBgBitmap(
                context = context,
                uri = uri,
                width = size.width,
                height = size.height,
                blurRadius = blurRadius,
            )?.bitmap
        }
    }

    // 半透明 Paint —— alpha 直接作用在背景 bitmap 上
    val bgPaint = remember { Paint() }
    bgPaint.alpha = (bgAlpha.coerceIn(0f, 1f) * 255f).toInt()

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 主题 background 作为底色：背景图半透时露出来，无图时唯一来源
            .background(MaterialTheme.colorScheme.background)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = this.size.width.toInt()
            val h = this.size.height.toInt()
            if (w > 0 && h > 0 && (w != size.width || h != size.height)) {
                size = IntSize(w, h)
            }
            bitmap?.let { bmp ->
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.drawBitmap(bmp, 0f, 0f, bgPaint)
                }
            }
        }
        content()
    }
}

/**
 * 是否支持 Modifier.blur（API 31+）。
 * 当前 blur 走 BgImageManager 内部的 box-blur，**不依赖 API 31**，所有版本都可用。
 * 这个 flag 暂时保留给 ProfileScreen 的 UI 文案判断。
 */
val supportsBlur: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
