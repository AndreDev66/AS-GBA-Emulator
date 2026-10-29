package com.example

import com.example.core.emulation.ConsoleKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Decisión de qué consola emular una ROM.
 *
 * Es la decisión que gobierna todo lo demás —motor nativo, tamaño del
 * framebuffer, formato de píxeles—, así que su caso difícil no es la extensión
 * evidente sino la que falta: la ROM sacada de un comprimido llega con el
 * nombre del ZIP, y ahí no hay más remedio que mirar los bytes.
 */
class ConsoleKindTest {

    @Test
    fun `la extension manda sobre los bytes`() {
        assertEquals(ConsoleKind.GAME_BOY, ConsoleKind.detect("juego.gb", null))
        assertEquals(ConsoleKind.GAME_BOY, ConsoleKind.detect("JUEGO.GBC", null))
        assertEquals(ConsoleKind.GAME_BOY_ADVANCE, ConsoleKind.detect("juego.gba", null))
        assertEquals(ConsoleKind.GAME_BOY_ADVANCE, ConsoleKind.detect("juego.agb", null))
        assertEquals(ConsoleKind.NINTENDO_DS, ConsoleKind.detect("juego.nds", null))
        assertEquals(ConsoleKind.NINTENDO_DS, ConsoleKind.detect("JUEGO.NDS", null))
        assertEquals(ConsoleKind.NINTENDO_DS, ConsoleKind.detect("juego.dsi", null))
    }

    @Test
    fun `una rom de ds se reconoce sin extension`() {
        // Es el caso del que llega del importador de un comprimido: el nombre
        // que se ve es el del ZIP, no el de la entrada de dentro.
        assertEquals(ConsoleKind.NINTENDO_DS, ConsoleKind.detect("pack.zip", ndsRom()))
        assertEquals(ConsoleKind.NINTENDO_DS, ConsoleKind.detect("juego.bin", ndsRom()))
    }

    @Test
    fun `una cabecera corta identifica una rom de ds grande`() {
        val rom = ndsRom()
        assertEquals(
            ConsoleKind.NINTENDO_DS,
            ConsoleKind.detect("pack.zip", rom.copyOf(0x1000), rom.size.toLong()),
        )
    }

    @Test
    fun `una gb con cabecera de ds sigue siendo gb`() {
        // La detección de DS es aritmética y la de GB comprueba el logotipo de
        // arranque. Una Game Boy no tiene nada de lo primero, así que el orden
        // en que se pregunte no debería importar.
        assertEquals(ConsoleKind.GAME_BOY, ConsoleKind.detect("", gameBoyRom("TETRIS")))
    }

    @Test
    fun `la geometria de pantalla es la de cada consola`() {
        assertEquals(160, ConsoleKind.GAME_BOY.screenWidth)
        assertEquals(144, ConsoleKind.GAME_BOY.screenHeight)
        assertEquals(160 * 144, ConsoleKind.GAME_BOY.screenPixelCount)
        assertEquals(240, ConsoleKind.GAME_BOY_ADVANCE.screenWidth)
        assertEquals(160, ConsoleKind.GAME_BOY_ADVANCE.screenHeight)
        assertEquals(240 * 160, ConsoleKind.GAME_BOY_ADVANCE.screenPixelCount)
        // La DS entrega las dos pantallas juntas, con la táctil a la izquierda.
        assertEquals(512, ConsoleKind.NINTENDO_DS.screenWidth)
        assertEquals(192, ConsoleKind.NINTENDO_DS.screenHeight)
        assertEquals(512 * 192, ConsoleKind.NINTENDO_DS.screenPixelCount)
        assertEquals(256, ConsoleKind.NINTENDO_DS.panelWidth)
        assertEquals(true, ConsoleKind.NINTENDO_DS.hasTwoScreens)
        assertEquals(false, ConsoleKind.GAME_BOY_ADVANCE.hasTwoScreens)
    }

    @Test
    fun `lo que cada consola tiene y no tiene`() {
        // La Game Boy no tiene L ni R: su joypad llega hasta Start y Select.
        assertEquals(false, ConsoleKind.GAME_BOY.supportsShoulderButtons)
        assertEquals(true, ConsoleKind.GAME_BOY_ADVANCE.supportsShoulderButtons)
        assertEquals(true, ConsoleKind.NINTENDO_DS.supportsShoulderButtons)

        // X e Y sólo en la DS.
        assertEquals(false, ConsoleKind.GAME_BOY_ADVANCE.supportsFaceButtonsXY)
        assertEquals(true, ConsoleKind.NINTENDO_DS.supportsFaceButtonsXY)

        // Pantalla táctil sólo en la DS, y es la izquierda del fotograma.
        assertEquals(false, ConsoleKind.GAME_BOY_ADVANCE.hasStylus)
        assertEquals(-1, ConsoleKind.GAME_BOY_ADVANCE.stylusPanelIndex)
        assertEquals(true, ConsoleKind.NINTENDO_DS.hasStylus)
        assertEquals(0, ConsoleKind.NINTENDO_DS.stylusPanelIndex)

        // El enlace por cable es el de la GBA; la DS no tiene ese puerto.
        assertEquals(true, ConsoleKind.GAME_BOY_ADVANCE.supportsLinkCable)
        assertEquals(false, ConsoleKind.NINTENDO_DS.supportsLinkCable)

        assertEquals(true, ConsoleKind.NINTENDO_DS.prefersLandscape)
        assertEquals(false, ConsoleKind.GAME_BOY_ADVANCE.prefersLandscape)
    }

    @Test
    fun `los identificadores de almacenamiento no se mueven`() {
        // Son un contrato persistido: una biblioteca guardada lanza cada ROM por
        // su número. Reordenar el enum rompería las partidas ya importadas.
        assertEquals(0, ConsoleKind.GAME_BOY.storageId)
        assertEquals(2, ConsoleKind.GAME_BOY_ADVANCE.storageId)
        assertEquals(3, ConsoleKind.NINTENDO_DS.storageId)
    }

    /**
     * Cartucho de Nintendo DS mínimo: 16 KiB, cabecera con el ejecutable ARM9
     * declarado en 0x20/0x2C y un código de juego en 0x0C. No lleva logotipo de
     * Nintendo, que en la DS vive dentro del juego y en el banner.
     */
    private fun ndsRom(title: String = "NDS GAME", gameCode: String = "AYPE"): ByteArray {
        val rom = ByteArray(16 * 1024)
        title.forEachIndexed { index, ch -> rom[index] = ch.code.toByte() }
        gameCode.forEachIndexed { index, ch -> rom[0x0C + index] = ch.code.toByte() }
        // ARM9 en 0x1000, de 0x1000 bytes: dentro del archivo.
        writeLittleEndianInt(rom, 0x20, 0x1000)
        writeLittleEndianInt(rom, 0x24, 0x02000800)
        writeLittleEndianInt(rom, 0x28, 0x02000000)
        writeLittleEndianInt(rom, 0x2C, 0x1000)
        return rom
    }

    private fun writeLittleEndianInt(rom: ByteArray, offset: Int, value: Int) {
        rom[offset] = (value and 0xFF).toByte()
        rom[offset + 1] = ((value shr 8) and 0xFF).toByte()
        rom[offset + 2] = ((value shr 16) and 0xFF).toByte()
        rom[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    @Test
    fun `una extension ambigua se resuelve mirando la rom`() {
        assertEquals(ConsoleKind.GAME_BOY, ConsoleKind.detect("juego.bin", gameBoyRom("TETRIS")))
        // Lo que llega al importar de un comprimido: el nombre del archivo
        // elegido, no el de la entrada de dentro.
        assertEquals(ConsoleKind.GAME_BOY, ConsoleKind.detect("pack.zip", gameBoyRom("TETRIS")))
        assertEquals(ConsoleKind.GAME_BOY_ADVANCE, ConsoleKind.detect("juego.bin", gbaRom()))
    }

    @Test
    fun `sin pistas se resuelve a gba`() {
        // Es la consola histórica de la aplicación y el caso para el que ya
        // había bibliotecas: el valor por defecto no puede romperlas.
        assertEquals(ConsoleKind.GAME_BOY_ADVANCE, ConsoleKind.detect("", null))
        assertEquals(ConsoleKind.GAME_BOY_ADVANCE, ConsoleKind.detect("juego.zip", null))
        // Demasiado corto para tener cabecera de Game Boy.
        assertEquals(ConsoleKind.GAME_BOY_ADVANCE, ConsoleKind.detect("", ByteArray(16)))
    }

    @Test
    fun `una gb sin suma valida no se reconoce por los bytes`() {
        // Un homebrew que no corrigió la suma de cabecera. Sin extensión no hay
        // prueba de que sea un cartucho de Game Boy, y tratar una GBA grande
        // como GB dejaría la pantalla en negro sin explicación posible.
        val rota = gameBoyRom("X").also { it[0x14D] = (it[0x14D] + 1).toByte() }
        assertEquals(ConsoleKind.GAME_BOY_ADVANCE, ConsoleKind.detect("", rota))
        // Con extensión, la extensión manda y el juego entra igual.
        assertEquals(ConsoleKind.GAME_BOY, ConsoleKind.detect("homebrew.gb", rota))
    }

    /**
     * Cartucho de Game Boy mínimo pero completo: 32 KiB, logotipo de arranque
     * y suma de cabecera bien calculada, que es lo que el sniffing exige.
     */
    private fun gameBoyRom(title: String): ByteArray {
        val rom = ByteArray(32 * 1024)
        rom[0x104] = 0xCE.toByte()
        rom[0x105] = 0xED.toByte()
        title.forEachIndexed { index, ch -> rom[0x134 + index] = ch.code.toByte() }
        var checksum = 0
        for (index in 0x134..0x14C) {
            checksum = (checksum - (rom[index].toInt() and 0xFF) - 1) and 0xFF
        }
        rom[0x14D] = checksum.toByte()
        return rom
    }

    /** Relleno de GBA: sin las dos marcas que delatan un cartucho de Game Boy. */
    private fun gbaRom(): ByteArray = ByteArray(0xC0 + 8).also { rom ->
        for (index in rom.indices) rom[index] = 0x00
    }
}
