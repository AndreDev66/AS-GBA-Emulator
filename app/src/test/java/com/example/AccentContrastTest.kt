package com.example

import androidx.compose.ui.graphics.Color
import com.example.data.AccentTheme
import com.example.ui.theme.SteamOnAccent
import com.example.ui.theme.applyAccentTheme
import com.example.ui.theme.onAccentFor
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * La tinta que va **encima** del acento.
 *
 * Botones rellenos, pestañas activas y el "Jugar" de las tarjetas escriben sobre
 * el color del tema. Antes llevaban un azul casi negro escrito a mano, que sólo
 * funcionaba con acentos claros: con el Índigo GBA —oscuro— el texto se perdía.
 *
 * Ahora la tinta se elige por contraste real, y estas pruebas fijan el contrato:
 * con **cualquiera** de los temas el texto supera el mínimo de la WCAG para texto
 * normal (4,5:1), y la elección cambia de lado según el acento sea claro u oscuro.
 * Es la forma de que añadir un tema nuevo no reintroduzca el fallo sin que nadie
 * lo note.
 */
class AccentContrastTest {

    @Test
    fun `todo acento recibe una tinta legible segun la WCAG`() {
        for (theme in AccentTheme.entries) {
            val accent = Color(theme.argb)
            val ratio = contrast(accent, onAccentFor(accent))

            assertTrue(
                "${theme.displayName} deja la tinta en ${"%.2f".format(ratio)}:1",
                ratio >= MIN_AA_CONTRAST
            )
        }
    }

    @Test
    fun `el acento claro pide tinta oscura y el oscuro la pide clara`() {
        // La lima es el acento más claro de la paleta y el índigo el más oscuro:
        // son los dos extremos, así que sus tintas tienen que estar en los dos
        // lados, no en el mismo.
        val sobreLima = onAccentFor(Color(AccentTheme.GBA_LIME.argb))
        val sobreIndigo = onAccentFor(Color(AccentTheme.GBA_INDIGO.argb))

        assertTrue("la lima debería pedir tinta oscura", luminance(sobreLima) < 0.2f)
        assertTrue("el índigo debería pedir tinta clara", luminance(sobreIndigo) > 0.8f)
    }

    @Test
    fun `SteamOnAccent sigue al tema activo`() {
        // Es el nombre que usan los diálogos: tiene que devolver lo mismo que el
        // cálculo directo, y cambiar al cambiar el tema.
        applyAccentTheme(AccentTheme.GBA_INDIGO)
        val conIndigo = SteamOnAccent

        applyAccentTheme(AccentTheme.STEAM_CYAN)
        val conCian = SteamOnAccent

        assertNotEquals("la tinta no debería ser la misma con acentos opuestos", conIndigo, conCian)
        assertTrue(conCian == onAccentFor(Color(AccentTheme.STEAM_CYAN.argb)))

        // Se deja el tema de fábrica puesto: el estado es global y otras pruebas
        // podrían leerlo.
        applyAccentTheme(AccentTheme.STEAM_CYAN)
    }

    @Test
    fun `un acento opaco y su version con alfa dan la misma tinta`() {
        // Los swatches del selector se pintan con el mismo valor, así que la
        // decisión no puede depender de cómo venga el alfa.
        val opaco = Color(AccentTheme.GBA_PURPLE.argb)
        val transparente = opaco.copy(alpha = 0.4f)

        assertTrue(onAccentFor(opaco) == onAccentFor(transparente))
    }

    // ── Contraste según la WCAG 2.1 ──────────────────────────────────────────

    private fun luminance(color: Color): Float {
        fun linear(channel: Float): Float =
            if (channel <= 0.03928f) channel / 12.92f
            else ((channel + 0.055f) / 1.055f).pow(2.4f)

        return 0.2126f * linear(color.red) +
            0.7152f * linear(color.green) +
            0.0722f * linear(color.blue)
    }

    private fun contrast(a: Color, b: Color): Float {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
    }

    private companion object {
        /** Mínimo de la WCAG 2.1 (nivel AA) para texto de tamaño normal. */
        const val MIN_AA_CONTRAST = 4.5f
    }
}
