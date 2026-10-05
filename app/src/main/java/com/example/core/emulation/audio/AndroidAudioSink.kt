package com.example.core.emulation.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.ceil

/**
 * Salida de audio por AudioTrack en modo flujo.
 *
 * El motor produce sus muestras a [sourceRateHz] (32768 Hz). En vez de dejar que el
 * sistema remuestree hacia el ritmo de salida, con una calidad que depende del
 * aparato, se abre el AudioTrack al ritmo nativo del dispositivo y se remuestrea uno
 * mismo ([BandLimitedResampler]).
 *
 * [write] es bloqueante: llamado desde el hilo de emulación, marca la cadencia de la
 * sesión sobre el reloj de audio del sistema, cualquiera que sea el ritmo de salida.
 *
 * Ese calado deja subsistir una deriva: el segundo emulado y el del cuarzo no duran
 * igual, y la diferencia se acumula siempre en el mismo sentido hasta vaciar o saturar
 * la pista. [AudioClockGovernor] lo corrige de continuo con un ajuste demasiado
 * pequeño para oírse, lo que evita la secuencia ruptura-vaciado-rellenado y el blanco
 * periódico que producía.
 *
 * Una ruptura reportada por la plataforma se cuenta, no se repara. La reparación de
 * antes (parar, vaciar, rellenar) tiraba la ventaja ya calculada e imponía cien
 * milisegundos de silencio para una interrupción de unos pocos; ver [AudioBufferPrimer].
 * La cola se reconstituye sola, ya que la escritura bloqueante sólo bloquea sobre una
 * cola llena.
 *
 * [stats] registra qué le ocurre a cada bloque. Inactivo por defecto: sólo cuesta la
 * lectura de un booleano por llamada.
 */
class AndroidAudioSink(
    context: Context,
    sourceRateHz: Int,
    sourceSamplesPerFrame: Int,
    val stats: AudioTransportStats = AudioTransportStats(),
) {

    private val outputRate = resolveNativeRate(context)
    private val resampler = BandLimitedResampler(sourceRateHz, outputRate)
    private var resampled = ShortArray(0)
    private val primer: AudioBufferPrimer
    private val governor: AudioClockGovernor
    private val track: AudioTrack

    /**
     * Tramos entregados a la pista desde el último vaciado.
     *
     * `playbackHeadPosition` cuenta los reproducidos y vuelve a cero en cada `flush`:
     * los dos contadores se reinician juntos, así que su diferencia es la ventaja
     * pendiente en la salida.
     */
    private var framesWritten = 0L

    /** Puesto a `true` por [unblock]: no se intenta ninguna escritura más. */
    @Volatile
    private var stopped = false

    init {
        val outputFramesPerVideoFrame =
            ceil(sourceSamplesPerFrame.toDouble() * outputRate / sourceRateHz).toInt()
        val minBuffer = AudioTrack.getMinBufferSize(
            outputRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(0)
        // Ocho tramos absorben un pico puntual de planificación de Android.
        // Se escriben seis tramos antes de la primera llamada a play(), unos
        // 100 ms de reserva a 60 Hz.
        val bufferBytes = maxOf(
            minBuffer,
            outputFramesPerVideoFrame *
                CHANNEL_COUNT *
                BYTES_PER_SAMPLE *
                BUFFER_VIDEO_FRAMES,
        )
        primer = AudioBufferPrimer(
            outputFramesPerVideoFrame * CHANNEL_COUNT * PRIME_VIDEO_FRAMES,
        )
        // La ventaja buscada es la que acaba de instalar el rellenado previo: el
        // servocontrol tiene como único papel mantenerla.
        governor = AudioClockGovernor(outputFramesPerVideoFrame * PRIME_VIDEO_FRAMES)
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            // Ruta de baja latencia cuando está disponible (reduce el búfer
            // hardware, y por tanto el desfase sonido/imagen).
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(outputRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    /**
     * Escribe un bloque, sin capturar excepciones.
     *
     * Quien llama ya trata el fallo: sigue sin sonido y lo reporta. Capturar aquí
     * dejaba ese camino inaccesible y una pista en error quedaba muda sin aviso.
     */
    fun write(samples: ShortArray, count: Int) {
        stats.onUnderrunCount(currentUnderrunCount())

        if (stopped) return
        val scale = correctionForDrift()
        val needed = resampler.maxOutput(count, scale)
        if (resampled.size < needed) resampled = ShortArray(needed)
        val produced = resampler.resample(samples, count, resampled, scale)

        var offset = 0
        while (offset < produced) {
            val written = track.write(
                resampled,
                offset,
                produced - offset,
                AudioTrack.WRITE_BLOCKING,
            )
            if (written <= 0) break
            offset += written
        }
        framesWritten += offset / CHANNEL_COUNT
        stats.onBlock(submitted = count, resampled = produced, written = offset)

        // AudioTrack sólo empieza a consumir tras el rellenado previo. Las
        // llamadas siguientes mantienen esa ventaja en vez de correr al borde de
        // una nueva ruptura.
        if (primer.onSamplesQueued(offset)) track.play()
    }

    fun underrunCount(): Int = currentUnderrunCount()

    /**
     * Corrección de ritmo del bloque actual.
     *
     * Hasta que termina el rellenado previo la pista no consume nada, así que la
     * ventaja medida sería la de un arranque y no la de una deriva. Mientras tanto
     * se produce al ritmo nativo.
     */
    private fun correctionForDrift(): Double {
        if (!primer.playbackStarted) return 1.0
        val played = try {
            // El contador de la plataforma es un entero de 32 bits sin signo
            // devuelto en un `Int`, así que pasa por los negativos tras una docena
            // de horas de lectura continua. Sin convertirlo, la ventaja saltaría de golpe.
            track.playbackHeadPosition.toLong() and MASK_32
        } catch (e: Exception) {
            stats.onFailure(e)
            return governor.rateScale
        }
        val queued = (framesWritten - played) and MASK_32
        return governor.onQueuedFrames(queued.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }

    /**
     * Tras un vaciado la pista y su contador de tramos vuelven a cero. El nuestro
     * también, y la corrección con él, o la primera medida posterior compararía dos
     * orígenes distintos.
     */
    private fun forgetQueuedFrames() {
        framesWritten = 0L
        governor.reset()
    }

    private fun currentUnderrunCount(): Int = try {
        track.underrunCount.coerceAtLeast(0)
    } catch (e: Exception) {
        stats.onFailure(e)
        0
    }

    /**
     * Los puntos de entrada que siguen se llaman desde la interfaz y desde la parada
     * de sesión, fuera del `try` del bucle de emulación. Una excepción aquí llegaría a
     * un llamador que no puede hacer nada con ella e impediría que la parada
     * concluya, así que la retienen pero la cuentan.
     */
    fun setVolume(volume: Float) {
        try {
            track.setVolume(volume.coerceIn(0f, 1f))
        } catch (e: Exception) {
            stats.onFailure(e)
        }
    }

    fun pause() {
        try {
            track.pause()
            track.flush()
            primer.reset()
            forgetQueuedFrames()
            resampler.reset()
        } catch (e: Exception) {
            stats.onFailure(e)
        }
    }

    /**
     * Desbloquea una escritura en curso y rechaza las siguientes.
     *
     * `AudioTrack.write` en modo bloqueante no se puede interrumpir: sólo devuelve
     * el control al vaciarse la cola. `pause` seguido de `flush` la vacía de golpe y
     * [stopped] hace fallar lo que venga detrás, que es lo que permite que una parada
     * de sesión concluya en su plazo en vez de esperar al final del búfer.
     */
    fun unblock() {
        stopped = true
        try {
            track.pause()
            track.flush()
        } catch (e: Exception) {
            stats.onFailure(e)
        }
    }

    fun release() {
        try {
            track.release()
        } catch (e: Exception) {
            stats.onFailure(e)
        }
    }

    private companion object {
        const val CHANNEL_COUNT = 2
        const val BYTES_PER_SAMPLE = 2
        const val BUFFER_VIDEO_FRAMES = 8
        const val PRIME_VIDEO_FRAMES = 6

        /** Espacio del contador de tramos reproducidos de la plataforma. */
        const val MASK_32 = 0xFFFF_FFFFL

        /** Ritmo de salida nativo del dispositivo, con repliegue seguro a 48 kHz. */
        fun resolveNativeRate(context: Context): Int {
            return try {
                val manager =
                    context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val reported = manager
                    .getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
                    ?.toIntOrNull()
                if (reported != null && reported in 8000..192000) reported else 48000
            } catch (_: Exception) {
                48000
            }
        }
    }
}
