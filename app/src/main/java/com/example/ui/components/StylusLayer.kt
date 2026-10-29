package com.example.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import com.example.core.GBACore
import kotlin.math.roundToInt

/**
 * El dedo sobre la pantalla táctil de la Nintendo DS.
 *
 * La Nintendo DS no es «un móvil con más botones»: su pantalla inferior es
 * táctil y muchos juegos se juegan con el dedo o el lápiz sobre ella. Por eso
 * esta capa va entre la imagen y los mandos, que son opacos y se quedan con lo
 * suyo.
 *
 * Lo difícil no es recibir el toque sino traducirlo. La imagen se dibuja
 * escalada, centrada y con el modo de aspecto que el usuario haya elegido, y la
 * pantalla táctil puede estar a la izquierda o abajo. Por eso la capa recibe el
 * rectángulo donde [ScreenRenderer] ha puesto la imagen y el orden de paneles.
 *
 * La capa **sólo ocupa el rectángulo del panel**, no la pantalla entera. Es
 * deliberado: un `pointerInput` que mide todo el lienzo se queda con el primer
 * toque de cualquier zona —la barra superior incluida— porque Compose entrega
 * el evento al hermano de encima y no sigue bajando a los demás. Cubriendo sólo
 * el panel, los botones que están fuera vuelven a recibir lo suyo.
 *
 * En Game Boy y GBA no hace nada: no hay pantalla táctil, y [stylusPanel] llega
 * con menos cero.
 */
@Composable
fun StylusLayer(
    core: GBACore,
    panelWidth: Int,
    panelHeight: Int,
    contentRect: Rect?,
    stylusPanel: Int,
    stackScreensVertically: Boolean = false,
    modifier: Modifier = Modifier
) {
    if (stylusPanel < 0) return

    val currentCore = rememberUpdatedState(core)

    // Origen de la capa en la ventana. El rectángulo que llega de
    // [ScreenRenderer] está en coordenadas de ventana y el `offset` de un hijo
    // se mide desde la esquina de su padre, así que hay que restarlo.
    val layerOrigin = remember { mutableStateOf(Offset.Zero) }
    val origin = layerOrigin.value

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                val next = coordinates.positionInWindow()
                // Sólo se escribe cuando cambia: el callback salta en cada
                // pasada de layout y una escritura sin novedad recompondría en
                // bucle.
                if (next != layerOrigin.value) layerOrigin.value = next
            }
    ) {
        val rect = contentRect
        if (rect == null || rect.width <= 0f || rect.height <= 0f) return@Box

        val panelWidthPx = if (stackScreensVertically) rect.width else rect.width / 2f
        val panelHeightPx = if (stackScreensVertically) rect.height / 2f else rect.height
        val panelLeft = if (stackScreensVertically || stylusPanel == 0) {
            rect.left
        } else {
            rect.left + panelWidthPx
        }
        val panelTop = if (stackScreensVertically && stylusPanel == 0) {
            rect.top + panelHeightPx
        } else {
            rect.top
        }

        val leftPx = (panelLeft - origin.x).roundToInt()
        val topPx = (panelTop - origin.y).roundToInt()
        val widthPx = panelWidthPx.roundToInt()
        val heightPx = panelHeightPx.roundToInt()
        if (widthPx <= 0 || heightPx <= 0) return@Box

        val density = LocalDensity.current
        Box(
            modifier = Modifier
                .offset { IntOffset(leftPx, topPx) }
                .size(
                    width = with(density) { widthPx.toDp() },
                    height = with(density) { heightPx.toDp() }
                )
                .pointerInput(stylusPanel, panelWidth, panelHeight) {
                    awaitEachGesture {
                        // Un solo dedo: la pantalla táctil es un contacto
                        // resistivo y dos dedos a la vez no significan nada en
                        // la consola.
                        val down = awaitFirstDown(requireUnconsumed = false)

                        // Si otro control ya se quedó con el toque —un botón
                        // del mando que cae encima del panel— no se duplica: el
                        // lápiz no tiene por qué moverse al pulsar un botón.
                        if (down.isConsumed) return@awaitEachGesture

                        // La caja mide exactamente el panel, así que el toque
                        // se traduce con una regla de tres y sin comprobar
                        // bordes: fuera de aquí no llega ningún evento.
                        val scaleX = panelWidth.toFloat() / size.width
                        val scaleY = panelHeight.toFloat() / size.height

                        fun emit(position: Offset, pressed: Boolean) {
                            val x = (position.x * scaleX).toInt()
                                .coerceIn(0, panelWidth - 1)
                            val y = (position.y * scaleY).toInt()
                                .coerceIn(0, panelHeight - 1)
                            currentCore.value.setStylus(x, y, pressed)
                        }

                        emit(down.position, true)
                        down.consume()

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                // Al levantar el dedo el stylus se suelta. Sin
                                // esto el TSC se queda creyendo que el dedo
                                // sigue ahí y el juego ve un contacto fantasma
                                // clavado.
                                currentCore.value.setStylus(0, 0, false)
                                break
                            }
                            emit(change.position, true)
                            change.consume()
                        }
                    }
                }
        ) {
            // Sin contenido: la caja existe sólo para delimitar el panel y
            // escuchar el dedo sobre él.
        }
    }
}
