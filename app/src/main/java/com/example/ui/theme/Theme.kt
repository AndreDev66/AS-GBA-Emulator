package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import com.example.data.AccentTheme

/**
 * Tema AS GBA — inspirado en Steam Big Picture.
 *
 * `dynamicColor` desactivado por defecto: el color dinámico del sistema toca
 * los azules del tema y los mezcla con el fondo de pantalla del usuario, rompiendo
 * la paleta cuidadosamente calibrada. Cuando el usuario tiene un fondo llamativo
 * los textos pierden contraste y el cian primario desaparece.
 *
 * El esquema se reconstruye con el acento elegido en Ajustes ([AccentTheme]),
 * porque `MaterialTheme.colorScheme.primary` es lo que leen los controles
 * "de fábrica" de Material3: con el esquema fijo, un switch quedaría cian
 * aunque el resto de la app se pintara de verde. Los componentes propios leen
 * `SteamCyan`, que el propio [ASGbaTheme] mantiene al día con el mismo tono.
 */

/**
 * Esquema oscuro con el acento dado como primario.
 *
 * Es una función y no un `val` de inicialización perezosa porque el acento lo
 * decide el usuario: al cargar el esquema en la inicialización de la clase, el
 * tono quedaría clavado en el de fábrica para toda la vida del proceso.
 */
private fun darkColorSchemeFor(accent: Color): ColorScheme = darkColorScheme(
    // Primario: acento (CTA, tabs activos, FAB)
    primary             = accent,
    // La tinta se calcula por contraste y no va fija: con el acento oscuro de
    // fábrica (índigo) un texto casi negro no se leería. Ver [onAccentFor].
    onPrimary           = onAccentFor(accent),
    primaryContainer    = lerp(accent, Color.Black, 0.5f),
    // Un tinte claro del propio acento, no un azul fijo: el contenedor ya sigue
    // al tema, así que su tinta también tiene que hacerlo.
    onPrimaryContainer  = lerp(accent, Color.White, 0.72f),

    // Secundario: verde "activo" / FPS
    secondary           = SteamGreen,
    onSecondary         = Color(0xFF001A0D),
    secondaryContainer  = SteamGreenSoft,
    onSecondaryContainer = Color(0xFFA8F0C6),

    // Terciario: rosa hackrom/favorito
    tertiary            = SteamPink,
    onTertiary          = Color(0xFF1A0010),
    tertiaryContainer   = SteamPinkSoft,
    onTertiaryContainer = Color(0xFFFFB3E0),

    // Fondos y superficies
    background          = SteamBg,
    surface             = SteamSurface1,
    surfaceVariant      = SteamSurface2,
    surfaceTint         = accent.copy(alpha = 0.04f),

    // Texto sobre superficies
    onBackground        = SteamTextPrimary,
    onSurface           = SteamTextPrimary,
    onSurfaceVariant    = SteamTextSecondary,

    // Bordes y outlines
    outline             = SteamBorder,
    outlineVariant      = SteamBorderActive,

    // Errores
    error               = Color(0xFFCF6679),
    onError             = Color(0xFF1A000A),
)

// Modo claro (heredado, sin uso en esta app pero requerido por Material3)
private val LightColorScheme = lightColorScheme(
    primary             = Color(0xFF0060A0),
    onPrimary           = Color.White,
    primaryContainer    = Color(0xFFCCE8FF),
    onPrimaryContainer  = Color(0xFF001E33),
    secondary           = Color(0xFF2E7D52),
    onSecondary         = Color.White,
    background          = Color(0xFFF4F6FA),
    surface             = Color(0xFFFFFFFF),
    onBackground        = Color(0xFF1A1C22),
    onSurface           = Color(0xFF1A1C22),
)

/**
 * Tema de la app.
 *
 * [accent] es la única decisión de color que el usuario puede cambiar; el resto
 * de la paleta es fija a propósito. Se aplica por los dos caminos a la vez:
 *  - aquí, reconstruyendo el esquema, para que lo que use `MaterialTheme` siga
 *    el acento, y
 *  - con [SideEffect] sobre `SteamCyan`, para que los ~60 sitios que usan ese
 *    nombre suelto (títulos, bordes, iconos) lo sigan sin tocarlos.
 */
@Composable
fun ASGbaTheme(
    accent: AccentTheme = AccentTheme.STEAM_CYAN,
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    // Se pone el acento antes de que nada se dibuje con él. `SideEffect` corre
    // tras confirmar la composición y la escritura en estado igual no invalida:
    // el caso habitual (recomponer por otro ajuste) no repinta la app entera.
    SideEffect { applyAccentTheme(accent) }

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            // Preferencia del sistema sobre el acento elegido: si el usuario
            // pidió color dinámico, ya está diciendo que no quiere elegir.
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> darkColorSchemeFor(Color(accent.argb))
        else      -> LightColorScheme
    }

    MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
