package com.example

import com.example.core.emulation.gba.GbaSaveType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Detección del tipo de memoria persistente a partir de los marcadores de la ROM.
 *
 * El aviso que motivó estas pruebas: Mega Man Zero 4 informaba de partida
 * corrupta al guardar. La causa no era la escritura sino que la ROM, de 16 MiB
 * justos, acababa forzada a Flash 128 KiB por una heurística de tamaño cuando en
 * realidad es EEPROM (`EEPROM_V124`). DOOM II, también de 16 MiB, estaba igual.
 *
 * El tamaño de la ROM no describe su memoria: por eso estas pruebas fijan lo que
 * dice el marcador, que es lo único que el cartucho declara de sí mismo.
 */
class SaveTypeDetectionTest {

    private val sixteenMib = 16 * 1024 * 1024

    private fun romWithMarker(marker: String, sizeBytes: Int, offset: Int = 0x1000): ByteArray {
        val rom = ByteArray(sizeBytes)
        marker.toByteArray(Charsets.US_ASCII).copyInto(rom, offset)
        return rom
    }

    @Test
    fun megaManZero4EsEepromYNoFlash() {
        val rom = romWithMarker("EEPROM_V124", sixteenMib)
        assertEquals(GbaSaveType.EEPROM_8K, GbaSaveType.detect(rom))
    }

    @Test
    fun doomIIEsEepromYNoFlash() {
        val rom = romWithMarker("EEPROM_V120", sixteenMib)
        assertEquals(GbaSaveType.EEPROM_8K, GbaSaveType.detect(rom))
    }

    @Test
    fun pokemonEmeraldEsFlashDe128KiB() {
        val rom = romWithMarker("FLASH1M_V", sixteenMib)
        assertEquals(GbaSaveType.FLASH_128K, GbaSaveType.detect(rom))
    }

    @Test
    fun versionEepromDecideElTamano() {
        assertEquals(GbaSaveType.EEPROM_512, GbaSaveType.detect(romWithMarker("EEPROM_V111", 0x8000)))
        assertEquals(GbaSaveType.EEPROM_8K, GbaSaveType.detect(romWithMarker("EEPROM_V122", 0x8000)))
        assertEquals(GbaSaveType.EEPROM_8K, GbaSaveType.detect(romWithMarker("EEPROM_V124", 0x8000)))
        assertEquals(GbaSaveType.EEPROM_8K, GbaSaveType.detect(romWithMarker("EEPROM_V126", 0x8000)))
    }

    @Test
    fun eepromConMarcadorIlegibleCaeA512Bytes() {
        assertEquals(GbaSaveType.EEPROM_512, GbaSaveType.detect(romWithMarker("EEPROM_VV12", 0x8000)))
        assertEquals(GbaSaveType.EEPROM_512, GbaSaveType.detect(romWithMarker("EEPROM_V000", 0x8000)))
    }

    @Test
    fun flashDe64KiBSegunSuMarcador() {
        assertEquals(GbaSaveType.FLASH_64K, GbaSaveType.detect(romWithMarker("FLASH512_V", 0x8000)))
        assertEquals(GbaSaveType.FLASH_64K, GbaSaveType.detect(romWithMarker("FLASH_V", 0x8000)))
    }

    @Test
    fun sramSegunSuMarcador() {
        assertEquals(GbaSaveType.SRAM, GbaSaveType.detect(romWithMarker("SRAM_V", 0x8000)))
        assertEquals(GbaSaveType.SRAM, GbaSaveType.detect(romWithMarker("SRAM_F_V", 0x8000)))
    }

    @Test
    fun marcadorDesalineadoSeIgnora() {
        // El núcleo busca los marcadores alineados a 4 bytes: un "EEPROM_V124"
        // desalineado es ruido de otro texto, no una declaración de memoria.
        val rom = romWithMarker("EEPROM_V124", 0x8000, offset = 0x1001)
        assertEquals(GbaSaveType.NONE, GbaSaveType.detect(rom))
    }

    @Test
    fun romSinMarcadorNoAfilaTipo() {
        assertEquals(GbaSaveType.NONE, GbaSaveType.detect(ByteArray(0x8000)))
        assertEquals(GbaSaveType.NONE, GbaSaveType.detect(ByteArray(sixteenMib)))
    }
}