package com.example.core.emulation.audio

import kotlin.math.abs

/**
 * Servocontrol del ritmo de producción sobre el reloj de salida.
 *
 * El motor produce 32768 muestras por segundo emulado y la salida consume 48000 por
 * segundo real. El cuarzo del teléfono no tiene por qué caer justo sobre la cadencia
 * de vídeo, y una desviación de una décima de por ciento, ordinaria, quita o añade un
 * tramo de ventaja cada dieciséis segundos.
 *
 * Sin corregirlo, el desvío se acumula en un solo sentido: el búfer acaba desbordando,
 * con la imagen cada vez más lenta, o vaciándose, y entonces llega la secuencia de
 * ruptura, parada y rellenado, un blanco de cien milisegundos que reaparece a
 * intervalos regulares.
 *
 * Aquí se corrige de forma permanente y muy leve: el remuestreador avanza en la
 * entrada un poco más rápido o más lento y produce un poco menos o un poco más. La
 * corrección está acotada a [MAX_CORRECTION], medio por ciento, por debajo del umbral
 * en que un oído nota un cambio de altura.
 *
 * Tres precauciones la vuelven inaudible: una zona muerta alrededor del objetivo,
 * donde la corrección vale exactamente uno; un límite de pendiente ([MAX_STEP] por
 * bloque), para que la altura se deslice en vez de saltar; y un rechazo de lecturas
 * absurdas, porque el contador de la plataforma vuelve a cero en cada vaciado.
 *
 * Es un bucle proporcional sin término integral: el error residual es lo que mantiene
 * la corrección, y la zona muerta hace que sólo se pague en altura cuando hace falta.
 */
class AudioClockGovernor(
    private val targetFrames: Int,
    private val toleranceFrames: Int = targetFrames / 4,
) {
    init {
        require(targetFrames > 0) { "Objetivo de relleno inválido" }
        require(toleranceFrames >= 0) { "Tolerancia inválida" }
    }

    /** Corrección actual, para pasar al remuestreador. */
    var rateScale: Double = 1.0
        private set

    /**
     * Toma la ventaja medida en la salida, en tramos, y devuelve la corrección del
     * bloque siguiente.
     *
     * Una ventaja superior al objetivo significa que el motor produce más rápido de
     * lo que consume la salida: la corrección pasa de uno, el remuestreador avanza
     * más rápido en la entrada y rinde menos tramos. Si va faltando, al revés.
     */
    fun onQueuedFrames(queuedFrames: Int): Double {
        if (queuedFrames < 0 || queuedFrames > targetFrames * IMPLAUSIBLE_FACTOR) {
            return rateScale
        }
        val error = queuedFrames - targetFrames
        val desired = if (abs(error) <= toleranceFrames) {
            1.0
        } else {
            val relative = error.toDouble() / (targetFrames * FULL_SCALE_ERROR)
            1.0 + MAX_CORRECTION * relative.coerceIn(-1.0, 1.0)
        }
        rateScale += (desired - rateScale).coerceIn(-MAX_STEP, MAX_STEP)
        return rateScale
    }

    /** Rearranca sin corrección (reanudación tras vaciar la salida). */
    fun reset() {
        rateScale = 1.0
    }

    companion object {
        /**
         * Corrección máxima, en proporción del ritmo.
         *
         * Medio por ciento son ocho centésimas y media de semitono. El umbral que se
         * suele dar por perceptible en un sonido sostenido es de cinco a diez veces eso.
         */
        const val MAX_CORRECTION = 0.005

        /**
         * Variación máxima de la corrección de un bloque a otro.
         *
         * Los bloques llegan al ritmo del vídeo, unos sesenta por segundo, así que la
         * corrección tarda alrededor de un tercio de segundo en recorrer su amplitud
         * entera. Ese deslizamiento no se oye; un salto, sí.
         */
        const val MAX_STEP = 0.00025

        /**
         * Desviación, en proporción del objetivo, a la que la corrección está a tope.
         *
         * La mitad del objetivo de desviación pide el medio por ciento y el resto
         * sigue proporcional. Con esta ganancia, un reloj desviado tres milésimas se
         * corrige conservando dos tercios de la ventaja buscada.
         */
        private const val FULL_SCALE_ERROR = 0.5

        /**
         * Por encima de este múltiplo del objetivo, la lectura no es creíble: el
         * propio búfer de salida no hace más que una fracción de eso.
         */
        private const val IMPLAUSIBLE_FACTOR = 16
    }
}
