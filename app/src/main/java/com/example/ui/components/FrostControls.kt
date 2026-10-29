package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.example.ui.theme.FrostSkin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.sin

/**
 * Micro-interacciones de los mandos táctiles.
 *
 * Hay tres piezas y ninguna es decorativa por separado:
 *
 *  1. [rememberPressScale] hunde el botón al pulsar y lo devuelve con un rebote
 *     corto. Sin él, el dedo sólo cambia un color y no hay sensación de que se
 *     haya hundido algo físico.
 *  2. [rememberPressPulse] lanza una onda de luz **desde el punto del dedo**, no
 *     desde el centro: el lugar de donde sale la luz es lo que convierte un
 *     círculo que se enciende en algo que ha sido tocado ahí.
 *  3. [FrostSurface] pinta cuerpo, reflejo y filo de una vez, para que el
 *     material sea el mismo en A que en Start.
 */

/** Cuánto se hunde el botón bajo el dedo. */
private const val PressedScale = 0.95f

/** Duración de la onda de luz. Corta a propósito: es un destello, no un efecto. */
private const val PulseMillis = 420

/**
 * Estado de la onda de luz de un botón.
 *
 * [phase] va de 0 (recién salido del dedo) a 1 (desaparecido). La animación vive
 * en un [Animatable] y se lee en la fase de dibujo, no en la composición: el
 * botón se repinta sin recomponer el árbol entero por cada fotograma del pulso.
 */
@Stable
class PressPulseState(private val scope: CoroutineScope) {
    private val progress = Animatable(1f)

    /** Punto del último toque, en coordenadas del botón. */
    var origin: Offset by mutableStateOf(Offset.Unspecified)
        private set

    val phase: Float get() = progress.value

    /**
     * Lanza la onda desde [at]. Repetirla antes de que termine la reinicia.
     *
     * Se lanza en el ámbito de composición en vez de en el gesto porque el
     * gesto se reanuda en cuanto el dedo se va y la onda debe sobrevivir a la
     * pulsación: si la animación viviera en aquél, desaparecería a media
     * expansión, que es justo cuando se nota que era un efecto y no un brillo.
     */
    fun fire(at: Offset) {
        origin = at
        scope.launch {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(PulseMillis, easing = FastOutSlowInEasing))
        }
    }
}

@Composable
fun rememberPressPulse(): PressPulseState {
    val scope = rememberCoroutineScope()
    return remember(scope) { PressPulseState(scope) }
}

/**
 * Escala del botón según esté pulsado o no.
 *
 * El rebote va con `dampingRatio` por debajo de 1 a propósito: al soltar pasa
 * de 0,95 a 1 y se pasa un poco de largo antes de asentarse, que es lo que lee
 * como "elástico" sin necesidad de una animación larga.
 */
@Composable
fun rememberPressScale(pressed: Boolean): Float {
    val scale by animateFloatAsState(
        targetValue = if (pressed) PressedScale else 1f,
        animationSpec = spring(
            dampingRatio = 0.48f,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "pressScale"
    )
    return scale
}

/**
 * 0 cuando los mandos están en su sitio y 1 cuando van por la mitad del
 * cambio de modo.
 *
 * Sirve para el abombamiento y el desvanecido del trayecto: en los extremos vale
 * cero, así que un mando quieto se dibuja exactamente igual que antes.
 */
fun travelEnvelope(t: Float): Float = sin(t * Math.PI).toFloat().coerceIn(0f, 1f)

/**
 * Cuerpo de un mando: cristal esmerilado con reflejo alto y filo que se apaga.
 *
 * Envuelve a [content] en el centro, que es donde viven la etiqueta y —si se
 * pasa [pulse]— la onda de luz.
 */
@Composable
fun FrostSurface(
    shape: Shape,
    skin: FrostSkin,
    modifier: Modifier = Modifier,
    pulse: PressPulseState? = null,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(skin.face)
            .background(skin.sheen)
            .border(width = 1.dp, brush = skin.edge, shape = shape),
        contentAlignment = Alignment.Center
    ) {
        if (pulse != null) {
            PressRipple(pulse = pulse, color = skin.glow)
        }
        content()
    }
}

/**
 * Onda de luz que sale del punto tocado.
 *
 * Dos círculos concéntricos con el segundo retrasado: una sola onda se lee como
 * un destello plano, y el retraso es lo que hace que se perciba como algo que se
 * expande. Se dibuja sobre el vidrio, sin desplazar el contenido.
 */
@Composable
private fun BoxScope.PressRipple(pulse: PressPulseState, color: Color) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val t = pulse.phase
        if (t >= 1f || pulse.origin == Offset.Unspecified) return@Canvas

        val center = pulse.origin
        val reach = max(size.width, size.height) * 1.15f

        drawCircle(
            color = color.copy(alpha = (1f - t) * 0.30f),
            radius = reach * easeOut(t),
            center = center
        )

        val delayed = (t - 0.18f) / 0.82f
        if (delayed > 0f) {
            drawCircle(
                color = color.copy(alpha = (1f - delayed) * 0.16f),
                radius = reach * easeOut(delayed) * 0.72f,
                center = center
            )
        }
    }
}

/** Salida rápida y cola larga: la onda sale de golpe y se apaga despacio. */
private fun easeOut(t: Float): Float {
    val inv = 1f - t
    return 1f - inv * inv * inv
}

/**
 * Gesto de pulsación con su onda de luz.
 *
 * Sustituye al `detectTapGestures` a pelo para poder encender la onda desde el
 * punto exacto del contacto ([onDown] lo recibe) y para apagar la pulsación
 * cuando el dedo se va, tanto si fue por `tryAwaitRelease` como si el botón
 * desapareció a mitad de partida.
 */
fun frostPressGesture(
    interactive: Boolean,
    pulse: PressPulseState? = null,
    onDown: (Offset) -> Unit = {},
    onUp: () -> Unit = {},
    onPressChange: (Boolean) -> Unit
): Modifier = if (!interactive) Modifier else Modifier.pointerInput(Unit) {
    detectTapGestures(
        onPress = { offset ->
            onDown(offset)
            onPressChange(true)
            tryAwaitRelease()
            onUp()
            onPressChange(false)
        }
    )
}
