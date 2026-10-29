package com.example.core

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * Cabecera de un cartucho de Game Boy Advance.
 *
 * Son los bytes 0xA0-0xBF del archivo, y de ellos lo que de verdad decide algo
 * es el código de juego —con el que AS nombra partidas y estados—, la suma de
 * comprobación —que distingue un cartucho bien formado de un volcado roto— y el
 * tamaño declarado, que aquí se contrasta con el real del archivo.
 *
 * No es la vía por la que se decide qué consola emula una ROM: de eso se encarga
 * [com.example.core.emulation.ConsoleKind], que además tolera cabeceras
 * defectuosas, y el núcleo nativo vuelve a validarlo todo al cargar. Aquí se
 * describe la cabecera, no se confía en ella.
 *
 * Base: RavenEmu. Interfaz y fachada: AS GBA Emulator, de Andrés Socorro.
 */
data class GBAHeader(
    val title: String,
    val gameCode: String,
    val makerCode: String,
    val isFixedValueValid: Boolean,
    val softwareVersion: Int,
    val checksum: Int,
    val isChecksumValid: Boolean,
    val romSizeBytes: Long,
    val isHackRom: Boolean,
    val hackRomName: String?
) {
    /** Tamaño del archivo ya formateado, para los mensajes. */
    val formattedSize: String
        get() {
            val mb = romSizeBytes.toDouble() / (1024.0 * 1024.0)
            return String.format("%.1f MB", mb)
        }

    /** Título para mostrar: el de la hackrom si se reconoció, y si no el del cartucho. */
    val displayTitle: String
        get() = hackRomName ?: title.ifBlank { "Unknown GBA Game" }

    companion object {
        fun parse(data: ByteArray): GBAHeader? {
            if (data.size < 0xC0) return null
            return parse(ByteBuffer.wrap(data), data.size.toLong())
        }

        fun parse(buffer: ByteBuffer, totalSize: Long): GBAHeader? {
            if (totalSize < 0xC0) return null
            if (buffer.capacity() < 0xC0) return null
            try {
                // Desplazamientos de la cabecera de GBA:
                // 0xA0 - 0xAB: título del juego (12 caracteres, ASCII en mayúsculas)
                val titleBytes = ByteArray(12)
                for (i in 0 until 12) {
                    titleBytes[i] = buffer.get(0xA0 + i)
                }

                val rawTitle = String(titleBytes, StandardCharsets.US_ASCII).trim { it <= ' ' || it == '\u0000' }
                val title = if (rawTitle.isEmpty()) "GBA GAME" else rawTitle

                // 0xAC - 0xAF: código de juego (4 caracteres; "BPEE" es Pokémon Esmeralda)
                val codeBytes = ByteArray(4)
                for (i in 0 until 4) {
                    codeBytes[i] = buffer.get(0xAC + i)
                }
                val gameCode = String(codeBytes, StandardCharsets.US_ASCII).trim { it <= ' ' || it == '\u0000' }

                // 0xB0 - 0xB1: código del fabricante (2 caracteres; "01" es Nintendo)
                val makerBytes = ByteArray(2)
                for (i in 0 until 2) {
                    makerBytes[i] = buffer.get(0xB0 + i)
                }
                val makerCode = String(makerBytes, StandardCharsets.US_ASCII)

                // 0xB2: valor fijo, siempre 0x96
                val fixedVal = buffer.get(0xB2).toInt() and 0xFF
                val isFixedValid = (fixedVal == 0x96)

                // 0xBC: versión del software
                val version = buffer.get(0xBC).toInt() and 0xFF

                // 0xBD: complemento de comprobación
                val complement = buffer.get(0xBD).toInt() and 0xFF
                var calcComplement = 0
                for (i in 0xA0..0xBC) {
                    calcComplement = (calcComplement - (buffer.get(i).toInt() and 0xFF)) and 0xFF
                }
                calcComplement = (calcComplement - 0x19) and 0xFF
                val isChecksumValid = (calcComplement == complement)

                // Se buscan firmas de hackrom conocidas y tamaños grandes (32 MB /
                // 64 MB).
                val isLargeHack = totalSize > 16 * 1024 * 1024
                var hackName: String? = null

                // Sólo se reconoce un nombre de hack cuando la cabecera lo dice de
                // forma inequívoca. Antes, cualquier ROM de más de 16 MB se
                // renombraba como "<título de cabecera> (Expanded 32MB)", y como
                // las cabeceras de Pokémon Emerald miden doce caracteres los ROM
                // de 32 MB salían todos juntos como "POKEMON EMER (Expanded
                // 32MB)": Pokémon TRE 2026 y Elite Redux se presentaban con el
                // nombre del Emerald original. El nombre real de la ROM lo aporta
                // el archivo, que es lo que se usa para mostrarlo.
                when {
                    title.contains("UNBOUND", ignoreCase = true) || title.contains("UNBN", ignoreCase = true) || gameCode == "BPED" -> {
                        hackName = "Pokémon Unbound"
                    }
                    title.contains("RADICAL", ignoreCase = true) || title.contains("RR", ignoreCase = true) -> {
                        hackName = "Pokémon Radical Red"
                    }
                    title.contains("GAIA", ignoreCase = true) -> {
                        hackName = "Pokémon Gaia"
                    }
                    title.contains("ROGUE", ignoreCase = true) -> {
                        hackName = "Pokémon Emerald Rogue"
                    }
                    title.contains("GS CHRON", ignoreCase = true) -> {
                        hackName = "Pokémon GS Chronicles"
                    }
                }

                return GBAHeader(
                    title = title,
                    gameCode = gameCode,
                    makerCode = makerCode,
                    isFixedValueValid = isFixedValid,
                    softwareVersion = version,
                    checksum = complement,
                    isChecksumValid = isChecksumValid,
                    romSizeBytes = totalSize,
                    isHackRom = isLargeHack || hackName != null,
                    hackRomName = hackName
                )
            } catch (e: Exception) {
                return null
            }
        }
    }
}
