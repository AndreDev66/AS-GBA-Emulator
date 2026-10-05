package com.example

import com.example.core.GBAHeader
import com.example.core.GBACore
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer

/**
 * Unit tests for AS GBA Emulator components.
 * Developed by Andrés Socorro.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GBACoreUnitTest {

    @Test
    fun testGbaHeaderParsing_standardGba() {
        val bytes = ByteArray(0xC0)
        val buffer = ByteBuffer.wrap(bytes)

        // Title at 0xA0
        val titleBytes = "POKEMON EMER".toByteArray(Charsets.US_ASCII)
        System.arraycopy(titleBytes, 0, bytes, 0xA0, titleBytes.size)

        // Game code at 0xAC
        val codeBytes = "BPEE".toByteArray(Charsets.US_ASCII)
        System.arraycopy(codeBytes, 0, bytes, 0xAC, codeBytes.size)

        // Maker code at 0xB0
        val makerBytes = "01".toByteArray(Charsets.US_ASCII)
        System.arraycopy(makerBytes, 0, bytes, 0xB0, makerBytes.size)

        // Fixed value 0x96 at 0xB2
        bytes[0xB2] = 0x96.toByte()

        val header = GBAHeader.parse(buffer, 16 * 1024 * 1024L)

        assertNotNull(header)
        assertEquals("POKEMON EMER", header?.title)
        assertEquals("BPEE", header?.gameCode)
        assertTrue(header?.isFixedValueValid == true)
    }

    @Test
    fun testHackRomDetection_pokemonUnbound() {
        val bytes = ByteArray(0xC0)
        val buffer = ByteBuffer.wrap(bytes)

        val titleBytes = "POKEMON UNBN".toByteArray(Charsets.US_ASCII)
        System.arraycopy(titleBytes, 0, bytes, 0xA0, titleBytes.size)

        val codeBytes = "BPED".toByteArray(Charsets.US_ASCII)
        System.arraycopy(codeBytes, 0, bytes, 0xAC, codeBytes.size)

        bytes[0xB2] = 0x96.toByte()

        val header = GBAHeader.parse(buffer, 32 * 1024 * 1024L)

        assertNotNull(header)
        assertTrue(header?.isHackRom == true)
        assertEquals("Pokémon Unbound", header?.hackRomName)
        assertEquals("32.0 MB", header?.formattedSize)
    }

    @Test
    fun testGbaKeyMasking() {
        val core = GBACore(ApplicationProvider.getApplicationContext())
        assertFalse(core.isKeyPressed(GBACore.KEY_A))

        core.setKey(GBACore.KEY_A, true)
        assertTrue(core.isKeyPressed(GBACore.KEY_A))
        assertFalse(core.isKeyPressed(GBACore.KEY_B))

        core.setKey(GBACore.KEY_B, true)
        assertTrue(core.isKeyPressed(GBACore.KEY_A))
        assertTrue(core.isKeyPressed(GBACore.KEY_B))

        core.setKey(GBACore.KEY_A, false)
        assertFalse(core.isKeyPressed(GBACore.KEY_A))
        assertTrue(core.isKeyPressed(GBACore.KEY_B))

        core.release()
    }
}
