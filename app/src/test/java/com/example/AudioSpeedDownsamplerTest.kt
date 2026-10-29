package com.example

import com.example.core.emulation.audio.AudioSpeedDownsampler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * El compresor de audio del avance rápido.
 *
 * Lo que hay que garantizar es que el bloque entregado dure lo que dura el avance
 * (si no, la cola de la pista crece o se vacía justo cuando se acelera) y que el
 * nivel no se caiga al promediar (si no, acelerar bajaría el volumen de golpe).
 */
class AudioSpeedDownsamplerTest {

    @Test
    fun `a velocidad normal no toca el bloque`() {
        val input = ShortArray(20) { it.toShort() }
        val downsampler = AudioSpeedDownsampler()

        val block = downsampler.reduce(input, 20, 1.0f)

        assertSame(input, block)
        assertEquals(20, downsampler.outputCount)
    }

    @Test
    fun `al doble devuelve la mitad de las muestras`() {
        val input = ShortArray(1024) { 1000 }
        val downsampler = AudioSpeedDownsampler()

        downsampler.reduce(input, 1024, 2.0f)

        // 512 tramas de entrada dan 256 de salida, 512 muestras entrelazadas.
        assertEquals(512, downsampler.outputCount)
    }

    @Test
    fun `la duracion es la del avance`() {
        val frames = 6000
        val input = ShortArray(frames * 2) { 1 }

        for (speed in listOf(1.5f, 2.0f, 4.0f, 8.0f)) {
            val downsampler = AudioSpeedDownsampler()
            downsampler.reduce(input, input.size, speed)

            assertEquals(
                "velocidad $speed",
                (frames / speed).toInt(),
                downsampler.outputCount / 2,
            )
        }
    }

    @Test
    fun `una senal constante sobrevive a cualquier velocidad`() {
        val input = ShortArray(2048) { 8000 }

        for (speed in listOf(1.5f, 2.0f, 4.0f, 8.0f)) {
            val downsampler = AudioSpeedDownsampler()
            val block = downsampler.reduce(input, input.size, speed)

            for (i in 0 until downsampler.outputCount) {
                assertEquals("velocidad $speed, muestra $i", 8000, block[i].toInt())
            }
        }
    }

    @Test
    fun `las dos vias se comprimen por su cuenta`() {
        // Un canal a tope y el otro al revés: mezclarlos al promediar se oiría como
        // un canal que se cuela en el otro.
        val input = ShortArray(1024 * 2)
        for (frame in 0 until 1024) {
            input[frame * 2] = 20000
            input[frame * 2 + 1] = -20000
        }
        val downsampler = AudioSpeedDownsampler()

        val block = downsampler.reduce(input, input.size, 4.0f)

        for (i in 0 until downsampler.outputCount / 2) {
            assertEquals(20000, block[i * 2].toInt())
            assertEquals(-20000, block[i * 2 + 1].toInt())
        }
    }

    @Test
    fun `un bloque mas corto que la velocidad no rompe`() {
        val downsampler = AudioSpeedDownsampler()

        val block = downsampler.reduce(ShortArray(2) { 5 }, 2, 8.0f)

        assertEquals(0, downsampler.outputCount)
        assertEquals(0, block.size)
    }
}
