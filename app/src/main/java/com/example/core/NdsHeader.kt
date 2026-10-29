package com.example.core

/**
 * Cabecera de un cartucho de Nintendo DS.
 *
 * Son los primeros 4096 bytes del archivo y melonDS la analiza con las mismas
 * comprobaciones de CRC, pero esa información hace falta **antes** de que exista
 * ningún núcleo: es la biblioteca la que la guarda al importar la ROM, para
 * poner el título y el código de juego en la tarjeta y poder buscar por ellos.
 *
 * Por eso vive aquí y no se le pide nada al nativo. Cuando el juego se lanza,
 * melonDS vuelve a leer la cabecera y devuelve sus propios metadatos, que son los
 * que mandan: si el núcleo cambia su criterio, la biblioteca puede quedarse
 * con lo que haya guardado sin que nada se rompa.
 *
 * La disposición de la cabecera es:
 *
 * | Offset | Tamaño | Campo |
 * |--------|--------|-------|
 * | 0x00   | 12     | Título del juego |
 * | 0x0C   | 4      | Código de juego |
 * | 0x10   | 2      | Código del fabricante |
 * | 0x12   | 1      | Código de unidad (modelo de consola) |
 * | 0x1D   | 1      | Región |
 * | 0x20   | 4      | Desplazamiento del ejecutable ARM9 |
 * | 0x24   | 4      | Dirección de entrada ARM9 |
 * | 0x28   | 4      | Dirección de carga ARM9 |
 * | 0x2C   | 4      | Tamaño del ejecutable ARM9 |
 * | 0x30   | 4      | Desplazamiento del ejecutable ARM7 |
 */
data class NdsHeader(
    val title: String,
    val gameCode: String,
    val makerCode: String,
    val region: NdsRegion,
    val unit: NdsUnit,
) {
    /** Título listo para mostrar: sin espacios de relleno ni basura. */
    val displayTitle: String
        get() = title.ifBlank { gameCode.ifBlank { "Juego de Nintendo DS" } }

    companion object {
        /** Una cabecera de DS ocupa 4096 bytes; menos que eso no es un cartucho. */
        private const val HEADER_SIZE = 0x1000

        /**
         * Lee la cabecera, o devuelve `null` si los bytes no encajan con una de
         * Nintendo DS.
         *
         * Se conforma con lo que la Nintendo DS garantiza: el ejecutable
         * principal tiene que caber dentro del archivo y empezar tras la
         * cabecera. No es una comprobación de CRC —esa la hace melonDS al
         * arrancar, y aquí no hay nada que arrancar todavía.
         */
        fun parse(rom: ByteArray, romSize: Long = rom.size.toLong()): NdsHeader? {
            if (rom.size < HEADER_SIZE) return null

            val arm9Offset = readLittleEndianInt(rom, 0x20)
            val arm9Size = readLittleEndianInt(rom, 0x2C)
            if (arm9Size <= 0) return null
            if (arm9Offset < HEADER_SIZE) return null
            if (arm9Offset.toLong() + arm9Size.toLong() > romSize) return null
            if (!isPrintableAscii(rom, 0x0C, 4)) return null

            return NdsHeader(
                title = readAscii(rom, 0x00, 12),
                gameCode = readAscii(rom, 0x0C, 4),
                makerCode = readAscii(rom, 0x10, 2),
                region = NdsRegion.fromCode(rom[0x1D].toInt() and 0xFF),
                unit = NdsUnit.fromCode(rom[0x12].toInt() and 0xFF),
            )
        }

        /**
         * Comprueba que el ejecutable ARM7 pueda arrancar en memoria de la DS.
         *
         * melonDS permite cargar cartuchos cuya cabecera tiene ejecutables
         * truncados o direcciones fuera de la RAM; el direct boot luego salta a
         * esa dirección y deja ambas pantallas en blanco. Se comprueba antes de
         * entregar una ROM grande al núcleo nativo.
         */
        fun bootValidationError(rom: ByteArray, romSize: Long? = null): String? {
            if (rom.size < 0x40) return "La cabecera de Nintendo DS está incompleta."

            val arm7Offset = readUnsignedLittleEndianInt(rom, 0x30)
            val arm7Entry = readUnsignedLittleEndianInt(rom, 0x34)
            val arm7Ram = readUnsignedLittleEndianInt(rom, 0x38)
            val arm7Size = readUnsignedLittleEndianInt(rom, 0x3C)
            if (arm7Offset < HEADER_SIZE || arm7Size == 0L) {
                return "La ROM no contiene un ejecutable ARM7 válido."
            }
            if (romSize != null && arm7Offset + arm7Size > romSize) {
                return "El ejecutable ARM7 está truncado."
            }

            val arm7End = arm7Ram + arm7Size
            val inMainRam = arm7Ram >= MAIN_RAM_START && arm7End <= MAIN_RAM_END
            val inWorkRam = arm7Ram >= ARM7_WRAM_START && arm7End <= ARM7_WRAM_END
            if (!inMainRam && !inWorkRam) {
                return "La dirección de carga ARM7 está fuera de la RAM de Nintendo DS " +
                    "(0x${arm7Ram.toString(16).uppercase().padStart(8, '0')})."
            }
            if (arm7Entry < arm7Ram || arm7Entry >= arm7End) {
                return "La dirección de inicio ARM7 no pertenece al ejecutable " +
                    "(0x${arm7Entry.toString(16).uppercase().padStart(8, '0')})."
            }
            return null
        }

        private const val MAIN_RAM_START = 0x02000000L
        private const val MAIN_RAM_END = 0x02400000L
        private const val ARM7_WRAM_START = 0x037F8000L
        private const val ARM7_WRAM_END = 0x03810000L

        private fun readUnsignedLittleEndianInt(rom: ByteArray, offset: Int): Long =
            (rom[offset].toLong() and 0xFF) or
                ((rom[offset + 1].toLong() and 0xFF) shl 8) or
                ((rom[offset + 2].toLong() and 0xFF) shl 16) or
                ((rom[offset + 3].toLong() and 0xFF) shl 24)

        private fun readLittleEndianInt(rom: ByteArray, offset: Int): Int {
            return (rom[offset].toInt() and 0xFF) or
                ((rom[offset + 1].toInt() and 0xFF) shl 8) or
                ((rom[offset + 2].toInt() and 0xFF) shl 16) or
                ((rom[offset + 3].toInt() and 0xFF) shl 24)
        }

        private fun isPrintableAscii(rom: ByteArray, offset: Int, length: Int): Boolean {
            for (index in offset until offset + length) {
                val value = rom[index].toInt() and 0xFF
                if (value < 0x20 || value > 0x7E) return false
            }
            return true
        }

        /**
         * Lee texto de la cabecera y lo limpia.
         *
         * La DS rellena con espacios y a veces con ceros; algunos homebrew
         * ponen bytes que no son texto. Se corta en el primer caracter no
         * imprimible en lugar de sustituirlo, para no inventar un título.
         */
        private fun readAscii(rom: ByteArray, offset: Int, length: Int): String {
            val builder = StringBuilder(length)
            for (index in offset until offset + length) {
                val value = rom[index].toInt() and 0xFF
                if (value < 0x20 || value > 0x7E) break
                builder.append(value.toChar())
            }
            return builder.toString().trim()
        }
    }
}

/** Región declarada en la cabecera de un cartucho de Nintendo DS. */
enum class NdsRegion(val label: String) {
    JAPAN("J"),
    USA("E"),
    EUROPE("P"),
    AUSTRALIA("O"),
    CHINA("I"),
    KOREA("K"),
    REGION_FREE("libre"),
    UNKNOWN("?");

    companion object {
        fun fromCode(code: Int): NdsRegion = when (code) {
            0x00 -> JAPAN
            0x01 -> USA
            0x02 -> EUROPE
            0x03 -> AUSTRALIA
            0x04 -> CHINA
            0x05 -> KOREA
            0x0A -> REGION_FREE
            else -> UNKNOWN
        }
    }
}

/** Modelo de consola para el que se hizo el juego. */
enum class NdsUnit(val label: String) {
    DS("DS"),
    DS_PHAT("DS Phat"),
    DS_LITE("DS Lite"),
    DSI("DSi"),
    UNKNOWN("DS");

    companion object {
        fun fromCode(code: Int): NdsUnit = when (code) {
            0x03 -> DS_PHAT
            0x10 -> DS_LITE
            0x20, 0x21, 0x22 -> DSI
            else -> DS
        }
    }
}
