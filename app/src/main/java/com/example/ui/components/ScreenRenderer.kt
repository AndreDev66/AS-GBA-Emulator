package com.example.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.GBACore
import com.example.data.AspectRatioMode
import com.example.data.VideoFilter

/**
 * High-performance Game Boy Advance Screen Renderer.
 * Renders the 240x160 GBA LCD bitmap with hardware scaling and filters.
 *
 * Developed by Andrés Socorro.
 */
@Composable
fun ScreenRenderer(
    bitmap: Bitmap,
    videoFilter: VideoFilter,
    aspectRatioMode: AspectRatioMode,
    fps: Int,
    fastForwardSpeed: Float,
    showFps: Boolean,
    frameTick: Long,
    modifier: Modifier = Modifier
) {
    // El bitmap se reescribe en el mismo objeto en cada fotograma: sin un dato
    // que cambie, Compose no volvería a dibujar y la pantalla quedaría congelada
    // en el primer fotograma. `frameTick` es ese dato.
    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (frameTick < 0L) return@Canvas
            val canvasW = size.width
            val canvasH = size.height

            if (canvasW <= 0 || canvasH <= 0) return@Canvas

            val filterQuality = when (videoFilter) {
                VideoFilter.NEAREST_NEIGHBOR -> FilterQuality.None
                VideoFilter.BILINEAR -> FilterQuality.Medium
                VideoFilter.SCANLINES -> FilterQuality.None
            }

            // GBA Native aspect ratio is 240:160 -> 3:2 (1.5)
            val nativeRatio = GBACore.SCREEN_WIDTH.toFloat() / GBACore.SCREEN_HEIGHT.toFloat()

            val (destW, destH) = when (aspectRatioMode) {
                AspectRatioMode.ORIGINAL_3_2 -> {
                    if (canvasW / canvasH > nativeRatio) {
                        val h = canvasH
                        val w = h * nativeRatio
                        Pair(w, h)
                    } else {
                        val w = canvasW
                        val h = w / nativeRatio
                        Pair(w, h)
                    }
                }
                AspectRatioMode.STRETCH_FULL -> Pair(canvasW, canvasH)
                AspectRatioMode.FIT_HEIGHT -> {
                    val w = canvasW
                    val h = (w / nativeRatio).coerceAtMost(canvasH)
                    Pair(w, h)
                }
            }

            val left = (canvasW - destW) / 2f
            val top = (canvasH - destH) / 2f

            drawImage(
                image = imageBitmap,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(GBACore.SCREEN_WIDTH, GBACore.SCREEN_HEIGHT),
                dstOffset = IntOffset(left.toInt(), top.toInt()),
                dstSize = IntSize(destW.toInt(), destH.toInt()),
                filterQuality = filterQuality
            )

            // Optional Scanlines shader simulation
            if (videoFilter == VideoFilter.SCANLINES) {
                val step = (destH / GBACore.SCREEN_HEIGHT).coerceAtLeast(1f)
                var y = top
                while (y < top + destH) {
                    drawLine(
                        color = Color.Black.copy(alpha = 0.25f),
                        start = androidx.compose.ui.geometry.Offset(left, y),
                        end = androidx.compose.ui.geometry.Offset(left + destW, y),
                        strokeWidth = 1f
                    )
                    y += step
                }
            }
        }

        // Overlay status badges (FPS & Fast Forward)
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (fastForwardSpeed > 1.0f) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f),
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = ">> ${fastForwardSpeed.toInt()}x",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            if (showFps) {
                Surface(
                    color = Color.Black.copy(alpha = 0.7f),
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = "$fps FPS",
                        color = if (fps >= 55) Color(0xFF00E676) else Color(0xFFFFB300),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}
