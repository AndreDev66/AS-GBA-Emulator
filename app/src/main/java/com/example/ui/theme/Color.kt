package com.example.ui.theme

import androidx.compose.ui.graphics.Color

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

// ── Primario / Acento ────────────────────────────────────────────────────────
/** Cian eléctrico: botones CTA, tabs seleccionados, highlights. */
val SteamCyan        = Color(0xFF1A9FFF)
/** Cian oscuro para contenedores y bordes activos. */
val SteamCyanDark    = Color(0xFF0D5FA0)
/** Cian muy suave para áreas de fondo de ítems seleccionados. */
val SteamCyanSurface = Color(0x1A1A9FFF)

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
/** Borde activo / con foco. */
val SteamBorderActive = Color(0x401A9FFF)

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
