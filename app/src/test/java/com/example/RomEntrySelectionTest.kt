package com.example

import com.example.data.isRomEntryName
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Selección de la entrada que es la ROM dentro de un comprimido.
 *
 * Un archivo descargado trae de todo alrededor del cartucho: un readme, una
 * carátula, a veces otro comprimido. Coger la primera entrada con pinta de ROM
 * es lo que separa una importación limpia de devolver al núcleo un texto que no
 * es un cartucho, con el error críptico que trae eso.
 */
class RomEntrySelectionTest {

    @Test
    fun `acepta las extensiones de ROM de gba`() {
        assertTrue(isRomEntryName("juego.gba"))
        assertTrue(isRomEntryName("juego.agb"))
        assertTrue(isRomEntryName("juego.bin"))
        assertTrue(isRomEntryName("juego.rom"))
    }

    @Test
    fun `acepta las extensiones de ROM de game boy`() {
        // Las ROM de Game Boy también llegan en comprimido, así que una entrada
        // `.gb` o `.gbc` dentro del ZIP es el cartucho buscado.
        assertTrue(isRomEntryName("juego.gb"))
        assertTrue(isRomEntryName("juego.gbc"))
    }

    @Test
    fun `acepta las extensiones de ROM de nintendo ds`() {
        // Las ROM de DS llegan en comprimido igual que las de las otras consolas,
        // y una de DSi es un archivo aparte con su propia extensión.
        assertTrue(isRomEntryName("juego.nds"))
        assertTrue(isRomEntryName("juego.dsi"))
    }

    @Test
    fun `no distingue mayusculas`() {
        // Windows guarda en mayúsculas y Linux en minúsculas: una comparación
        // sensible a mayúsculas perdería la mitad de las ROM.
        assertTrue(isRomEntryName("JUEGO.GBA"))
        assertTrue(isRomEntryName("Juego.Gba"))
        assertTrue(isRomEntryName("JUEGO.GBC"))
    }

    @Test
    fun `busca dentro de las carpetas del comprimido`() {
        assertTrue(isRomEntryName("roms/espanol/juego.gba"))
        assertTrue(isRomEntryName("roms\\espanol\\JUEGO.GBA"))
        assertTrue(isRomEntryName("roms/espanol/juego.gb"))
    }

    @Test
    fun `ignora lo que no es una ROM`() {
        assertFalse(isRomEntryName("readme.txt"))
        assertFalse(isRomEntryName("caratula.png"))
        assertFalse(isRomEntryName("juego.zip"))
        assertFalse(isRomEntryName("juego.gba.bak"))
        assertFalse(isRomEntryName("juego.gb.sav"))
    }

    @Test
    fun `una carpeta que parezca ROM no vale`() {
        // Las carpetas se descartan antes por `isDirectory()`, pero el nombre
        // por sí solo tampoco debe pasar: "carpeta.gba/" no es un cartucho.
        assertFalse(isRomEntryName("carpeta.gba/"))
        assertFalse(isRomEntryName("carpeta/sub/"))
    }
}
