package com.example.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.AspectRatioMode
import com.example.data.VideoFilter
import com.example.ui.theme.SteamGreen
import com.example.ui.theme.SteamOrange

/**
 * Radio de esquina del lienzo del juego, como fracción del lado menor.
 *
 * 0,035 es apenas perceptible y sólo se nota junto a los mandos, que es
 * exactamente donde tiene que estar: el objetivo es que la pantalla no se
 * parezca a una pegatina, no que se vea como un móvil.
 */
private const val ScreenCornerFraction = 0.035f

/**
 * Renderizador de las pantallas de Game Boy, Game Boy Advance y Nintendo DS.
 *
 * Pinta el mapa de bits nativo con escalado por hardware y filtros. La DS puede
 * mostrar sus dos paneles juntos en horizontal o apilados en vertical.
 *
 * Interfaz y fachada: AS GBA Emulator, de Andrés Socorro.
 */
@Composable
fun ScreenRenderer(
    bitmap: Bitmap,
    videoFilter: VideoFilter,
    aspectRatioMode: AspectRatioMode,
    fps: Int,
    fastForwardSpeed: Float,
    showFps: Boolean,
    /**
     * Señal de que hay un fotograma nuevo.
     *
     * Se pasa como `State` y se lee dentro del dibujado, no en la composición:
     * el bitmap se reescribe en el mismo objeto cada fotograma, y observar el
     * contador aquí arriba recompondría la pantalla entera sesenta veces por
     * segundo. Leído en el lienzo, sólo se invalida el dibujado.
     */
    frameTick: State<Long>,
    stackScreensVertically: Boolean = false,
    /**
     * Avisa de dónde ha quedado la imagen, en coordenadas de ventana.
     *
     * Existe para el puntero de la pantalla táctil de la Nintendo DS, que
     * necesita traducir un dedo sobre la pantalla a coordenadas de imagen. Y no
     * puede suponerlas: el rectángulo depende del modo de aspecto, del filtro y
     * del tamaño del móvil, así que el único que lo sabe con certeza es el que
     * dibuja. Escribirlo en pantalla una vez por fotograma es más barato que
     * mantenerlo sincronizado a mano.
     */
    onContentRect: ((Rect) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // El bitmap se reescribe en el mismo objeto en cada fotograma: sin un dato
    // que cambie, Compose no volvería a dibujar y la pantalla quedaría congelada
    // en el primer fotograma. `frameTick` es ese dato.
    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }

    // Dónde está el lienzo respecto a la ventana. El `Canvas` rellena la `Box`,
    // y la `Box` es el `modifier` de quien compone, así que su origen en ventana
    // es el origen del propio lienzo.
    val canvasOrigin = remember { mutableStateOf(Offset.Zero) }
    val originState = canvasOrigin.value

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black)
            .onGloballyPositioned { coordinates ->
                canvasOrigin.value = coordinates.positionInWindow()
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Lectura en fase de dibujado: un fotograma nuevo invalida sólo el
            // lienzo, no la composición de la pantalla que lo contiene.
            if (frameTick.value < 0L) return@Canvas
            val canvasW = size.width
            val canvasH = size.height

            if (canvasW <= 0 || canvasH <= 0) return@Canvas

            val filterQuality = when (videoFilter) {
                VideoFilter.NEAREST_NEIGHBOR -> FilterQuality.None
                VideoFilter.BILINEAR -> FilterQuality.Medium
                VideoFilter.SCANLINES -> FilterQuality.None
            }

            val sourceWidth = if (stackScreensVertically) bitmap.width / 2 else bitmap.width
            val sourceHeight = if (stackScreensVertically) bitmap.height * 2 else bitmap.height
            val nativeRatio = sourceWidth.toFloat() / sourceHeight.toFloat()

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

            onContentRect?.invoke(
                Rect(
                    left = left + originState.x,
                    top = top + originState.y,
                    right = left + destW + originState.x,
                    bottom = top + destH + originState.y
                )
            )

            // El juego es el único elemento con el borde nítido de la
            // pantalla: los mandos son translúcidos y el fondo es degradado, así
            // que un rectángulo de esquinas vivas entre ellos rompe el conjunto.
            // El radio es pequeño a propósito —casi una esquina, no un círculo—
            // y sólo se aplica si cabe: si el destino es más pequeño que el
            // radio, redondearlo dejaría el juego con forma de gota.
            val drawLeft = left.toInt()
            val drawTop = top.toInt()
            val drawWidth = destW.toInt()
            val drawHeight = destH.toInt()
            val cornerRadius = minOf(destW, destH) * ScreenCornerFraction

            fun drawScreens() {
                if (stackScreensVertically) {
                    val firstScreenHeight = drawHeight / 2
                    drawImage(
                        image = imageBitmap,
                        srcOffset = IntOffset(bitmap.width / 2, 0),
                        srcSize = IntSize(sourceWidth, bitmap.height),
                        dstOffset = IntOffset(drawLeft, drawTop),
                        dstSize = IntSize(drawWidth, firstScreenHeight),
                        filterQuality = filterQuality
                    )
                    drawImage(
                        image = imageBitmap,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(sourceWidth, bitmap.height),
                        dstOffset = IntOffset(drawLeft, drawTop + firstScreenHeight),
                        dstSize = IntSize(drawWidth, drawHeight - firstScreenHeight),
                        filterQuality = filterQuality
                    )
                } else {
                    drawImage(
                        image = imageBitmap,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(bitmap.width, bitmap.height),
                        dstOffset = IntOffset(drawLeft, drawTop),
                        dstSize = IntSize(drawWidth, drawHeight),
                        filterQuality = filterQuality
                    )
                }
            }

            if (cornerRadius >= 1f) {
                val rounded = Path().apply {
                    addRoundRect(
                        RoundRect(
                            left = left,
                            top = top,
                            right = left + destW,
                            bottom = top + destH,
                            cornerRadius = CornerRadius(cornerRadius, cornerRadius)
                        )
                    )
                }
                clipPath(rounded) {
                    drawScreens()
                }
            } else {
                drawScreens()
            }

            if (videoFilter == VideoFilter.SCANLINES) {
                val step = (destH / sourceHeight).coerceAtLeast(1f)
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
                        color = if (fps >= 55) SteamGreen else SteamOrange,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}
