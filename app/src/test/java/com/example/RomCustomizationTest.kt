package com.example

import com.example.data.ROMItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pruebas del nombre y la portada que el usuario puede cambiar en una ROM.
 *
 * Lo que importa aquí es que renombrar o poner portada no toca la ROM: el
 * archivo, su ruta y su CRC tienen que seguir exactamente igual, porque de eso
 * dependen las partidas guardadas y el arranque del juego.
 */
class RomCustomizationTest {

    private fun item(
        fileName       : String = "Pokemon - Edicion Esmeralda (Spain).gba",
        displayName    : String? = null,
        customCoverPath: String? = null
    ) = ROMItem(
        id               = "abc123",
        title            = "POKEMON EMER",
        gameCode         = "BPES",
        uriString        = "content://roms/1",
        fileName         = fileName,
        sizeBytes        = 16 * 1024 * 1024,
        isHackRom        = false,
        hackRomName      = null,
        displayName      = displayName,
        customCoverPath  = customCoverPath
    )

    @Test
    fun `sin cambios el nombre es el del archivo`() {
        assertEquals("Pokemon - Edicion Esmeralda (Spain)", item().displayTitle)
    }

    @Test
    fun `el nombre del usuario manda sobre el del archivo`() {
        val renamed = item(displayName = "Mi Esmeralda favorita")

        assertEquals("Mi Esmeralda favorita", renamed.displayTitle)
    }

    @Test
    fun `un nombre en blanco deja el nombre del archivo`() {
        // Escribir espacios y guardar no puede dejar una fila sin título: sería
        // justo el problema que el usuario quería quitar.
        val renamed = item(displayName = "   ")

        assertEquals("Pokemon - Edicion Esmeralda (Spain)", renamed.displayTitle)
    }

    @Test
    fun `el nombre del archivo sobrevive al renombrado`() {
        // El renombrado es de presentación: lo que se carga y de dónde viene la
        // partida guardada no puede cambiar.
        val renamed = item(displayName = "Mi Esmeralda favorita")

        assertEquals("Pokemon - Edicion Esmeralda (Spain).gba", renamed.fileName)
        assertEquals("content://roms/1", renamed.uriString)
        assertEquals("BPES", renamed.gameCode)
        assertEquals(16 * 1024 * 1024, renamed.sizeBytes)
    }

    @Test
    fun `sin portada propia no hay ruta guardada`() {
        assertNull(item().customCoverPath)
    }

    @Test
    fun `la portada propia es una ruta local`() {
        val withCover = item(customCoverPath = "/data/user/0/com.example/files/covers/abc123.png")

        assertEquals("/data/user/0/com.example/files/covers/abc123.png", withCover.customCoverPath)
        // La ruta real de la ROM sigue siendo la de siempre.
        assertEquals("content://roms/1", withCover.uriString)
    }
}
