package com.example.core

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.core.emulation.EmulatorButton
import com.example.core.emulation.audio.AndroidAudioSink
import com.example.core.emulation.gba.GbaSaveType
import com.example.core.emulation.gba.RavenGbaCore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Fachada de emulación de AS GBA Emulator.
 *
 * La emulación entera (CPU ARM7TDMI, PPU, APU, temporizadores, DMA, cartucho, BIOS y
 * memoria de guardado) la hace el núcleo C++ de RavenEmu por JNI. Esta clase sólo
 * posee el hilo que cadencia el motor, el framebuffer compartido y la salida de
 * audio.
 *
 * A 1x la escritura de audio bloqueante cala la sesión sobre el reloj del sistema,
 * como en cualquier emulador con sonido. Al acelerar, el audio se descarta y la
 * cadencia pasa a ser la de la consola dividida por la velocidad: el avance rápido
 * salta tramas de audio, nunca modifica el tiempo emulado.
 */
class GBACore(private val context: Context) {

    companion object {
        private const val TAG = "GBACore"

        const val SCREEN_WIDTH = 240
        const val SCREEN_HEIGHT = 160
        const val PIXELS_TOTAL = SCREEN_WIDTH * SCREEN_HEIGHT
        const val TARGET_FPS = 60

        const val KEY_A = 1 shl 0
        const val KEY_B = 1 shl 1
        const val KEY_SELECT = 1 shl 2
        const val KEY_START = 1 shl 3
        const val KEY_RIGHT = 1 shl 4
        const val KEY_LEFT = 1 shl 5
        const val KEY_UP = 1 shl 6
        const val KEY_DOWN = 1 shl 7
        const val KEY_R = 1 shl 8
        const val KEY_L = 1 shl 9

        /** Frecuencia nativa de refresco de la Game Boy Advance. */
        private val GBA_REFRESH_RATE_HZ = RavenGbaCore.REFRESH_RATE_HZ

        /** Frecuencia de muestreo nativa del APU de la Game Boy Advance. */
        private const val GBA_SOURCE_RATE_HZ = 32_768

        /**
         * Tramas de audio por canal que el APU produce por fotograma de vídeo.
         *
         * El sumidero multiplica este valor por los canales al dimensionar la pista
         * y su rellenado previo, así que aquí va sin el factor dos: 32768 / 59,7275
         * ≈ 549. El puente nativo sí devuelve muestras `int16` intercaladas, unas
         * 1097 por fotograma.
         */
        private val GBA_FRAMES_PER_VIDEO_FRAME =
            (GBA_SOURCE_RATE_HZ / GBA_REFRESH_RATE_HZ).toInt()

        /** Intervalo entre rellenados de muestras de audio. */
        private const val AUDIO_BUFFER_SAMPLES = 8192

        private const val BATTERY_SAVE_INTERVAL_NANOS = 5_000_000_000L
        private const val MAX_LAG_NANOS = 100_000_000L

        /** Correspondencia entre máscara de teclas de AS y botones del núcleo. */
        private val BUTTON_MASK = listOf(
            KEY_UP to EmulatorButton.UP,
            KEY_DOWN to EmulatorButton.DOWN,
            KEY_LEFT to EmulatorButton.LEFT,
            KEY_RIGHT to EmulatorButton.RIGHT,
            KEY_A to EmulatorButton.A,
            KEY_B to EmulatorButton.B,
            KEY_START to EmulatorButton.START,
            KEY_SELECT to EmulatorButton.SELECT,
            KEY_L to EmulatorButton.L,
            KEY_R to EmulatorButton.R,
        )
    }

    private val core = RavenGbaCore()

    private val framebuffer = IntArray(PIXELS_TOTAL)
    private val audioBuffer = ShortArray(AUDIO_BUFFER_SAMPLES)
    private val mainHandler = Handler(Looper.getMainLooper())

    val frameBitmap: Bitmap = Bitmap.createBitmap(SCREEN_WIDTH, SCREEN_HEIGHT, Bitmap.Config.ARGB_8888)

    private var audioSink: AndroidAudioSink? = null

    private var emulationThread: Thread? = null
    private val isRunning = AtomicBoolean(false)

    @Volatile
    private var isPaused = false

    @Volatile
    private var currentKeyMask: Int = 0

    private var appliedKeyMask: Int = -1
    var currentHeader: GBAHeader? = null
        private set

    var fastForwardSpeed: Float = 1.0f
        set(value) {
            field = value.coerceIn(1.0f, 8.0f)
            applyVolume()
        }

    /** Audio activo (ajuste del usuario, modificable en caliente). */
    @Volatile
    var audioEnabled: Boolean = true

    /**
     * Volumen de salida (0..1), modificable en caliente.
     *
     * El sumidero tiene volumen fijo y reabrirlo en cada cambio de ajuste exige
     * parar y reiniciar la pista, con el silencio que implica. El factor se le
     * aplica en la mezcla.
     */
    @Volatile
    var audioVolume: Float = 1.0f
        set(value) {
            field = value.coerceIn(0.0f, 1.0f)
            applyVolume()
        }

    /**
     * Serializa el acceso al núcleo entre el hilo de emulación y el de interfaz.
     *
     * Guardar o cargar el estado, y volcar la RAM de cartucho, se pulsan desde la
     * interfaz con el hilo de emulación dentro de `runFrame`. Sin esta exclusión
     * ambos tocarían la misma máquina a la vez.
     */
    private val coreLock = ReentrantLock()

    /**
     * Persistencia de la RAM de cartucho. Sólo debe devolver `true` si la escritura
     * cuajó; si no, el motor sigue marcado como modificado y se reintenta. Sin
     * asignar, la RAM de cartucho no se persiste.
     */
    @Volatile
    var onBatterySave: ((ByteArray) -> Boolean)? = null

    private val _currentFps = MutableStateFlow(60)
    val currentFps = _currentFps.asStateFlow()

    private val _isEmulating = MutableStateFlow(false)
    val isEmulating = _isEmulating.asStateFlow()

    /** Crea la salida de audio si aún no existe (llamado antes de emular). */
    private fun ensureAudioSink() {
        if (audioSink == null) {
            audioSink = try {
                AndroidAudioSink(context, GBA_SOURCE_RATE_HZ, GBA_FRAMES_PER_VIDEO_FRAME)
            } catch (e: Exception) {
                Log.w(TAG, "Sin salida de audio: ${e.message}")
                null
            }
        }
        applyVolume()
    }

    /**
     * Lleva el ajuste de sonido a la pista.
     *
     * Sin sonido, o acelerado, el factor es cero en vez de dejar el anterior: así el
     * silencio se aplica al momento y no queda audio en la cola sonando un rato
     * después de apagarlo.
     */
    private fun applyVolume() {
        val factor = if (audioEnabled && fastForwardSpeed <= 1.0f) audioVolume else 0.0f
        try {
            audioSink?.setVolume(factor)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo ajustar el volumen: ${e.message}")
        }
    }

    fun loadRomDirect(buffer: ByteBuffer, size: Long, header: GBAHeader?): Boolean {
        val safeSize = buffer.remaining()
        val data = ByteArray(safeSize)
        val duplicate = buffer.duplicate()
        duplicate.rewind()
        duplicate.get(data, 0, safeSize)
        return loadRomBytes(data, header)
    }

    /**
     * Cartuchos que se saben Flash por catálogo aunque su ROM no traiga marcador.
     * Sólo se consulta cuando la detección nativa no encuentra nada.
     */
    private fun isLargeOrPokemon(data: ByteArray): Boolean =
        data.size >= 16 * 1024 * 1024 ||
                currentHeader?.gameCode in listOf("BPEE", "AXVE", "AXPE", "BPRE", "BPGE")

    fun loadRomBytes(data: ByteArray, header: GBAHeader?, batteryRam: ByteArray? = null): Boolean {
        stop()
        currentHeader = header ?: GBAHeader.parse(data)

        // El byte fijo 0xB2 del encabezado va a 0x96 por compatibilidad con hackroms y traducciones
        if (data.size >= 0xC0 && (data[0xB2].toInt() and 0xFF) != 0x96) {
            data[0xB2] = 0x96.toByte()
        }

        try {
            // La detección nativa manda. El tamaño de la ROM no dice nada del tipo
            // de memoria: Mega Man Zero 4 (EEPROM_V124) y DOOM II (EEPROM_V120)
            // miden 16 MiB exactos y son EEPROM, así que forzar Flash por tamaño
            // los volvía cartuchos Flash y el juego rechazaba su partida por corrupta.
            core.forcedSaveType = null

            // La RAM de cartucho guardada se inyecta con la propia carga: es lo
            // que el juego relee por su cuenta al arrancar, así que si se
            // entregara después el juego ya habría pasado por su comprobación de
            // "partida nueva".
            core.loadRom(data, batteryRam)

            // La heurística queda como red de seguridad para los hackroms sin
            // marcador del SDK. Se decide tras la carga porque el tipo forzado es
            // pegajoso y contaminaría la ROM siguiente.
            if (core.saveType == GbaSaveType.NONE && isLargeOrPokemon(data)) {
                core.forcedSaveType = GbaSaveType.FLASH_128K
                core.loadRom(data, batteryRam)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cargando ROM en núcleo nativo: ${e.message}", e)
            return false
        }

        currentKeyMask = 0
        appliedKeyMask = -1
        try {
            core.runFrame(framebuffer, true)
            publishFramebuffer()
        } catch (e: Exception) {
            Log.e(TAG, "Error en frame inicial: ${e.message}", e)
        }

        Log.i(
            TAG,
            "ROM cargada: ${currentHeader?.displayTitle} (${currentHeader?.formattedSize}), " +
                "guardado=${core.saveType.displayName}"
        )
        return true
    }

    fun start(onFrameRendered: () -> Unit) {
        if (isRunning.getAndSet(true)) return
        isPaused = false
        // La cadencia es de sesión, no de núcleo: si el juego anterior acabó
        // acelerado, el siguiente arrancaría acelerado sin que se le pidiera.
        fastForwardSpeed = 1.0f
        _isEmulating.value = true
        ensureAudioSink()
        val thread = Thread({
            emulationLoop(onFrameRendered)
        }, "ASGBA-Emulation")
        thread.priority = Thread.MAX_PRIORITY - 1
        emulationThread = thread
        thread.start()
    }

    private fun emulationLoop(onFrameRendered: () -> Unit) {
        val basePeriodNanos = (1_000_000_000.0 / GBA_REFRESH_RATE_HZ).toLong()
        var nextFrameAt = System.nanoTime()
        var fpsWindowStart = System.nanoTime()
        var fpsFrames = 0
        var lastBatteryCheck = System.nanoTime()

        while (isRunning.get()) {
            if (isPaused) {
                LockSupport.parkNanos(20_000_000)
                nextFrameAt = System.nanoTime()
                fpsWindowStart = System.nanoTime()
                fpsFrames = 0
                continue
            }

            try {
                applyInput()

                val audioCount: Int
                coreLock.withLock {
                    core.runFrame(framebuffer, true)
                    audioCount = core.readAudio(audioBuffer)
                }
                publishFramebuffer()
                mainHandler.post(onFrameRendered)

                val speed = fastForwardSpeed
                val audioPaced = speed <= 1.0f && audioEnabled && audioCount > 0

                // El silencio lo aplica [applyVolume] al cambiar los ajustes; aquí
                // sólo se decide si la cadencia la marca el audio.
                if (audioPaced) {
                    try {
                        audioSink?.write(audioBuffer, audioCount)
                    } catch (e: Exception) {
                        Log.w(TAG, "Fallo de audio: ${e.message}")
                    }
                }

                fpsFrames++
                val now = System.nanoTime()
                if (now - fpsWindowStart >= 1_000_000_000L) {
                    _currentFps.value = fpsFrames
                    fpsFrames = 0
                    fpsWindowStart = now
                }

                if (now - lastBatteryCheck >= BATTERY_SAVE_INTERVAL_NANOS) {
                    lastBatteryCheck = now
                    saveBatteryIfDirty()
                }

                if (audioPaced) {
                    nextFrameAt = System.nanoTime()
                } else {
                    nextFrameAt += (basePeriodNanos / speed).toLong()
                    val wait = nextFrameAt - System.nanoTime()
                    if (wait > 0) {
                        LockSupport.parkNanos(wait)
                    } else if (wait < -MAX_LAG_NANOS) {
                        nextFrameAt = System.nanoTime()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error en bucle de emulación: ${e.message}", e)
                _currentFps.value = 0
                LockSupport.parkNanos(50_000_000)
            }
        }

        saveBatteryNow()
    }

    private fun applyInput() {
        val mask = currentKeyMask
        if (mask == appliedKeyMask) return
        for ((keyMask, button) in BUTTON_MASK) {
            core.setButton(button, mask and keyMask != 0)
        }
        appliedKeyMask = mask
    }

    private fun publishFramebuffer() {
        synchronized(frameBitmap) {
            for (i in framebuffer.indices) {
                framebuffer[i] = framebuffer[i] or 0xFF000000.toInt()
            }
            frameBitmap.setPixels(framebuffer, 0, SCREEN_WIDTH, 0, 0, SCREEN_WIDTH, SCREEN_HEIGHT)
        }
    }

    fun setKey(key: Int, pressed: Boolean) {
        currentKeyMask = if (pressed) {
            currentKeyMask or key
        } else {
            currentKeyMask and key.inv()
        }
    }

    fun isKeyPressed(key: Int): Boolean = (currentKeyMask and key) != 0

    fun pause() {
        isPaused = true
        audioSink?.pause()
    }

    fun resume() {
        isPaused = false
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) {
            audioSink?.pause()
            // Aun sin hilo vivo puede haber una RAM de cartucho modificada: es la
            // partida del juego, y parar no es motivo para perderla.
            flushBattery()
            return
        }
        _isEmulating.value = false
        audioSink?.unblock()
        val thread = emulationThread
        thread?.let {
            LockSupport.unpark(it)
            it.join(2_000L)
        }
        emulationThread = null
        // La pista queda desbloqueada y no reutilizable: se libera para que el
        // próximo arranque abra una limpia.
        audioSink?.release()
        audioSink = null
        _currentFps.value = 0
    }

    fun reset() {
        coreLock.withLock {
            core.reset()
            currentKeyMask = 0
            appliedKeyMask = -1
        }
    }

    /**
     * Estado completo de la sesión, listo para disco.
     *
     * Se serializa contra el bucle de emulación: sin la exclusión mutua, guardar
     * mientras corre `runFrame` leería la máquina a medio fotograma y el estado
     * resultante no cargaría.
     */
    fun saveState(): ByteArray? = coreLock.withLock {
        try {
            core.saveState()
        } catch (e: Exception) {
            Log.e(TAG, "Error al guardar estado: ${e.message}")
            null
        }
    }

    fun loadState(data: ByteArray): Boolean = coreLock.withLock {
        try {
            core.loadState(data)
            Log.i(TAG, "Estado cargado (${data.size} bytes)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error al cargar estado: ${e.message}")
            false
        }
    }

    fun release() {
        stop()
        core.close()
    }

    private fun saveBatteryIfDirty() {
        val callback = onBatterySave ?: return
        coreLock.withLock {
            if (!core.hasBatteryRam || !core.batteryRamDirty) return
            val snapshot = core.snapshotBatteryRam() ?: return
            if (callback(snapshot.first)) {
                core.acknowledgeBatteryRamSaved(snapshot.second)
            }
        }
    }

    /** Fuerza una salvaguarda de la RAM de cartucho (pausa, segundo plano…). */
    fun flushBattery() {
        saveBatteryNow()
    }

    private fun saveBatteryNow() {
        val callback = onBatterySave ?: return
        coreLock.withLock {
            if (!core.hasBatteryRam || !core.batteryRamDirty) return
            val snapshot = core.snapshotBatteryRam() ?: return
            if (callback(snapshot.first)) {
                core.acknowledgeBatteryRamSaved(snapshot.second)
            }
        }
    }
}
