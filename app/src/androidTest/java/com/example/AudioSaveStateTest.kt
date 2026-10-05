package com.example

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.core.emulation.gba.RavenGbaCore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El sonido de una partida guardada tiene que seguir sonando al cargarla.
 *
 * Un juego habilita el audio una vez al arrancar y no vuelve a escribir
 * SOUNDCNT_X ni SOUNDCNT_H: es la secuencia de arranque del sonido, no parte de
 * cada nota. El estado instantáneo es, por tanto, la **única** forma que tiene el
 * núcleo de saber que el sonido estaba en marcha cuando lo guardó.
 *
 * El estado que se guardaba no incluía el APU, así que al cargarlo el núcleo
 * nacía con el sonido apagado mientras el juego, él, seguía creyéndolo
 * encendido. Como el juego no reescribe esos registros, nadie lo corregía y la
 * partida continuaba en silencio hasta que se reiniciara la máquina.
 *
 * Se prueba sobre el dispositivo porque el núcleo es una biblioteca nativa: la
 * misma frontera JNI que usa la aplicación.
 */
@RunWith(AndroidJUnit4::class)
class AudioSaveStateTest {

    @Test
    fun elSonContinuaDespuesDeCargarUnEstado() {
        RavenGbaCore().use { core ->
            core.loadRom(romQueDejaSonandoUnTono())
            val video = IntArray(VIDEO_PIXELS)

            // La ROM de prueba deja el APU sonando y no vuelve a tocar sus
            // registros: cualquier nota posterior depende de que el estado
            // instantaneo lleve el APU consigo.
            val antesDeGuardar = audioDe(core, video, FRAMES_ANTES_DE_GUARDAR)
            assertFalse("La ROM de prueba debería sonar antes de guardar", antesDeGuardar.estaMuda())

            val estado = core.saveState()
            val referencia = audioDe(core, video, FRAMES_COMPARADOS)

            core.loadState(estado)
            val recargado = audioDe(core, video, FRAMES_COMPARADOS)

            assertFalse(
                "Tras cargar un estado el juego no vuelve a escribir SOUNDCNT_X: si el " +
                    "APU no se restaura, la partida sigue muda hasta reiniciar la máquina",
                recargado.estaMuda(),
            )
            assertEquals(
                "Cargar un estado debe devolver el mismo número de muestras por trama",
                referencia.size,
                recargado.size,
            )
            assertArrayEquals(
                "Cargar un estado debe devolver el mismo sonido, no una reiniciación",
                referencia,
                recargado,
            )
        }
    }

    @Test
    fun cargarUnEstadoNoPierdeLosRegistrosDelSon() {
        RavenGbaCore().use { core ->
            core.loadRom(romQueDejaSonandoUnTono())
            val video = IntArray(VIDEO_PIXELS)
            audioDe(core, video, FRAMES_ANTES_DE_GUARDAR)

            val antesDeCargar = core.saveState()
            core.loadState(antesDeCargar)

            // El estado guardado ahora lleva el APU: si el bloque se leyera
            // desplazado, o faltara una palabra, esta carga fallaría en vez de
            // devolver un APU mutilado y mudo. Y si el estado se restaura en los
            // dos sentidos, volver a guardarlo da los mismos bytes.
            assertArrayEquals(
                "Cargar un estado debe devolver exactamente el mismo estado",
                antesDeCargar,
                core.saveState(),
            )
        }
    }

    /** Ejecuta [frames] tramas y devuelve todas las muestras leídas, en orden. */
    private fun audioDe(core: RavenGbaCore, video: IntArray, frames: Int): ShortArray {
        val muestras = ArrayList<Short>(frames * AUDIO_BUFFER_SAMPLES)
        val buffer = ShortArray(AUDIO_BUFFER_SAMPLES)
        repeat(frames) {
            core.runFrame(video, false)
            val count = core.readAudio(buffer)
            for (index in 0 until count) muestras.add(buffer[index])
        }
        return muestras.toShortArray()
    }

    private fun ShortArray.estaMuda(): Boolean {
        var max = 0
        for (value in this) {
            val v = if (value < 0) -value.toInt() else value.toInt()
            if (v > max) max = v
        }
        return max < 8
    }

    /**
     * ROM mínima que enciende el APU y deja sonar un tono cuadrado de 256 Hz.
     *
     * El programa es ARM porque así arranca la GBA, y termina en un bucle
     * infinito: las escrituras de sonido ocurren **una vez**, exactamente como en
     * un juego real. Por eso el estado instantáneo no puede evitarse para que el
     * sonido siga sonando.
     */
    private fun romQueDejaSonandoUnTono(): ByteArray {
        val rom = ByteArray(ROM_SIZE)
        // El motor exige el marcador fijo de Game Boy Advance en 0xB2.
        rom[0xB2] = 0x96.toByte()

        // Ojo con el orden de los campos: `Rn` son los bits 19-16 (nibble 4) y
        // `Rd` los 15-12 (nibble 3), así que `str r1, [r0]` se codifica
        // 0xE580_1000. Con los nibbles al revés la escritura usa r1 como base
        // y no toca los registros de sonido.
        // ldr r0, [pc, #0x30] ; 0x04000000 — base de los registros de E/S
        escribirPalabra(rom, 0x00, 0xE59F0030)
        // ldr r1, [pc, #0x30] ; 0xF0BF0000 — NR10 = 0, NR11/NR12 = 0xF0BF
        escribirPalabra(rom, 0x04, 0xE59F1030)
        // str r1, [r0, #0x60]
        escribirPalabra(rom, 0x08, 0xE5801060)
        // ldr r1, [pc, #0x2C] ; 0x00008400 — NR13/NR14: frecuencia y disparo
        escribirPalabra(rom, 0x0C, 0xE59F102C)
        // str r1, [r0, #0x64]
        escribirPalabra(rom, 0x10, 0xE5801064)
        // ldr r0, [pc, #0x28] ; 0x04000080 — base del control de sonido
        escribirPalabra(rom, 0x14, 0xE59F0028)
        // ldr r1, [pc, #0x28] ; 0x8002FF77 — SOUNDCNT_L y SOUNDCNT_H
        escribirPalabra(rom, 0x18, 0xE59F1028)
        // str r1, [r0]
        escribirPalabra(rom, 0x1C, 0xE5801000)
        // ldr r1, [pc, #0x24] ; 0x00000080 — SOUNDCNT_X: sonido habilitado
        escribirPalabra(rom, 0x20, 0xE59F1024)
        // str r1, [r0, #4]
        escribirPalabra(rom, 0x24, 0xE5801004)
        // b . — el juego deja de tocar el sonido y espera
        escribirPalabra(rom, 0x28, 0xEAFFFFFE)

        escribirPalabra(rom, 0x38, 0x04000000)
        escribirPalabra(rom, 0x3C, 0xF0BF0000)
        escribirPalabra(rom, 0x40, 0x00008400)
        escribirPalabra(rom, 0x44, 0x04000080)
        escribirPalabra(rom, 0x48, 0x8002FF77)
        escribirPalabra(rom, 0x4C, 0x00000080)
        return rom
    }

    /**
     * Escribe una palabra de 32 bits en [offset], en orden little-endian.
     *
     * El parámetro es [Long] porque las instrucciones ARM de este programa pasan
     * de `0xE0000000`: Kotlin tiparía esos literales como `Int` solo si el signo
     * estuviera ya aplicado, y el truncamiento silencioso sería un programa
     * distinto.
     */
    private fun escribirPalabra(rom: ByteArray, offset: Int, value: Long) {
        rom[offset] = (value and 0xFF).toByte()
        rom[offset + 1] = (value ushr 8 and 0xFF).toByte()
        rom[offset + 2] = (value ushr 16 and 0xFF).toByte()
        rom[offset + 3] = (value ushr 24 and 0xFF).toByte()
    }

    private companion object {
        const val ROM_SIZE = 0x8000
        const val VIDEO_PIXELS = 240 * 160
        const val AUDIO_BUFFER_SAMPLES = 2048
        const val FRAMES_ANTES_DE_GUARDAR = 20
        const val FRAMES_COMPARADOS = 10
    }
}