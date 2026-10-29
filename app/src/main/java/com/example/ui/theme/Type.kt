package com.example.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.R

/**
 * Tipografía de AS GBA.
 *
 * Inter (SIL Open Font License) empaquetada en `res/font` en su corte
 * variable: una sola instancia cubre todos los pesos de la app, viaja
 * dentro del APK y no necesita permiso de red ni depender de Google
 * Fonts.
 *
 * El peso se pasa dos veces a propósito: como argumento `weight` para
 * que Compose empareje esta entrada con las peticiones de texto, y como
 * eje `wght` de la fuente variable para que el trazo sea realmente ese.
 * `Font` tiene una sobrecarga sin ejes que se graded como "más
 * específica", así que omitir el eje daría Inter normal en todo y los
 * pesos se verían todos iguales.
 *
 * En Android 11 y anteriores el eje no se aplica y se dibuja el peso
 * normal, que sigue siendo una sans-serif limpia y neutra.
 *
 * Ajustes intencionales:
 *  - letterSpacing muy ajustado (≤ 0) en títulos: más compacto, más "broadcast".
 *  - lineHeight generoso en body: facilita escanear listas largas de ROMs.
 *  - FontWeight.ExtraBold en displayLarge para el nombre del juego en hero.
 */
@OptIn(ExperimentalTextApi::class)
private fun inter(weight: FontWeight) = Font(
    resId            = R.font.inter_variable,
    weight           = weight,
    variationSettings = FontVariation.Settings(weight, FontStyle.Normal)
)

@OptIn(ExperimentalTextApi::class)
private val Inter = FontFamily(
    inter(FontWeight.Normal),
    inter(FontWeight.Medium),
    inter(FontWeight.SemiBold),
    inter(FontWeight.Bold),
    inter(FontWeight.ExtraBold)
)

val Typography = Typography(

    // Hero / Pantalla completa
    displayLarge = TextStyle(
        fontFamily   = Inter,
        fontWeight   = FontWeight.ExtraBold,
        fontSize     = 52.sp,
        lineHeight   = 56.sp,
        letterSpacing = (-1.5).sp
    ),
    displayMedium = TextStyle(
        fontFamily   = Inter,
        fontWeight   = FontWeight.Bold,
        fontSize     = 38.sp,
        lineHeight   = 44.sp,
        letterSpacing = (-1).sp
    ),
    displaySmall = TextStyle(
        fontFamily   = Inter,
        fontWeight   = FontWeight.Bold,
        fontSize     = 28.sp,
        lineHeight   = 34.sp,
        letterSpacing = (-0.5).sp
    ),

    // Cabeceras de sección
    headlineLarge = TextStyle(
        fontFamily   = Inter,
        fontWeight   = FontWeight.ExtraBold,
        fontSize     = 24.sp,
        lineHeight   = 30.sp,
        letterSpacing = (-0.5).sp
    ),
    headlineMedium = TextStyle(
        fontFamily   = Inter,
        fontWeight   = FontWeight.Bold,
        fontSize     = 20.sp,
        lineHeight   = 26.sp,
        letterSpacing = 0.sp
    ),
    headlineSmall = TextStyle(
        fontFamily   = Inter,
        fontWeight   = FontWeight.Bold,
        fontSize     = 18.sp,
        lineHeight   = 24.sp,
        letterSpacing = 0.sp
    ),

    // Títulos de componentes (AppBar, Dialog, Card)
    titleLarge = TextStyle(
        fontFamily   = Inter,
        fontWeight   = FontWeight.ExtraBold,
        fontSize     = 20.sp,
        lineHeight   = 26.sp,
        letterSpacing = (-0.25).sp
    ),
    titleMedium = TextStyle(
        fontFamily   = Inter,
        fontWeight   = FontWeight.Bold,
        fontSize     = 16.sp,
        lineHeight   = 22.sp,
        letterSpacing = 0.sp
    ),
    titleSmall = TextStyle(
        fontFamily   = Inter,
        fontWeight   = FontWeight.SemiBold,
        fontSize     = 13.sp,
        lineHeight   = 18.sp,
        letterSpacing = 0.1.sp
    ),

    // Cuerpo de texto
    bodyLarge = TextStyle(
        fontFamily   = Inter,
        fontWeight   = FontWeight.Normal,
        fontSize     = 16.sp,
        lineHeight   = 24.sp,
        letterSpacing = 0.15.sp
    ),
    bodyMedium = TextStyle(
        fontFamily   = Inter,
        fontWeight   = FontWeight.Normal,
        fontSize     = 14.sp,
        lineHeight   = 21.sp,
        letterSpacing = 0.1.sp
    ),
    bodySmall = TextStyle(
        fontFamily   = Inter,
        fontWeight   = FontWeight.Normal,
        fontSize     = 12.sp,
        lineHeight   = 18.sp,
        letterSpacing = 0.2.sp
    ),

    // Etiquetas / Badges
    labelLarge = TextStyle(
        fontFamily   = Inter,
        fontWeight   = FontWeight.SemiBold,
        fontSize     = 14.sp,
        lineHeight   = 20.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily   = Inter,
        fontWeight   = FontWeight.Medium,
        fontSize     = 12.sp,
        lineHeight   = 16.sp,
        letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontFamily   = Inter,
        fontWeight   = FontWeight.Bold,
        fontSize     = 10.sp,
        lineHeight   = 14.sp,
        letterSpacing = 0.8.sp
    )
)
