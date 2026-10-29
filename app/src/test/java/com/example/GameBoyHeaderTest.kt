package com.example

import com.example.core.GameBoyHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lectura de la cabecera de un cartucho de Game Boy / Game Boy Color.
 *
 * La cabecera es lo único que dice qué consola es la ROM y de qué tamaño es,
 * y a diferencia de la de GBA el título no está en el principio del archivo:
 * vive en 0x134, después del logotipo, y su largo depende de si la ROM reserva
 * el último byte para la bandera de color.
 */
class GameBoyHeaderTest {

    @Test
    fun `lee titulo tipo de cartucho y tamanos`() {
        val header = GameBoyHeader.parse(cartucho(title = "TETRIS", cgbFlag = 0x00, romSizeCode = 0x01, ramSizeCode = 0x02))
        assertEquals("TETRIS", header?.title)
        assertEquals("TETRIS", header?.displayTitle)
        assertFalse(header!!.supportsColor)
        assertFalse(header.isColorExclusive)
        assertEquals(64 * 1024L, header.romSizeBytes)
        assertEquals("64 KB", header.formattedSize)
        assertEquals(8 * 1024L, header.ramSizeBytes)
        assertTrue(header.isChecksumValid)
    }

    @Test
    fun `el titulo de color deja fuera el byte de la bandera`() {
        // Con bandera CGB el título ocupa 0x134-0x142 y 0x143 es la bandera: si
        // el título se leyera entero, el último carácter sería 0xC0.
        val header = GameBoyHeader.parse(cartucho(title = "ZELDA", cgbFlag = 0x80))
        assertEquals("ZELDA", header?.title)
        assertTrue(header!!.supportsColor)
        assertFalse(header.isColorExclusive)
    }

    @Test
    fun `una rom exclusiva de color se marca como tal`() {
        val header = GameBoyHeader.parse(cartucho(title = "GBC", cgbFlag = 0xC0))
        assertTrue(header!!.supportsColor)
        assertTrue(header.isColorExclusive)
    }

    @Test
    fun `el titulo se corta en el primer caracter no imprimible`() {
        val rom = cartucho(title = "ABC", cgbFlag = 0x00)
        // 0x134-0x136 son "ABC" y 0x137 deja de ser texto: un título en UTF-8 o
        // un título KOMSI-filled siguen siendo legibles hasta ese punto.
        rom[0x137] = 0x00
        assertEquals("ABC", GameBoyHeader.parse(rom)?.title)
    }

    @Test
    fun `un titulo vacio no deja la biblioteca en blanco`() {
        val rom = cartucho(title = "", cgbFlag = 0x00)
        val header = GameBoyHeader.parse(rom)
        assertEquals("", header?.title)
        assertEquals("Unknown Game Boy Game", header?.displayTitle)
    }

    @Test
    fun `una suma de cabecera mal calculada se detecta`() {
        val rom = cartucho(title = "PIRATA", cgbFlag = 0x00)
        rom[0x14D] = (rom[0x14D] + 1).toByte()
        assertFalse(GameBoyHeader.parse(rom)!!.isChecksumValid)
    }

    @Test
    fun `un archivo que no llega a la cabecera no da cartucho`() {
        assertNull(GameBoyHeader.parse(ByteArray(0x14F)))
    }

    @Test
    fun `los tamanos irregulares de mbc5 se resuelven`() {
        assertEquals(
            72L * 16 * 1024,
            GameBoyHeader.parse(cartucho(title = "X", cgbFlag = 0x00, romSizeCode = 0x52))!!.romSizeBytes
        )
        assertEquals(
            96L * 16 * 1024,
            GameBoyHeader.parse(cartucho(title = "X", cgbFlag = 0x00, romSizeCode = 0x54))!!.romSizeBytes
        )
        // Un código que no existe no inventa un tamaño.
        assertEquals(
            0L,
            GameBoyHeader.parse(cartucho(title = "X", cgbFlag = 0x00, romSizeCode = 0x77))!!.romSizeBytes
        )
    }

    /** Cartucho de 32 KiB con la cabecera escrita y su suma bien calculada. */
    private fun cartucho(
        title: String,
        cgbFlag: Int,
        romSizeCode: Int = 0x00,
        ramSizeCode: Int = 0x00,
    ): ByteArray {
        val rom = ByteArray(32 * 1024)
        title.forEachIndexed { index, ch -> rom[0x134 + index] = ch.code.toByte() }
        rom[0x143] = cgbFlag.toByte()
        rom[0x147] = 0x1B // MBC5 + RAM + batería, el cartucho típico de Color
        rom[0x148] = romSizeCode.toByte()
        rom[0x149] = ramSizeCode.toByte()
        var checksum = 0
        for (index in 0x134..0x14C) {
            checksum = (checksum - (rom[index].toInt() and 0xFF) - 1) and 0xFF
        }
        rom[0x14D] = checksum.toByte()
        return rom
    }
}
