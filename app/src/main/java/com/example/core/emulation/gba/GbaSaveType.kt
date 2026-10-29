package com.example.core.emulation.gba

/** Tipos de memoria persistente que se encuentran en un cartucho de Game Boy Advance. */
enum class GbaSaveType(val displayName: String, val sizeBytes: Int) {
    NONE("Ninguna", 0),
    SRAM("SRAM 32 KiB", 32 * 1024),
    FLASH_64K("Flash 64 KiB", 64 * 1024),
    FLASH_128K("Flash 128 KiB", 128 * 1024),
    EEPROM_512("EEPROM 512 B", 512),
    EEPROM_8K("EEPROM 8 KiB", 8 * 1024),
    ;

    companion object {
        /**
         * Deduce el tipo a partir de los marcadores ASCII alineados que deja la ROM.
         *
         * Réplica de `detect_save` (`save_factory.hpp`): la versión del marcador
         * EEPROM decide el tamaño, así que `EEPROM_V111` son 512 B y `EEPROM_V12x`
         * son 8 KiB. Quien manda es el núcleo nativo; esto es su referencia en Kotlin.
         */
        fun detect(rom: ByteArray): GbaSaveType {
            for ((marker, type) in listOf(
                "FLASH1M_V" to FLASH_128K,
                "FLASH512_V" to FLASH_64K,
                "FLASH_V" to FLASH_64K,
            )) {
                if (contains(rom, marker)) return type
            }
            eepromVersion(rom)?.let { return if (it == 0 || it == 111) EEPROM_512 else EEPROM_8K }
            for ((marker, type) in listOf("SRAM_F_V" to SRAM, "SRAM_V" to SRAM)) {
                if (contains(rom, marker)) return type
            }
            return NONE
        }

        /**
         * Versión tras `EEPROM_V`. `null` si la ROM no trae el marcador; `0` si el
         * marcador existe pero sus tres dígitos no son legibles, que el nativo
         * trata como 512 B.
         */
        private fun eepromVersion(rom: ByteArray): Int? {
            val prefix = "EEPROM_V".toByteArray(Charsets.US_ASCII)
            var offset = 0
            while (offset + prefix.size + 3 <= rom.size) {
                var match = true
                for (i in prefix.indices) {
                    if (rom[offset + i] != prefix[i]) {
                        match = false
                        break
                    }
                }
                if (match) {
                    var version = 0
                    for (i in 0 until 3) {
                        val digit = rom[offset + prefix.size + i].toInt() - '0'.code
                        if (digit !in 0..9) return 0
                        version = version * 10 + digit
                    }
                    return version
                }
                offset += 4
            }
            return null
        }

        private fun contains(rom: ByteArray, marker: String): Boolean {
            val bytes = marker.toByteArray(Charsets.US_ASCII)
            var offset = 0
            while (offset + bytes.size <= rom.size) {
                var match = true
                for (i in bytes.indices) {
                    if (rom[offset + i] != bytes[i]) {
                        match = false
                        break
                    }
                }
                if (match) return true
                offset += 4
            }
            return false
        }
    }
}
