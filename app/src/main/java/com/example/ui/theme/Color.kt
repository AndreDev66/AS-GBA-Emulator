package com.example.ui.theme

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import com.example.data.AccentTheme
import kotlin.math.pow

/**
 * Paleta "Steam Big Picture" — minimalista, oscura, con un solo acento eléctrico.
 *
 * Filosofía:
 *  - Fondos en negro / carbón casi puro para máximo contraste.
 *  - Un único acento primario (cian eléctrico) que guía la vista.
 *  - El texto activo es blanco puro; el inactivo usa alpha.
 *  - El rosa/purpura queda reservado únicamente para hackroms y favoritos activos.
 *  - Cero colores saturados compitiendo entre sí.
 */

/**
 * Acento activo de la interfaz.
 *
 * [SteamCyan] no es un color fijo sino una lectura de este estado: los sesenta y
 * pico de sitios que la usan por la app quedan así suscritos al tema elegido sin
 * tocarlos uno a uno. Quien lee la propiedad durante la composición se recompone
 * al cambiarla; quien la lee al dibujar, se repinta. Con igualdad estructural,
 * aplicar el mismo color dos veces no invalida nada.
 */
private val accentState = mutableStateOf(Color(0xFF1A9FFF))

/**
 * Pone el acento de [theme] en vigor.
 *
 * Lo llama el tema al componer (no el diálogo de ajustes): así la interfaz sólo
 * tiene una fuente de verdad y volver a un color ya aplicado no repinta nada.
 */
fun applyAccentTheme(theme: AccentTheme) {
    accentState.value = Color(theme.argb)
}

// ── Primario / Acento ────────────────────────────────────────────────────────
/** Acento activo: botones CTA, tabs seleccionados, highlights. */
val SteamCyan: Color
    get() = accentState.value
/**
 * Versión suave del acento para bordes activos / con foco.
 *
 * Se deriva en el momento y no se guarda por tema: así un acento nuevo no
 * exige redefinir también su borde, y el 16 % de alfa es el mismo para todos
 * (los tonos claros se ven igual de presentes que los oscuros).
 */
val SteamBorderActive: Color
    get() = SteamCyan.copy(alpha = 0x40 / 255f)

/**
 * Color legible **encima** del acento activo.
 *
 * Es lo que llevan los botones rellenos de acento, las etiquetas de las pestañas
 * activas y el "Jugar" de las tarjetas. Estaba escrito a mano como un azul casi
 * negro (`0xFF001626`) que sólo funciona con acentos claros: con el Índigo GBA,
 * que es oscuro, el texto se quedaba sin contraste visible.
 *
 * En vez de fiarse del tono se pregunta por el contraste real (WCAG 2.1) contra
 * los dos candidatos y gana el que más se separa. Así un acento lima o hielo
 * pide tinta oscura y un índigo o fucsia pide tinta clara, sin una tabla que
 * mantener cada vez que se añade un tema.
 */
val SteamOnAccent: Color
    get() = onAccentFor(SteamCyan)

/** Tinta clara de la pareja de contraste. */
private val OnAccentLight = Color(0xFFFFFFFF)

/**
 * Tinta oscura de la pareja de contraste.
 *
 * Negro puro y no un azul casi negro: con el acento más claro que puede elegir
 * el usuario el par suave se quedaba en 4,18:1, por debajo del mínimo legible.
 * En negro puro el peor de los seis temas sube a 4,6:1.
 */
private val OnAccentDark = Color(0xFF000000)

/**
 * Elige la tinta legible sobre [accent].
 *
 * Se expone para el esquema de Material3, que necesita el mismo cálculo para
 * `onPrimary` pero recibe el acento como parámetro y no como estado.
 */
fun onAccentFor(accent: Color): Color =
    if (contrastRatio(accent, OnAccentDark) >= contrastRatio(accent, OnAccentLight)) {
        OnAccentDark
    } else {
        OnAccentLight
    }

/** Luminancia relativa según WCAG 2.1. */
private fun relativeLuminance(color: Color): Float {
    fun linear(channel: Float): Float =
        if (channel <= 0.03928f) channel / 12.92f
        else ((channel + 0.055f) / 1.055f).pow(2.4f)

    return 0.2126f * linear(color.red) +
        0.7152f * linear(color.green) +
        0.0722f * linear(color.blue)
}

/** Relación de contraste entre dos colores, de 1:1 a 21:1. */
private fun contrastRatio(a: Color, b: Color): Float {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    val brighter = maxOf(la, lb)
    val darker   = minOf(la, lb)
    return (brighter + 0.05f) / (darker + 0.05f)
}

// ── Superficies / Fondos ─────────────────────────────────────────────────────
/** Fondo base: negro profundo tipo OLED. */
val SteamBg          = Color(0xFF0A0B0D)
/** Primera capa sobre el fondo: menús, toolbars. */
val SteamSurface1    = Color(0xFF111318)
/** Segunda capa: cards, items de lista. */
val SteamSurface2    = Color(0xFF171B22)
/** Tercera capa: campos, chips no seleccionados. */
val SteamSurface3    = Color(0xFF1E222B)
/** Borde sutil estándar. */
val SteamBorder      = Color(0x1AFFFFFF)

// ── Texto ────────────────────────────────────────────────────────────────────
val SteamTextPrimary   = Color(0xFFECEFF4)
val SteamTextSecondary = Color(0xFF8B95A5)
val SteamTextMuted     = Color(0xFF4D5666)

// ── Estados semánticos ───────────────────────────────────────────────────────
/** Verde para indicar "activo", FPS OK, guardado correcto. */
val SteamGreen     = Color(0xFF4DB87A)
val SteamGreenSoft = Color(0xFF1E3D2F)

/** Naranja para advertencias o fast-forward activo. */
val SteamOrange    = Color(0xFFFF9A3C)

/** Rosa/magenta únicamente para hackroms y favoritos. */
val SteamPink      = Color(0xFFD456A0)
val SteamPinkSoft  = Color(0x33D456A0)

// ── Gradientes preconstruidos ─────────────────────────────────────────────────
/** Overlay de cabecera: transparente → SteamBg. */
val GradientHeaderFade = listOf(Color(0x00000000), SteamBg)
/** Icono de juego normal. */
val GradientGameIconNormal = listOf(Color(0xFF1D3557), Color(0xFF0D1B2A))
/** Icono de hackrom. */
val GradientGameIconHack   = listOf(Color(0xFF5A1040), Color(0xFF2D0828))
