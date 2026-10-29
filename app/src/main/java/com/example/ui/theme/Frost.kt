package com.example.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * Material de la interfaz: cristal esmerilado.
 *
 * Antes los mandos táctiles eran dos rectangles con un relleno plano y un borde
 * de un píxel: se leían como iconos pegados a la pantalla, no como objetos que se
 * tocan. Aquí cada botón tiene un **cuerpo** (relleno con profundidad, más claro
 * arriba y más oscuro abajo), un **filo** (borde con brillo que se apaga hacia
 * abajo, como el filo de un vidrio inclinado) y un **halo** (el resplandor que
 * aparece al pulsarlo).
 *
 * Todo sale del acento que el usuario eligió en Ajustes. Los tonos concretos
 * de la tabla ada de marca (violeta para A/B, azul para L/R, gris para Start y
 * Select) están ahí como destino de mezcla, no como color fijo: el mando
 * cambia de color con el tema, que es lo que se pidió, en vez de ignorar el
 * acento y quedarse con el violeta de siempre.
 */

/** Familias de control, cada una con su mezcla hacia el color de marca. */
enum class ControlFamily {
    /** A y B: la acción principal, la más saturada de las tres. */
    ACTION,

    /** L y R: los hombros, un punto más fríos que la acción. */
    SHOULDER,

    /** Start y Select: lo accesorio, casi neutro. */
    SYSTEM,

    /** La cruceta: vidrio neutro, porque ocupa mucho y compite con todo. */
    DPAD
}

/**
 * Los cuatro lienzos de un control: cuerpo, brillo, filo y halo.
 *
 * Los pinceles se construyen enteros (y no alpha suelto en el modificador) para
 * que el estado de pulsación sea un solo objeto nuevo: Compose compara por
 * igualdad estructural, así que un botón sin cambios no repinta nada.
 */
@Immutable
data class FrostSkin(
    val face: Brush,
    val sheen: Brush,
    val edge: Brush,
    val glow: Color,
    val label: Color,
)

/** Violeta de marca de A/B: destino de mezcla, no color fijo. */
private val ActionMark = Color(0xFF8C7AE6)

/** Azul de marca de L/R. */
private val ShoulderMark = Color(0xFF4FA3E3)

/** Gris de marca de Start y Select. */
private val SystemMark = Color(0xFF9AA7B8)

/**
 * Mezcla el acento elegido con el color de marca de la familia.
 *
 * [weight] es cuánto pesa el color de marca: 0 deja el acento intacto y 1 lo
 * sustituye. Un valor bajo mantiene los mandos dentro de la paleta del tema sin
 * que sean el mismo azul que el resto de la interfaz.
 */
private fun familyTint(accent: Color, mark: Color, weight: Float): Color =
    lerp(accent, mark, weight).let { lerp(it, SteamSurface3, 0.22f) }

/**
 * Piel de un control en un estado dado.
 *
 * @param pressed mientras el dedo está encima: el cuerpo se aclara y satura, el
 *   filo se vuelve casi blanco y el halo sale con fuerza.
 * @param intensity multiplica todos los alfas. Es el ajuste de opacidad de
 *   Ajustes aplicado de una vez, para que subirla no pese a doce `copy(alpha)`
 *   repartidos.
 * @param tinte sustituye al color de marca de la familia. Lo usa el editor de
 *   controles, que dibuja sus miniaturas con su propio color: así la pieza es
 *   una sola y el material no depende de quién la llama.
 */
@Composable
fun rememberFrostSkin(
    family: ControlFamily,
    pressed: Boolean = false,
    intensity: Float = 1f,
    tint: Color? = null,
): FrostSkin {
    val accent = SteamCyan
    val a = intensity.coerceIn(0f, 1f)

    val base = when (family) {
        ControlFamily.ACTION -> familyTint(accent, ActionMark, 0.45f)
        ControlFamily.SHOULDER -> familyTint(accent, ShoulderMark, 0.40f)
        ControlFamily.SYSTEM -> familyTint(accent, SystemMark, 0.35f)
        ControlFamily.DPAD -> lerp(Color.White, accent, 0.18f)
    }
    val tintColor = if (tint != null) lerp(base, tint, 0.7f) else base

    // Pulsado se nota en el cuerpo y en el halo, no sólo en el color del texto:
    // un dedo encima cambia la forma en que el botón capta la luz.
    val bodyTop = if (pressed) 0.46f else 0.26f
    val bodyMid = if (pressed) 0.30f else 0.14f
    val bodyBottom = if (pressed) 0.34f else 0.20f

    return FrostSkin(
        face = Brush.verticalGradient(
            0.00f to tintColor.copy(alpha = bodyTop * a),
            0.48f to tintColor.copy(alpha = bodyMid * a),
            1.00f to Color.Black.copy(alpha = bodyBottom * a),
        ),
        // Reflejo alto: da volumen al vidrio. En reposo, apenas un velo.
        sheen = Brush.verticalGradient(
            0.00f to Color.White.copy(alpha = (if (pressed) 0.16f else 0.07f) * a),
            0.42f to Color.White.copy(alpha = 0.02f * a),
            1.00f to Color.Transparent,
        ),
        // El filo se apaga hacia abajo: es el canto del vidrio visto de canto.
        edge = Brush.verticalGradient(
            0.00f to Color.White.copy(alpha = (if (pressed) 0.55f else 0.26f) * a),
            0.55f to Color.White.copy(alpha = 0.08f * a),
            1.00f to Color.White.copy(alpha = 0.04f * a),
        ),
        glow = lerp(tintColor, Color.White, if (pressed) 0.35f else 0.10f),
        label = Color.White.copy(alpha = (if (pressed) 1f else 0.88f) * a.coerceAtLeast(0.35f)),
    )
}

/**
 * Fondo de la zona de mandos en vertical.
 *
 * Un negro plano deja la mitad de la pantalla muerta; el degradado da sitio al
 * cristal sin robarle protagonismo al juego, y el halo del acento deja claro
 * que la zona toca el panel y no es parte de la pantalla.
 */
@Composable
fun rememberControlAreaBackdrop(): Brush {
    val accent = SteamCyan
    return Brush.verticalGradient(
        0.00f to Color.Transparent,
        0.22f to SteamSurface1.copy(alpha = 0.30f),
        0.65f to SteamSurface1.copy(alpha = 0.55f),
        1.00f to Color(0xFF0C0F15),
    )
}

/** Halo difuso del acento detrás de la zona de mandos. */
@Composable
fun rememberAccentGlow(): Brush = Brush.radialGradient(
    0.00f to SteamCyan.copy(alpha = 0.07f),
    0.55f to SteamCyan.copy(alpha = 0.025f),
    1.00f to Color.Transparent,
)
