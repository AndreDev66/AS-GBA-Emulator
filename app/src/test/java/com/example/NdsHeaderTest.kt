package com.example

import com.example.core.NdsHeader
import com.example.core.NdsRegion
import com.example.core.NdsUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lectura de la cabecera de un cartucho de Nintendo DS.
 *
 * Esta cabecera se lee en la biblioteca, antes de que exista ningún núcleo, para
 * poner el título y el código de juego en la tarjeta. El motivo de que las
 * pruebas se centren en el rechazo es que un `null` incorrecto es el fallo
 * silencioso: la ROM no se importa y la biblioteca simplemente no la enseña.
 */
class NdsHeaderTest {

    @Test
    fun `lee titulo, codigo y modelo`() {
        val header = NdsHeader.parse(rom(title = "MARIO KART D", code = "MKDE", unit = 0x00))
        requireNotNull(header)
        assertEquals("MARIO KART D", header.title)
        assertEquals("MKDE", header.gameCode)
        assertEquals(NdsUnit.DS, header.unit)
    }

    @Test
    fun `lee cabecera desde prefijo de rom grande`() {
        val rom = rom()
        val header = NdsHeader.parse(rom.copyOf(0x1000), rom.size.toLong())
        requireNotNull(header)
        assertEquals("AYPE", header.gameCode)
    }

    @Test
    fun `recorta los espacios de relleno`() {
        // La cabecera rellena con espacios hasta los 12 bytes. Si no se limpian,
        // el título de la tarjeta lleva un reguero de blancos detrás.
        val header = NdsHeader.parse(rom(title = "ZELDA    ", code = "YZLE"))
        requireNotNull(header)
        assertEquals("ZELDA", header.title)
    }

    @Test
    fun `un titulo vacio cae al codigo de juego`() {
        // Un homebrew puede no poner título. Un rótulo con el código de juego es
        // mejor que una cadena en blanco en la tarjeta.
        val header = NdsHeader.parse(rom(title = "            ", code = "HOM3"))
        requireNotNull(header)
        assertEquals("HOM3", header.displayTitle)
    }

    @Test
    fun `la region sale del byte de la cabecera`() {
        assertEquals(NdsRegion.EUROPE, NdsHeader.parse(rom(region = 0x02))?.region)
        assertEquals(NdsRegion.JAPAN, NdsHeader.parse(rom(region = 0x00))?.region)
        assertEquals(NdsRegion.REGION_FREE, NdsHeader.parse(rom(region = 0x0A))?.region)
        // Un valor que no existe no se inventa una región: se marca como
        // desconocido para que la biblioteca pueda decirlo.
        assertEquals(NdsRegion.UNKNOWN, NdsHeader.parse(rom(region = 0x7F))?.region)
    }

    @Test
    fun `el modelo distingue dsi de ds`() {
        assertEquals(NdsUnit.DSI, NdsHeader.parse(rom(unit = 0x20))?.unit)
        assertEquals(NdsUnit.DS_LITE, NdsHeader.parse(rom(unit = 0x10))?.unit)
    }

    @Test
    fun `acepta ejecutable ARM7 dentro de memoria de DS`() {
        val rom = rom()
        assertNull(NdsHeader.bootValidationError(rom, rom.size.toLong()))
    }

    @Test
    fun `rechaza ejecutable ARM7 con direccion de carga invalida`() {
        val rom = rom().also { writeLittleEndianInt(it, 0x38, 0x00363800) }
        val error = NdsHeader.bootValidationError(rom, rom.size.toLong())
        assertEquals(
            "La dirección de carga ARM7 está fuera de la RAM de Nintendo DS (0x00363800).",
            error
        )
    }

    @Test
    fun `rechaza entrada ARM7 que no pertenece al ejecutable`() {
        val rom = rom().also { writeLittleEndianInt(it, 0x34, 0x4F0) }
        val error = NdsHeader.bootValidationError(rom, rom.size.toLong())
        requireNotNull(error)
        assertTrue(error.contains("no pertenece al ejecutable"))
    }

    @Test
    fun `rechaza ejecutable ARM7 truncado`() {
        val rom = rom()
        assertEquals(
            "El ejecutable ARM7 está truncado.",
            NdsHeader.bootValidationError(rom, 0x2100)
        )
    }

    @Test
    fun `un archivo sin ejecutable principal no es un cartucho`() {
        // Es el rechazo que más importa: sin ARM9 no hay nada que arrancar, y
        // aceptarlo dejaría un juego en negro sin explicación.
        assertNull(NdsHeader.parse(ByteArray(0x1000)))

        val sinTamano = rom().also { writeLittleEndianInt(it, 0x2C, 0) }
        assertNull(NdsHeader.parse(sinTamano))

        // El ejecutable declarado cae fuera del archivo.
        val desbordado = rom().also { writeLittleEndianInt(it, 0x2C, 0x400000) }
        assertNull(NdsHeader.parse(desbordado))

        // Empieza antes de terminar la cabecera, donde no puede estar.
        val pronto = rom().also { writeLittleEndianInt(it, 0x20, 0x10) }
        assertNull(NdsHeader.parse(pronto))
    }

    @Test
    fun `una gb no se confunde con una ds`() {
        // La cabecera de Game Boy no declara ejecutable principal en 0x20/0x2C,
        // así que el tamaño sale a cero y se rechaza. Si algún día saliera, la
        // comprobación del código de juego en 0x0C es la segunda red.
        val gameBoy = ByteArray(32 * 1024).also {
            it[0x104] = 0xCE.toByte()
            it[0x105] = 0xED.toByte()
            writeLittleEndianInt(it, 0x20, 0x4000)
            writeLittleEndianInt(it, 0x2C, 0x1000)
        }
        assertNull(NdsHeader.parse(gameBoy))
    }

    /**
     * Cartucho de DS mínimo y bien formado: cabecera de 4096 bytes y ejecutable
     * principal declarado justo detrás, con el código de juego en 0x0C.
     */
    private fun rom(
        title: String = "NDS GAME",
        code: String = "AYPE",
        region: Int = 0x01,
        unit: Int = 0x00
    ): ByteArray {
        val bytes = ByteArray(16 * 1024)
        title.take(12).forEachIndexed { index, ch -> bytes[index] = ch.code.toByte() }
        code.take(4).forEachIndexed { index, ch -> bytes[0x0C + index] = ch.code.toByte() }
        bytes[0x12] = unit.toByte()
        bytes[0x1D] = region.toByte()
        writeLittleEndianInt(bytes, 0x20, 0x1000)
        writeLittleEndianInt(bytes, 0x24, 0x02000800)
        writeLittleEndianInt(bytes, 0x28, 0x02000000)
        writeLittleEndianInt(bytes, 0x2C, 0x1000)
        writeLittleEndianInt(bytes, 0x30, 0x2000)
        writeLittleEndianInt(bytes, 0x34, 0x02000000)
        writeLittleEndianInt(bytes, 0x38, 0x02000000)
        writeLittleEndianInt(bytes, 0x3C, 0x1000)
        return bytes
    }

    private fun writeLittleEndianInt(rom: ByteArray, offset: Int, value: Int) {
        rom[offset] = (value and 0xFF).toByte()
        rom[offset + 1] = ((value shr 8) and 0xFF).toByte()
        rom[offset + 2] = ((value shr 16) and 0xFF).toByte()
        rom[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }
}
