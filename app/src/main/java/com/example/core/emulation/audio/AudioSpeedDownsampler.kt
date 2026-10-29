package com.example.core.emulation.audio

import kotlin.math.min

/**
 * Comprime un bloque de audio por el factor del avance rápido.
 *
 * El motor entrega un bloque de muestras por fotograma y [AndroidAudioSink] las
 * pasa a la pista, que las consume a ritmo fijo. Acelerado, el motor produce más
 * bloques por segundo real de los que la pista consume: sin comprimirlos, la cola
 * crecería sin fin y la escritura bloquearía el propio avance. Aquí el bloque se
 * reduce por el factor y la pista lo consume igual de rápido, de modo que el
 * sonido dura lo que dura el avance y sale más agudo.
 *
 * Cada muestra de salida es la media de las muestras de entrada que le tocan, en
 * ventanas contiguas que cubren la entrada entera. Promediar hace de paso bajo, y
 * algo tiene que quitarlo antes de reducir: lo que la salida ya no puede portar se
 * plegaría dentro de la banda audible y sonaría a arenilla. No es un filtro de los
 * buenos, pero este sonido se escucha mientras se salta el juego.
 *
 * Es estéreo entrelazado y sin estado: cada bloque se comprime por su cuenta.
 */
class AudioSpeedDownsampler(private val channels: Int = CHANNELS) {

    private var compressed = ShortArray(0)

    /** Muestras válidas del bloque devuelto por la última llamada a [reduce]. */
    var outputCount: Int = 0
        private set

    /**
     * Comprime los [count] primeros valores de [input] por [speed] (mayor o igual
     * que 1) y devuelve el bloque que hay que leer.
     *
     * A velocidad normal devuelve [input] tal cual: la ruta del audio sin acelerar
     * no paga ninguna copia.
     */
    fun reduce(input: ShortArray, count: Int, speed: Float): ShortArray {
        outputCount = count
        if (speed <= 1.0f) return input

        val frames = count / channels
        val outFrames = (frames / speed).toInt()
        if (outFrames <= 0) {
            outputCount = 0
            return EMPTY
        }
        if (compressed.size < outFrames * channels) {
            compressed = ShortArray(outFrames * channels)
        }

        // El paso sale de las tramas ya redondeadas y no de `speed`: así las
        // ventanas cubren la entrada entera, sin huecos ni muestras contadas dos
        // veces, y la duración total cuadra.
        val step = frames.toDouble() / outFrames
        var slot = 0
        for (index in 0 until outFrames) {
            val from = index * step
            // La ventana es [from, from + step): la última trama que entra es la
            // anterior al borde, nunca la que empieza justo encima de él.
            val first = from.toInt()
            val last = min((from + step - EDGE).toInt(), frames - 1)
            for (channel in 0 until channels) {
                var sum = 0
                var taken = 0
                var frame = first
                while (frame <= last) {
                    sum += input[frame * channels + channel]
                    taken++
                    frame++
                }
                compressed[slot++] = if (taken == 0) input[first * channels + channel]
                else (sum / taken).toShort()
            }
        }
        outputCount = slot
        return compressed
    }

    private companion object {
        const val CHANNELS = 2

        /** Distancia con la que un borde exacto deja fuera la trama del borde. */
        const val EDGE = 1e-9

        val EMPTY = ShortArray(0)
    }
}
