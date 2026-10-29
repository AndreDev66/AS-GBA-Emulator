package com.example.core

/**
 * Cabecera de un cartucho de Game Boy o Game Boy Color.
 *
 * Son los campos 0x134-0x14F del archivo: título, marca de color (CGB), tipo de
 * cartucho, tamaños declarados y la suma de comprobación de cabecera.
 *
 * Ni la detección de consola ni el arranque pasan por aquí: de lo primero se
 * encarga [com.example.core.emulation.ConsoleKind] —que decide entre Game Boy y
 * Game Boy Color mirando la propia marca CGB— y de lo segundo el núcleo nativo,
 * que vuelve a validar la cabecera entera al cargar la ROM.
 *
 * Base: RavenEmu. Interfaz y fachada: AS GBA Emulator, de Andrés Socorro.
 */
data class GameBoyHeader(
    val title: String,
    /** 0x143: 0x80 = compatible con CGB, 0xC0 = sólo CGB, lo demás = DMG. */
    val cgbFlag: Int,
    val cartridgeType: Int,
    val romSizeBytes: Long,
    val ramSizeBytes: Long,
    val isChecksumValid: Boolean,
) {
    /** El cartucho arranca en color en una Game Boy Color y en modo DMG en las demás. */
    val supportsColor: Boolean
        get() = cgbFlag == 0x80 || cgbFlag == 0xC0

    /** El cartucho se niega a arrancar en una Game Boy original. */
    val isColorExclusive: Boolean
        get() = cgbFlag == 0xC0

    /** Tamaño declarado ya formateado, para los mensajes. */
    val formattedSize: String
        get() {
            val kb = romSizeBytes / 1024.0
            return if (romSizeBytes >= 1024L * 1024L) {
                String.format("%.1f MB", romSizeBytes / (1024.0 * 1024.0))
            } else {
                String.format("%.0f KB", kb)
            }
        }

    /** Título para mostrar: el del cartucho, o un relleno si viene vacío. */
    val displayTitle: String
        get() = title.ifBlank { "Unknown Game Boy Game" }

    companion object {
        fun parse(data: ByteArray): GameBoyHeader? {
            if (data.size < 0x150) return null
            val cgbFlag = data[0x143].toInt() and 0xFF
            // Con marca CGB el título ocupa 0x134-0x142; sin ella, el decimosexto
            // byte —0x143— sigue siendo parte del título.
            val titleEnd = if (cgbFlag == 0x80 || cgbFlag == 0xC0) 0x143 else 0x144
            val title = buildString {
                for (index in 0x134 until titleEnd) {
                    val ch = data[index].toInt().toChar()
                    if (ch.code in 0x20..0x7E) append(ch) else break
                }
            }.trim()
            return GameBoyHeader(
                title = title,
                cgbFlag = cgbFlag,
                cartridgeType = data[0x147].toInt() and 0xFF,
                romSizeBytes = romSizeBytes(data[0x148].toInt() and 0xFF),
                ramSizeBytes = ramSizeBytes(data[0x149].toInt() and 0xFF),
                isChecksumValid = headerChecksumMatches(data),
            )
        }

        /**
         * Tamaño de ROM declarado por el código de 0x148: 32 KB desplazados por
         * el código, más los tres códigos irregulares que usó la época del MBC5
         * (bancos 72, 80 y 96). Los códigos desconocidos se resuelven a cero en
         * lugar de a un tamaño equivocado.
         */
        private fun romSizeBytes(code: Int): Long = when (code) {
            in 0x00..0x08 -> 32_768L shl code
            0x52 -> 72L * 16_384L
            0x53 -> 80L * 16_384L
            0x54 -> 96L * 16_384L
            else -> 0L
        }

        /** Tamaño de RAM declarado por el código de 0x149, según Nintendo. */
        private fun ramSizeBytes(code: Int): Long = when (code) {
            0x01 -> 2_048L
            0x02 -> 8_192L
            0x03 -> 32_768L
            0x04 -> 131_072L
            0x05 -> 65_536L
            else -> 0L
        }

        /**
         * El mismo algoritmo que ejecuta la ROM de arranque de la Game Boy: una
         * resta acumulada sobre 0x134-0x14C que se compara con el byte de 0x14D.
         */
        private fun headerChecksumMatches(data: ByteArray): Boolean {
            var checksum = 0
            for (index in 0x134..0x14C) {
                checksum = (checksum - (data[index].toInt() and 0xFF) - 1) and 0xFF
            }
            return checksum == (data[0x14D].toInt() and 0xFF)
        }
    }
}
