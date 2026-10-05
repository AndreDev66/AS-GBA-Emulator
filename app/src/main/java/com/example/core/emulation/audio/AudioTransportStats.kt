package com.example.core.emulation.audio

/**
 * Registro del transporte de audio, del motor hasta la salida de la plataforma.
 *
 * Un chasquido puede nacer en tres sitios y nada los distingue al oído: el motor
 * que produce un flujo discontinuo, el remuestreo que pierde un trozo, o la
 * salida del sistema que se vacía entre dos escrituras. Cada uno deja aquí una
 * huella distinta.
 *
 * ### Coste
 * Todo está condicionado a [enabled], dejado en `false` por defecto. Desactivado,
 * una lectura se reduce a leer un booleano; ningún contador se toca y ninguna
 * cadena se construye.
 *
 * ### Hilos de ejecución
 * Los contadores los alimenta el único hilo de emulación y los lee la interfaz.
 * Los campos son `@Volatile` para que la lectura vea un valor reciente; no se
 * promete ninguna coherencia conjunta entre dos contadores, y ninguna decisión
 * depende de ella — son indicadores, no un estado.
 */
class AudioTransportStats {

    /** Registro activo. Falso por defecto: no se cuenta nada. */
    @Volatile
    var enabled: Boolean = false

    /** Muestras entrelazadas entregadas por el motor a la salida. */
    @Volatile
    var samplesSubmitted: Long = 0L
        private set

    /** Muestras entrelazadas producidas por el remuestreo. */
    @Volatile
    var samplesResampled: Long = 0L
        private set

    /** Muestras entrelazadas efectivamente aceptadas por la salida. */
    @Volatile
    var samplesWritten: Long = 0L
        private set

    /** Bloques cuyo final no pudo escribirse: otras tantas discontinuidades. */
    @Volatile
    var shortWrites: Int = 0
        private set

    /** Rupturas acumuladas reportadas por la plataforma. */
    @Volatile
    var outputUnderruns: Int = 0
        private set

    /** Excepciones lanzadas por la salida. */
    @Volatile
    var failures: Int = 0
        private set

    /** Descripción de la última excepción, o `null`. */
    @Volatile
    var lastFailure: String? = null
        private set

    /** Un bloque ha cruzado la cadena: entregado, remuestreado, escrito. */
    fun onBlock(submitted: Int, resampled: Int, written: Int) {
        if (!enabled) return
        samplesSubmitted += submitted.coerceAtLeast(0)
        samplesResampled += resampled.coerceAtLeast(0)
        samplesWritten += written.coerceAtLeast(0)
        if (written < resampled) shortWrites++
    }

    /** Contador acumulativo de rupturas reportado por la plataforma. */
    fun onUnderrunCount(total: Int) {
        if (!enabled) return
        outputUnderruns = maxOf(outputUnderruns, total.coerceAtLeast(0))
    }

    /**
     * La salida ha lanzado una excepción. El mensaje se conserva tal cual: viene
     * de la plataforma y no contiene ni rutas ni datos del usuario.
     */
    fun onFailure(error: Throwable) {
        if (!enabled) return
        failures++
        lastFailure = "${error.javaClass.simpleName}: ${error.message ?: "sin mensaje"}"
    }

    /**
     * Resumen de una línea, destinado a la capa de depuración. Vacío mientras el
     * registro esté inactivo: nada se formatea para nada.
     */
    fun summary(): String {
        if (!enabled) return ""
        val perdidos = samplesResampled - samplesWritten
        return buildString {
            append("audio ")
            append(samplesSubmitted / 2)
            append('→')
            append(samplesWritten / 2)
            append(" tr")
            if (perdidos > 0) append(" -").append(perdidos / 2)
            if (shortWrites > 0) append(" trun:").append(shortWrites)
            if (outputUnderruns > 0) append(" vac:").append(outputUnderruns)
            if (failures > 0) append(" err:").append(failures)
        }
    }

    fun reset() {
        samplesSubmitted = 0L
        samplesResampled = 0L
        samplesWritten = 0L
        shortWrites = 0
        outputUnderruns = 0
        failures = 0
        lastFailure = null
    }
}
