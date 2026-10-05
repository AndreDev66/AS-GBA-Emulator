package com.example.core

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * Data model for Game Boy Advance ROM Header analysis.
 * Parses the standard GBA cartridge header (offsets 0xA0 - 0xBF).
 *
 * Developed by Andrés Socorro for AS GBA Emulator.
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
    val formattedSize: String
        get() {
            val mb = romSizeBytes.toDouble() / (1024.0 * 1024.0)
            return String.format("%.1f MB", mb)
        }

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
                // Offsets:
                // 0xA0 - 0xAB: Game Title (12 characters, uppercase ASCII)
                val titleBytes = ByteArray(12)
                for (i in 0 until 12) {
                    titleBytes[i] = buffer.get(0xA0 + i)
                }

                val rawTitle = String(titleBytes, StandardCharsets.US_ASCII).trim { it <= ' ' || it == '\u0000' }
                val title = if (rawTitle.isEmpty()) "GBA GAME" else rawTitle

                // 0xAC - 0xAF: Game Code (4 chars, e.g. "BPEE" for Pokémon Emerald)
                val codeBytes = ByteArray(4)
                for (i in 0 until 4) {
                    codeBytes[i] = buffer.get(0xAC + i)
                }
                val gameCode = String(codeBytes, StandardCharsets.US_ASCII).trim { it <= ' ' || it == '\u0000' }

                // 0xB0 - 0xB1: Maker Code (2 chars, e.g. "01" for Nintendo)
                val makerBytes = ByteArray(2)
                for (i in 0 until 2) {
                    makerBytes[i] = buffer.get(0xB0 + i)
                }
                val makerCode = String(makerBytes, StandardCharsets.US_ASCII)

                // 0xB2: Fixed value 0x96
                val fixedVal = buffer.get(0xB2).toInt() and 0xFF
                val isFixedValid = (fixedVal == 0x96)

                // 0xBC: Software Version
                val version = buffer.get(0xBC).toInt() and 0xFF

                // 0xBD: Complement check
                val complement = buffer.get(0xBD).toInt() and 0xFF
                var calcComplement = 0
                for (i in 0xA0..0xBC) {
                    calcComplement = (calcComplement - (buffer.get(i).toInt() and 0xFF)) and 0xFF
                }
                calcComplement = (calcComplement - 0x19) and 0xFF
                val isChecksumValid = (calcComplement == complement)

                // Check for popular hackrom signatures and large sizes (32MB / 64MB)
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
