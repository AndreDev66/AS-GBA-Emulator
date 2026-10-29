package com.example

import com.example.data.CoverArt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * URL de carátula contra el CDN real de libretro-thumbnails.
 *
 * El nombre del archivo es la ÚNICA llave: el CDN no tiene API de búsqueda,
 * sólo un fichero por nombre de No-Intro, y el servidor es sensible a
 * mayúsculas. Un fallo de codificación aquí no ruidaría en tiempo de
 * ejecución (Coil falla en silencio y queda el icono de reserva), así que
 * estos tests son la única red de seguridad de la función.
 */
class CoverArtTest {

    private val base =
        "https://thumbnails.libretro.com/Nintendo%20-%20Game%20Boy%20Advance/Named_Boxarts/"

    // Caso real: este fichero EXISTE en el CDN (verificado con HEAD 200),
    // así que la expectativa del test es literalmente la URL que sirve la portada.
    @Test
    fun `construye la URL exacta de una ROM existente`() {
        assertEquals(
            base + "Pokemon%20-%20Edicion%20Esmeralda%20%28Spain%29.png",
            CoverArt.boxArtUrl("Pokemon - Edicion Esmeralda (Spain).gba")
        )
    }

    @Test
    fun `quita cualquier extension de ROM, sin distinguir mayusculas`() {
        val esperada = base + "Metroid%20Fusion%20%28USA%29.png"
        assertEquals(esperada, CoverArt.boxArtUrl("Metroid Fusion (USA).gba"))
        assertEquals(esperada, CoverArt.boxArtUrl("Metroid Fusion (USA).GBA"))
        assertEquals(esperada, CoverArt.boxArtUrl("Metroid Fusion (USA).zip"))
        assertEquals(esperada, CoverArt.boxArtUrl("Metroid Fusion (USA).7z"))
        assertEquals(esperada, CoverArt.boxArtUrl("Metroid Fusion (USA).RAR"))
    }

    // La coma del "(USA, Europe)" existe en nombres No-Intro reales; URLEncoder
    // la deja como %2C y el CDN la decodifica igual que los paréntesis.
    @Test
    fun `codifica la coma y los parentesis`() {
        assertEquals(
            base + "Pokemon%20-%20Emerald%20Version%20%28USA%2C%20Europe%29.png",
            CoverArt.boxArtUrl("Pokemon - Emerald Version (USA, Europe).gba")
        )
    }

    // Regla del README de libretro: los 11 caracteres ``&*/:`<>?\|"`` -> _
    // (en el índice real: "Lilo _ Stitch", "Legacy of Goku I _ II").
    @Test
    fun `sustituye los caracteres prohibidos por guion bajo`() {
        assertEquals(
            base + "Lilo%20_%20Stitch%20%28Europe%29.png",
            CoverArt.boxArtUrl("Lilo & Stitch (Europe).gba")
        )
        // Ningún resto del carácter original: ni literal ni codificado.
        val url = CoverArt.boxArtUrl("Lilo & Stitch (Europe).gba")!!
        assertFalse(url.contains("&"))
        assertFalse(url.contains("%26"))

        assertEquals(
            base + "A_B_C_D_E_F_G_H_I.png",
            CoverArt.boxArtUrl("A&B*C:D?E\"F<G>H|I.gba")
        )
    }

    // "Card-e+" es un nombre real del índice; en formulario el + significaría
    // espacio y el fichero no se encontraría nunca.
    @Test
    fun `el mas literal se codifica como signo mas, no como espacio`() {
        assertEquals(
            base + "Pokemon%20Battle%20Card-e%2B%20-%20Emerald%20-%2019-A001%20%28Japan%29.png",
            CoverArt.boxArtUrl("Pokemon Battle Card-e+ - Emerald - 19-A001 (Japan).gba")
        )
    }

    @Test
    fun `codifica los acentos en utf-8`() {
        assertEquals(
            base + "Se%C3%B1or%20de%20los%20Anillos.png",
            CoverArt.boxArtUrl("Señor de los Anillos.gba")
        )
        assertEquals(
            base + "Pok%C3%A9mon.png",
            CoverArt.boxArtUrl("Pokémon.gba")
        )
    }

    // El subrayado es literal en No-Intro y también el que nosotros ponemos
    // al sustituir; no debe ni escaparse ni convertirse en espacio.
    @Test
    fun `conserva el guion bajo literal`() {
        assertEquals(base + "Juego_GBA.png", CoverArt.boxArtUrl("Juego_GBA"))
        assertEquals(base + "Juego_GBA.png", CoverArt.boxArtUrl("Juego_GBA.gba"))
    }

    @Test
    fun `ignora la carpeta que envuelve al nombre`() {
        assertEquals(
            base + "Pokemon%20-%20Emerald%20Version%20%28USA%2C%20Europe%29.png",
            CoverArt.boxArtUrl("roms/GBA/Pokemon - Emerald Version (USA, Europe).gba")
        )
        assertEquals(
            base + "Metroid%20Fusion%20%28USA%29.png",
            CoverArt.boxArtUrl("C:\\Roms\\Metroid Fusion (USA).gba")
        )
    }

    @Test
    fun `devuelve null cuando no hay nombre del que partir`() {
        assertNull(CoverArt.boxArtUrl(""))
        assertNull(CoverArt.boxArtUrl("   "))
        assertNull(CoverArt.boxArtUrl(".gba"))
        assertNull(CoverArt.boxArtUrl("carpeta/"))
    }
}
