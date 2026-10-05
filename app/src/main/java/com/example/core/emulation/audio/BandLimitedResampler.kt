package com.example.core.emulation.audio

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Remuestreador de banda limitada, en flujo, PCM 16 bits estéreo entrelazado.
 *
 * Unir dos muestras con una recta es filtrar con un triángulo, cuya respuesta cae
 * despacio: deja pasar las imágenes del espectro plegadas y atenúa el alto de la
 * banda útil. En el sonido de una consola, hecho de ondas cuadradas y rico en
 * armónicos, eso se oye apagado y áspero. Aquí se convoluciona con un seno cardinal
 * enventanado, que las deja bajo el ruido de cuantificación.
 *
 * Los coeficientes se calculan una vez al construir, para [PHASE_COUNT] posiciones
 * fraccionarias. Cada muestra de salida cuesta [TAP_COUNT] multiplicaciones-sumas
 * por canal: alrededor de millón y medio por segundo a 48 kHz, despreciable frente
 * a la emulación y sin reservas dentro del bucle.
 *
 * Al ser simétrico, el filtro mira hacia delante y hacia atrás, así que la salida
 * va retrasada medio núcleo. Ese retraso es constante y no entra en la
 * sincronización.
 *
 * A igual ritmo y sin corrección pedida, [resample] copia la entrada sin calcular.
 */
class BandLimitedResampler(
    private val inputRate: Int,
    private val outputRate: Int,
) {
    init {
        require(inputRate > 0 && outputRate > 0) { "Ritmos inválidos" }
    }

    /** Tramos de entrada a avanzar por tramo de salida a velocidad nativa. */
    private val baseStep = inputRate.toDouble() / outputRate

    /**
     * Núcleo ordenado por fase: [PHASE_COUNT] juegos de [TAP_COUNT] coeficientes.
     *
     * Plano y no anidado, para que el recorrido de una fase sea contiguo en un
     * bucle que corre cuarenta y ocho mil veces por segundo.
     */
    private val kernel = DoubleArray(PHASE_COUNT * TAP_COUNT)

    /** Los últimos tramos de entrada, de los que el núcleo necesita a ambos lados. */
    private val historyLeft = DoubleArray(TAP_COUNT)
    private val historyRight = DoubleArray(TAP_COUNT)
    private var historyIndex = 0

    private var frac = 0.0

    val isIdentity: Boolean get() = inputRate == outputRate

    init {
        buildKernel()
    }

    /**
     * Tope de shorts producidos para [inputCount] shorts de entrada. [rateScale]
     * vale 1 a velocidad nativa y menos de 1 para estirar el sonido.
     */
    fun maxOutput(inputCount: Int, rateScale: Double = 1.0): Int {
        validateRateScale(rateScale)
        if (isIdentity && rateScale == 1.0) return inputCount
        val inFrames = inputCount / 2
        return (ceil(inFrames / (baseStep * rateScale)).toInt() + 2) * 2
    }

    /**
     * Remuestrea los [inputCount] primeros shorts de [input] (pares L/R) hacia
     * [output] y devuelve cuántos se escribieron, siempre en pares. Si [output] se
     * llena, para: no hay desbordamiento.
     */
    fun resample(
        input: ShortArray,
        inputCount: Int,
        output: ShortArray,
        rateScale: Double = 1.0,
    ): Int {
        validateRateScale(rateScale)
        if (isIdentity && rateScale == 1.0) {
            val n = min(inputCount, output.size)
            System.arraycopy(input, 0, output, 0, n)
            return n
        }
        val step = baseStep * rateScale
        var written = 0
        val frames = inputCount / 2
        var frame = 0
        while (frame < frames) {
            historyLeft[historyIndex] = input[frame * 2].toDouble()
            historyRight[historyIndex] = input[frame * 2 + 1].toDouble()
            historyIndex = (historyIndex + 1) and TAP_MASK

            while (frac < 1.0) {
                if (written + 1 >= output.size) return written
                // 256 posiciones fraccionarias dejan un error de colocación muy por
                // debajo del escalón de cuantificación de 16 bits.
                val phase = (frac * PHASE_COUNT).toInt().coerceIn(0, PHASE_COUNT - 1)
                val base = phase * TAP_COUNT
                var accumulatedLeft = 0.0
                var accumulatedRight = 0.0
                // `historyIndex` apunta a la casilla que se escribirá después, la
                // más antigua: el recorrido arranca ahí y remonta el tiempo.
                var slot = historyIndex
                for (tap in 0 until TAP_COUNT) {
                    val coefficient = kernel[base + tap]
                    accumulatedLeft += historyLeft[slot] * coefficient
                    accumulatedRight += historyRight[slot] * coefficient
                    slot = (slot + 1) and TAP_MASK
                }
                output[written++] = toSample(accumulatedLeft)
                output[written++] = toSample(accumulatedRight)
                frac += step
            }
            frac -= 1.0
            frame++
        }
        return written
    }

    /** Reinicia el estado (al reanudar tras un corte del flujo). */
    fun reset() {
        frac = 0.0
        historyLeft.fill(0.0)
        historyRight.fill(0.0)
        historyIndex = 0
    }

    /**
     * Redondea y acota.
     *
     * Truncar hacia cero cavaría una zona muerta alrededor del silencio, audible
     * como aspereza al final de las notas. Y el núcleo sobrepasa en las
     * transiciones (Gibbs), así que una señal ya cercana al máximo puede salir más
     * allá y, sin cota, rebotaría al otro extremo.
     */
    private fun toSample(value: Double): Short =
        value.roundToInt().coerceIn(-32768, 32767).toShort()

    private fun validateRateScale(rateScale: Double) {
        require(rateScale.isFinite() && rateScale > 0.0) { "Escala de ritmo inválida" }
    }

    /**
     * Calcula el núcleo, una vez.
     *
     * El corte va en la banda más baja de las dos: al subir de ritmo hay que
     * retener las imágenes por encima de la entrada, y al bajarlo hay que retirar
     * lo que la salida ya no podría portar. [ROLLOFF] lo deja un poco por debajo
     * para que el filtro caiga en vez de cortar en seco.
     *
     * Ventana de Blackman: sus lóbulos secundarios caen a 74 dB, por debajo del
     * ruido de cuantificación de 16 bits.
     */
    private fun buildKernel() {
        val cutoff = ROLLOFF * 0.5 * min(1.0, outputRate.toDouble() / inputRate)
        for (phase in 0 until PHASE_COUNT) {
            val offset = phase.toDouble() / PHASE_COUNT
            var sum = 0.0
            for (tap in 0 until TAP_COUNT) {
                // Posición del coeficiente respecto al punto interpolado, en
                // tramos de entrada. El centro del núcleo cae entre los dos
                // tramos que enmarcan ese punto.
                val distance = (tap - (CENTRE - 1)).toDouble() - offset
                val value = sinc(2.0 * cutoff * distance) * 2.0 * cutoff * window(tap, offset)
                kernel[phase * TAP_COUNT + tap] = value
                sum += value
            }
            // Ganancia unitaria por fase: si no, el nivel ondula al ritmo de
            // las fases y se oye como un siseo en el batido de ambos ritmos.
            if (sum != 0.0) {
                for (tap in 0 until TAP_COUNT) kernel[phase * TAP_COUNT + tap] /= sum
            }
        }
    }

    /** Ventana de Blackman, centrada en el punto interpolado. */
    private fun window(tap: Int, offset: Double): Double {
        val position = (tap.toDouble() + (1.0 - offset)) / TAP_COUNT
        if (position <= 0.0 || position >= 1.0) return 0.0
        val angle = 2.0 * PI * position
        return 0.42 - 0.5 * cos(angle) + 0.08 * cos(2.0 * angle)
    }

    private fun sinc(x: Double): Double {
        if (x == 0.0) return 1.0
        val angle = PI * x
        return sin(angle) / angle
    }

    companion object {
        /**
         * Longitud del núcleo, en tramos de entrada.
         *
         * Dieciséis bastan para bajar las imágenes bajo el ruido de cuantificación
         * sin que el retraso se note. Potencia de dos porque el historial se
         * recorre con máscara.
         */
        const val TAP_COUNT = 16
        private const val TAP_MASK = TAP_COUNT - 1
        private const val CENTRE = TAP_COUNT / 2

        /** Posiciones fraccionarias precalculadas entre dos tramos de entrada. */
        const val PHASE_COUNT = 256

        /**
         * Parte de la banda conservada antes del corte.
         *
         * Cortar justo a la mitad del ritmo pediría un filtro infinito. A 0,91 el
         * núcleo de dieciséis puntos tiene sitio para llegar al suelo, a costa de
         * un agudo que se detiene hacia los 15 kHz: por encima de lo que daba una
         * consola de la época y de lo que rinde un altavoz de teléfono.
         */
        private const val ROLLOFF = 0.91
    }
}
