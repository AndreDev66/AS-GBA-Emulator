package com.example.core.emulation.audio

/**
 * Mantiene el estado de rellenado previo de una salida de audio en flujo.
 *
 * Una pista arrancada antes de contener suficientes muestras consume su primer
 * bloque mientras el siguiente aún se calcula. Queda entonces al borde de la
 * ruptura aunque el búfer asignado sea grande. Este controlador retrasa por
 * tanto el arranque hasta el umbral pedido.
 *
 * Una ruptura no dispara un nuevo rellenado previo: tras una ruptura la cola
 * está vacía, pero la pista sigue reproduciendo lo que llega, y la escritura
 * bloqueante sólo bloquea sobre una cola **llena**. El hilo de emulación calcula
 * un tramo en pocos milisegundos y produce dieciséis de sonido, de modo que la
 * reserva se reconstituye mucho más rápido que el tiempo real. El rellenado
 * previo guarda así su único papel útil: el arranque, y la reanudación tras un
 * vaciado voluntario ([reset], en pausa o parada de sesión).
 */
class AudioBufferPrimer(
    private val startThresholdSamples: Int,
) {
    init {
        require(startThresholdSamples > 0) { "Umbral de rellenado previo inválido" }
    }

    var queuedSamples: Int = 0
        private set

    var playbackStarted: Boolean = false
        private set

    /**
     * Registra [sampleCount] muestras entrelazadas escritas en la pista.
     * Devuelve verdadero una sola vez, cuando puede empezar la reproducción.
     */
    fun onSamplesQueued(sampleCount: Int): Boolean {
        if (sampleCount <= 0 || playbackStarted) return false
        queuedSamples = (queuedSamples.toLong() + sampleCount)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        if (queuedSamples < startThresholdSamples) return false
        playbackStarted = true
        return true
    }

    /** Rearranca con una pista vacía, tras un vaciado voluntario. */
    fun reset() {
        queuedSamples = 0
        playbackStarted = false
    }
}
