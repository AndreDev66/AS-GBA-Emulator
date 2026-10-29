package com.example.core

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import com.example.core.emulation.ConsoleKind
import com.example.core.emulation.EmulatorBackend
import com.example.core.emulation.EmulatorButton
import com.example.core.emulation.FastForwardAudio
import com.example.core.emulation.FramebufferFormat
import com.example.core.emulation.MelonDsNativeCore
import com.example.core.emulation.RavenNativeCore
import com.example.core.emulation.audio.AndroidAudioSink
import com.example.core.cheats.CheatOp
import com.example.core.emulation.gba.GbaSaveType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Fachada de emulación de AS GBA Emulator.
 *
 * La emulación entera (CPU, PPU, APU, temporizadores, DMA, cartucho, BIOS y
 * memoria de guardado —ARM7TDMI en la GBA, Sharp SM83 en Game Boy—) la hace el
 * núcleo C++ de RavenEmu por JNI. Esta clase sólo posee el hilo que cadencia el
 * motor, el framebuffer compartido y la salida de audio.
 *
 * La consola la decide [loadRomBytes] al cargar: detecta si la ROM es de Game
 * Boy (`.gb`, `.gbc`) o de GBA, cambia el motor nativo si hace falta y
 * redimensiona framebuffer y bitmap (160×144 o 240×160). Cadencia y audio no
 * cambian: las dos consolas corren a 59,7275 Hz y muestrean a 32 768 Hz.
 *
 * A 1x la escritura de audio bloqueante cala la sesión sobre el reloj del sistema,
 * como en cualquier emulador con sonido. Al acelerar hay dos caminos, y los elige
 * el usuario: o el audio se descarta y la cadencia pasa a ser la de la consola
 * dividida por la velocidad, o el audio se comprime para que acompañe a la partida
 * y vuelve a marcar la cadencia. En ninguno de los dos se toca el tiempo emulado:
 * el avance rápido salta tramas de audio, nunca de emulación.
 */
class GBACore(private val context: Context) {

    companion object {
        private const val TAG = "GBACore"

        /** Sin enlace, o ya cerrado: es lo que devuelve el transporte. */
        private const val STATUS_CLOSED = 2

        /** La consola emulada no tiene enlace por cable (todo menos la GBA). */
        private const val NO_LINK = -1L

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

        /**
         * Botones que sólo la Nintendo DS tiene en el cartucho. Ni la Game Boy
         * ni la GBA los tienen, y RavenEmu los descarta si le llegan, de modo
         * que sólo [MelonDsNativeCore] los traduce a algo con efecto.
         */
        const val KEY_X = 1 shl 10
        const val KEY_Y = 1 shl 11

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
            // X e Y sólo existen en Nintendo DS. Se envían siempre y RavenEmu los
            // descarta, que es el mismo trato que reciben los trucos en Game Boy:
            // inofensivo, y evita tener dos tablas de botones según la consola.
            KEY_X to EmulatorButton.X,
            KEY_Y to EmulatorButton.Y,
        )

        /**
         * Perfil de la pantalla clásica de la Game Boy DMG-01 (LCD verde
         * oliva): cuatro sombras del más claro (nivel 0) al más oscuro (nivel
         * 3), en el mismo orden en que el núcleo las entrega en modo DMG. Son
         * colores de simulación de un LCD, no una paleta oficial de Nintendo.
         */
        private val DMG_PALETTE = intArrayOf(
            0xFFB5C18C.toInt(),
            0xFF8A9A5B.toInt(),
            0xFF4E5F30.toInt(),
            0xFF1B2410.toInt(),
        )
    }

    /**
     * Motor nativo de la consola emulada. Se recrea en [switchConsole] cuando
     * la ROM que llega es de otra consola; entretanto, GBA.
     *
     * El tipo es [EmulatorBackend] y no un núcleo concreto porque hay dos que no
     * se parecen: RavenEmu para Game Boy y GBA, melonDS para Nintendo DS. Lo
     * que sólo sabe hacer uno de los dos —el reloj de la GBA, los trucos, el
     * enlace por cable, el puntero de la DS— no se pregunta a través de aquí,
     * sino al motor concreto, con [raven] a la vista.
     */
    private var core: EmulatorBackend = RavenNativeCore()

    /**
     * El motor actual **si** es RavenEmu, o `null` si es melonDS.
     *
     * Existe para lo que sólo la GBA tiene y la Nintendo DS no: reloj de
     * cartucho, tipos de guardado, trucos de memoria y enlace por cable. Quien
     * lo usa comprueba antes que no sea `null`, y la interfaz lo publica como
     * [ConsoleKind.supportsLinkCable] o [ConsoleKind.supportsMemoryCheats].
     */
    private val raven: RavenNativeCore?
        get() = core as? RavenNativeCore

    /** Consola emulada; la fija [loadRomBytes] y no cambia sin pasar por ahí. */
    var console: ConsoleKind = ConsoleKind.GAME_BOY_ADVANCE
        private set

    /** Motivo de la última carga fallida, para que la interfaz no muestre un error genérico. */
    @Volatile
    var lastLoadError: String? = null
        private set

    /**
     * Cabecera de la ROM cargada cuando la consola es Nintendo DS.
     *
     * La lee melonDS al reconocer el cartucho —con sus comprobaciones de CRC— y
     * de ahí sale el título y el código que se enseñan en la biblioteca y en el
     * HUD. Para Game Boy y GBA la/leen [currentHeader] y [gameBoyHeader], que
     * existen desde antes de que esta consola estuviera en el proyecto.
     */
    var ndsHeader: MelonDsRomInfo? = null
        private set

    private var framebuffer = IntArray(console.screenPixelCount)
    private val audioBuffer = ShortArray(AUDIO_BUFFER_SAMPLES)
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Fotograma que la interfaz lee: 240×160 en GBA, 160×144 en Game Boy. Se
     * recrea al cambiar de consola, siempre con el hilo de emulación parado.
     */
    var frameBitmap: Bitmap =
        Bitmap.createBitmap(console.screenWidth, console.screenHeight, Bitmap.Config.ARGB_8888)
        private set

    private var audioSink: AndroidAudioSink? = null

    private var emulationThread: Thread? = null
    private val isRunning = AtomicBoolean(false)

    @Volatile
    private var isPaused = false

    @Volatile
    private var currentKeyMask: Int = 0

    private var appliedKeyMask: Int = -1

    /** Cabecera de la ROM cargada cuando la consola es GBA. */
    var currentHeader: GBAHeader? = null
        private set

    /** Cabecera de la ROM cargada cuando la consola es Game Boy / Color. */
    var gameBoyHeader: GameBoyHeader? = null
        private set

    /**
     * El núcleo entrega niveles `0..3` (pantalla DMG) en vez de color: en ese
     * caso [publishFramebuffer] los colorea con [DMG_PALETTE]. Se decide al
     * cargar la ROM, cuando el núcleo ya sabe en qué modo de hardware corre.
     */
    private var monochromeLevels = false

    /**
     * El núcleo ya entrega los píxeles opacos (alfa a 255).
     *
     * melonDS deja el byte de alfa a cero en sus búferes, así que el adaptador
     * nativo lo rellena al convertir. Cuando eso ocurre, recorrer los ~197 000
     * píxeles del fotograma en Kotlin para volver a poner el mismo byte es
     * trabajo repetido en el camino más caliente de la sesión.
     */
    private var opaqueFrames = false

    var fastForwardSpeed: Float = 1.0f
        set(value) {
            field = value.coerceIn(1.0f, 8.0f)
            applyVolume()
        }

    /** Audio activo (ajuste del usuario, modificable en caliente). */
    @Volatile
    var audioEnabled: Boolean = true

    /**
     * Qué se hace con el sonido al acelerar (ajuste del usuario, modificable en
     * caliente).
     *
     * A 1x no interviene: el audio manda y nada se descarta. Sólo decide si en
     * avance rápido se oyen los bloques comprimidos o no se oye nada.
     */
    @Volatile
    var fastForwardAudio: FastForwardAudio = FastForwardAudio.SILENCED
        set(value) {
            field = value
            applyVolume()
        }

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

    /**
     * Crea la salida de audio si aún no existe (llamado antes de emular).
     *
     * La frecuencia de muestreo y el relleno previo los pide el núcleo, no la
     * aplicación: RavenEmu entrega a 32 768 Hz y melonDS a 48 kHz, y abrir la
     * pista con la frecuencia equivocada hace que el sonido corra más deprisa o
     * más lento que la imagen.
     */
    private fun ensureAudioSink() {
        if (audioSink == null) {
            val sourceRate = core.sourceRateHz
            val framesPerVideoFrame = (sourceRate / core.refreshRateHz).toInt()
            audioSink = try {
                AndroidAudioSink(context, sourceRate, framesPerVideoFrame)
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
     * Sin sonido —apagado, o acelerado con el avance mudo— el factor es cero en vez
     * de dejar el anterior: así el silencio se aplica al momento y no queda audio en
     * la cola sonando un rato después de apagarlo.
     */
    private fun applyVolume() {
        val audible = audioEnabled &&
            (fastForwardSpeed <= 1.0f || fastForwardAudio == FastForwardAudio.ACCELERATED)
        val factor = if (audible) audioVolume else 0.0f
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
     * Carga desde archivo para consolas cuyas ROMs no caben de forma segura en
     * el heap de Java (Nintendo DS). Game Boy y GBA mantienen su ruta habitual.
     */
    fun loadRomFile(file: File, batteryRam: ByteArray?, console: ConsoleKind): Boolean {
        lastLoadError = null
        if (console != ConsoleKind.NINTENDO_DS) {
            if (file.length() > 64L * 1024 * 1024) {
                val error = "La ROM es demasiado grande para cargarse en memoria."
                lastLoadError = error
                Log.e(TAG, error)
                return false
            }
            return try {
                loadRomBytes(file.readBytes(), null, batteryRam, console)
            } catch (e: Exception) {
                lastLoadError = "No se pudo leer la ROM: ${e.message}"
                Log.e(TAG, "No se pudo leer la ROM: ${e.message}", e)
                false
            }
        }

        val bootError = try {
            val header = ByteArray(0x40)
            DataInputStream(file.inputStream()).use { it.readFully(header) }
            NdsHeader.bootValidationError(header, file.length())
        } catch (e: Exception) {
            "No se pudo leer la cabecera de la ROM: ${e.message}"
        }
        if (bootError != null) {
            lastLoadError = bootError
            Log.e(TAG, "Se rechaza ROM de Nintendo DS: $bootError")
            return false
        }

        stop()
        switchConsole(console)
        currentHeader = null
        gameBoyHeader = null
        return try {
            val melonDs = core as? MelonDsNativeCore
                ?: throw IllegalStateException("El núcleo activo no es melonDS")
            melonDs.loadRom(file, batteryRam)
            ndsHeader = melonDs.romInfo
            finishRomLoad()
        } catch (e: Exception) {
            lastLoadError = "Error cargando ROM de Nintendo DS: ${e.message}"
            Log.e(TAG, "Error cargando ROM de Nintendo DS desde archivo: ${e.message}", e)
            false
        }
    }

    /**
     * Cartuchos que se saben Flash por catálogo aunque su ROM no traiga marcador.
     * Sólo se consulta cuando la detección nativa no encuentra nada.
     */
    private fun isLargeOrPokemon(data: ByteArray): Boolean =
        data.size >= 16 * 1024 * 1024 ||
                currentHeader?.gameCode in listOf("BPEE", "AXVE", "AXPE", "BPRE", "BPGE")

    /**
     * Carga una ROM y decide qué consola la emula.
     *
     * @param console la consola detectada por quien llama (nombre del archivo
     *   más bytes). `null` deja que se decida aquí mirando sólo los bytes, que
     *   es lo que hace [loadRomDirect] y lo que cubre archivos sin extensión.
     */
    fun loadRomBytes(
        data: ByteArray,
        header: GBAHeader?,
        batteryRam: ByteArray? = null,
        console: ConsoleKind? = null,
    ): Boolean {
        stop()
        // Antes que nada: el motor, el framebuffer y el bitmap dependen de la
        // consola, y cambiarlos exige el hilo de emulación ya parado ([stop]).
        val initialConsole = console ?: ConsoleKind.detect("", data)
        switchConsole(initialConsole)

        if (!loadCartridge(data, header, batteryRam)) {
            // Un cartucho que no dice de quién es —homebrew sin la suma de
            // cabecera correcta, extensión ambigua— se queda sin consola. Antes
            // de rendirse se prueban las demás: cada núcleo rechaza lo que no
            // es suyo, así que un intento más sólo puede mejorar el resultado, y
            // el motivo del primer fallo queda en el registro.
            //
            // Se guarda la consola de partida porque [switchConsole] va cambiando
            // `console` mientras recorre la lista, y el candidato a excluir es
            // el primero que se probó, no el actual.
            val rejected = initialConsole
            var loaded = false
            for (candidate in ConsoleKind.entries) {
                if (candidate == rejected) continue
                Log.w(TAG, "El núcleo ${rejected.shortName} la rechazó; " +
                    "se reintenta como ${candidate.shortName}")
                switchConsole(candidate)
                if (loadCartridge(data, header, batteryRam)) {
                    loaded = true
                    break
                }
            }
            if (!loaded) return false
        }

        return finishRomLoad()
    }

    private fun finishRomLoad(): Boolean {
        monochromeLevels = core.framebufferFormat == FramebufferFormat.INDEXED_4
        // melonDS rellena el alfa en el adaptador nativo; RavenEmu no.
        opaqueFrames = console == ConsoleKind.NINTENDO_DS
        currentKeyMask = 0
        appliedKeyMask = -1
        try {
            core.runFrame(framebuffer, true)
            publishFramebuffer()
        } catch (e: Exception) {
            Log.e(TAG, "Error en frame inicial: ${e.message}", e)
        }
        Log.i(TAG, "ROM cargada (${this.console.shortName}): ${describeLoadedRom()}")
        return true
    }

    /**
     * Prepara la cabecera del cartucho y lo carga en el motor de la consola
     * activa. Devuelve `false` si el núcleo no lo acepta.
     */
    private fun loadCartridge(data: ByteArray, header: GBAHeader?, batteryRam: ByteArray?): Boolean {
        // El byte 0xB2 se tocan en GBA y se restauran en Game Boy: allí cae
        // dentro del área de vectores del cartucho, y dejar el parche puesto
        // corrompería una ROM que sí es de Game Boy (el reintento de consola
        // entra por esta puerta con la misma cadena de bytes).
        val originalB2 = if (data.size >= 0xC0) data[0xB2] else null

        if (console == ConsoleKind.GAME_BOY_ADVANCE) {
            currentHeader = header ?: GBAHeader.parse(data)
            gameBoyHeader = null
            ndsHeader = null

            // El byte fijo 0xB2 del encabezado va a 0x96 por compatibilidad con
            // hackroms y traducciones.
            if (originalB2 != null && (originalB2.toInt() and 0xFF) != 0x96) {
                data[0xB2] = 0x96.toByte()
            }
        } else if (console == ConsoleKind.GAME_BOY) {
            currentHeader = null
            gameBoyHeader = GameBoyHeader.parse(data)
            ndsHeader = null
            if (originalB2 != null && (originalB2.toInt() and 0xFF) != 0x96) {
                data[0xB2] = originalB2
            }
        } else {
            // Nintendo DS. Ni el parche de 0xB2 ni la cabecera de GBA aplican
            // aquí: el byte 0xB2 cae dentro de la cabecera de melonDS, que tiene
            // su propia suma de comprobación, y tocarlo haría que el cartucho
            // no encajara. melonDS recibe la ROM tal cual.
            currentHeader = null
            gameBoyHeader = null
            if (originalB2 != null && (originalB2.toInt() and 0xFF) != 0x96) {
                data[0xB2] = originalB2
            }
            return try {
                val melonDs = core as? MelonDsNativeCore ?: return false
                melonDs.loadRom(data, batteryRam)
                ndsHeader = melonDs.romInfo
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error cargando ROM de Nintendo DS: ${e.message}", e)
                false
            }
        }

        return try {
            val raven = core as? RavenNativeCore ?: return false
            // La detección nativa manda. El tamaño de la ROM no dice nada del tipo
            // de memoria: Mega Man Zero 4 (EEPROM_V124) y DOOM II (EEPROM_V120)
            // miden 16 MiB exactos y son EEPROM, así que forzar Flash por tamaño
            // los volvía cartuchos Flash y el juego rechazaba su partida por corrupta.
            raven.forcedSaveType = null

            // La RAM de cartucho guardada se inyecta con la propia carga: es lo
            // que el juego relee por su cuenta al arrancar, así que si se
            // entregara después el juego ya habría pasado por su comprobación de
            // "partida nueva".
            raven.loadRom(data, batteryRam)

            // La heurística queda como red de seguridad para los hackroms sin
            // marcador del SDK. Se decide tras la carga porque el tipo forzado es
            // pegajoso y contaminaría la ROM siguiente. Sólo aplica a GBA: en
            // Game Boy el tipo de cartucho lo declara la propia ROM (0x147).
            if (console == ConsoleKind.GAME_BOY_ADVANCE &&
                raven.saveType == GbaSaveType.NONE && isLargeOrPokemon(data)
            ) {
                raven.forcedSaveType = GbaSaveType.FLASH_128K
                raven.loadRom(data, batteryRam)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error cargando ROM en núcleo nativo: ${e.message}", e)
            false
        }
    }

    /** Título y detalle de la ROM recién cargada, para el registro del sistema. */
    private fun describeLoadedRom(): String = when (console) {
        ConsoleKind.GAME_BOY -> gameBoyHeader
            ?.let { "${it.displayTitle} (${it.formattedSize})" }
            ?: "ROM de Game Boy"
        ConsoleKind.GAME_BOY_ADVANCE ->
            "${currentHeader?.displayTitle} (${currentHeader?.formattedSize}), " +
                "guardado=${raven?.saveType?.displayName}"
        ConsoleKind.NINTENDO_DS ->
            "${ndsHeader?.title} (${ndsHeader?.gameCode}), " +
                "modelo=${ndsHeader?.unit}, región=${ndsHeader?.region}"
    }

    /**
     * Cambia de consola cuando la ROM que llega es de otra: cierra el motor
     * anterior, abre el que toca y redimensiona framebuffer y bitmap.
     *
     * Sólo se llama con el hilo de emulación parado ([stop] va primero en
     * [loadRomBytes]); sin ese orden, el hilo anterior seguiría escribiendo en
     * un bitmap que ya no existe.
     *
     * El transporte del enlace LAN no se cierra: el destructor de la máquina
     * anterior se desengancha solo y el enchufe sigue vivo. Aquí sólo hay que
     * volver a cablearlo al motor nuevo, que es lo mismo que el nativo hace por
     * su cuenta cuando se carga una ROM.
     */
    private fun switchConsole(target: ConsoleKind) {
        if (target == console) return
        coreLock.withLock {
            core.close()
            console = target
            core = createBackend(target)
            val linkable = raven
            if (linkEndpoint != 0L && linkable != null && !linkable.linkAttach(linkEndpoint)) {
                Log.w(TAG, "No se pudo reenganchar el enlace al núcleo ${target.shortName}")
            }
        }
        framebuffer = IntArray(target.screenPixelCount)
        frameBitmap = Bitmap.createBitmap(
            target.screenWidth, target.screenHeight, Bitmap.Config.ARGB_8888
        )
        Log.i(TAG, "Cambio de consola a ${target.shortName}")
    }

    /**
     * Abre el motor que corresponde a la consola.
     *
     * Cada núcleo tiene su propia frontera JNI y su propio ciclo de vida, y no
     * hay forma razonable de unificarlos: RavenEmu se construye con el
     * identificador de consola y devuelve el motor ya listo, mientras que
     * melonDS necesita un directorio donde escribir y no acepta cartucho hasta
     * que se le pasa.
     */
    private fun createBackend(target: ConsoleKind): EmulatorBackend =
        if (target == ConsoleKind.NINTENDO_DS) {
            MelonDsNativeCore(context.filesDir.absolutePath)
        } else {
            RavenNativeCore(target)
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
            // Prioridad de dibujado, no la máxima. El hilo tiene que llegar a
            // tiempo a cada fotograma, pero subirlo al máximo lo pone a competir
            // con el audio y con el hilo de interfaz del sistema: el tirón que
            // eso provoca se nota más de lo que ayuda a emular.
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
            } catch (_: Exception) {
                // Sin permiso para cambiar la prioridad se sigue igual: peor
                // sería no emular por no poder subirla.
            }
            emulationLoop(onFrameRendered)
        }, "ASGBA-Emulation")
        emulationThread = thread
        thread.start()
    }

    private fun emulationLoop(onFrameRendered: () -> Unit) {
        val basePeriodNanos = (1_000_000_000.0 / core.refreshRateHz).toLong()
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
                // Una ROM de Nintendo DS puede apagar la consola por su cuenta.
                // Cuando lo hace ya no hay fotograma que sacar, y seguir
                // emulando sería gastar la batería calculando el equivalente a
                // una pantalla en negro. Se consulta bajo el mismo cerrojo que
                // el fotograma porque es estado del núcleo, y si se apagó se
                // sale del bucle: la partida la guarda el `saveBatteryNow()` de
                // después, el mismo camino que al cerrar el juego.
                var poweredOff: Boolean
                coreLock.withLock {
                    core.runFrame(framebuffer, true)
                    audioCount = core.readAudio(audioBuffer)
                    poweredOff = !core.isRunning
                }
                publishFramebuffer()
                mainHandler.post(onFrameRendered)

                if (poweredOff) {
                    Log.i(TAG, "El juego apagó la consola; se termina la emulación")
                    break
                }

                val speed = fastForwardSpeed
                // A 1x manda el audio. Acelerado y con sonido, también: la pista
                // consume el bloque comprimido más deprisa y la escritura bloqueante
                // marca la cadencia nueva. Sólo el avance mudo deja el reloj al vídeo.
                val audioPaced = audioEnabled && audioCount > 0 &&
                    (speed <= 1.0f || fastForwardAudio == FastForwardAudio.ACCELERATED)

                // El silencio lo aplica [applyVolume] al cambiar los ajustes; aquí
                // sólo se decide si la cadencia la marca el audio.
                if (audioPaced) {
                    try {
                        audioSink?.write(audioBuffer, audioCount, speed)
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

    /**
     * Vuelca a la máquina lo que el jugador tenga pulsado.
     *
     * El puntero va aparte de la máscara de botones porque la Nintendo DS no
     * lo cuenta como uno más: es un contacto resistivo sobre la pantalla
     * inferior, con coordenadas, y se envía en cuanto cambia. Los botones, en
     * cambio, sólo se reenvían cuando la máscara cambia: son diez, no cambian
     * cada fotograma, y recorrerlos sin necesidad no aporta nada.
     */
    private fun applyInput() {
        val mask = currentKeyMask
        if (mask != appliedKeyMask) {
            for ((keyMask, button) in BUTTON_MASK) {
                core.setButton(button, mask and keyMask != 0)
            }
            appliedKeyMask = mask
        }
        val stylus = stylusPosition
        if (stylus != appliedStylus) {
            core.setStylus(stylus.first, stylus.second, stylus.third)
            appliedStylus = stylus
        }
    }

    private fun publishFramebuffer() {
        synchronized(frameBitmap) {
            if (monochromeLevels) {
                // El núcleo DMG entrega sombras (0 más claro, 3 más oscuro),
                // no color: aquí las colorea el perfil de la pantalla original.
                for (i in framebuffer.indices) {
                    framebuffer[i] = DMG_PALETTE[framebuffer[i].coerceIn(0, 3)]
                }
            } else if (!opaqueFrames) {
                for (i in framebuffer.indices) {
                    framebuffer[i] = framebuffer[i] or 0xFF000000.toInt()
                }
            }
            frameBitmap.setPixels(
                framebuffer, 0, frameBitmap.width, 0, 0,
                frameBitmap.width, frameBitmap.height
            )
        }
    }

    fun setKey(key: Int, pressed: Boolean) {
        currentKeyMask = if (pressed) {
            currentKeyMask or key
        } else {
            currentKeyMask and key.inv()
        }
    }

    /**
     * Puntero sobre la pantalla táctil, en coordenadas de imagen.
     *
     * La horizontal va de 0 a 255 y la vertical de 0 a 191. Con
     * [ConsoleKind.hasStylus] en falso la llamada no hace nada: ni la Game Boy
     * ni la GBA tienen dónde señalar.
     */
    fun setStylus(x: Int, y: Int, pressed: Boolean) {
        stylusPosition = Triple(x.coerceIn(0, 255), y.coerceIn(0, 191), pressed)
    }

    /** Contacto actual con la pantalla táctil: horizontal, vertical y pulsado. */
    @Volatile
    private var stylusPosition = Triple(0, 0, false)

    /** Lo último que se mandó a la máquina, para no repetirlo cada fotograma. */
    private var appliedStylus = Triple(-1, -1, false)

    /**
     * Fija los trucos de memoria activos; una lista vacía los retira.
     *
     * Se serializa con el bucle de emulación con el mismo candado que
     * [saveState]: la lista se escribe en la memoria del núcleo y un
     * fotograma en curso estaría leyéndola a mitad de escritura.
     */
    fun setCheats(ops: List<CheatOp>) {
        // La Nintendo DS no expone la RAM del juego como una memoria plana que
        // se pueda editar así: el truco de AS está pensado para el bus de la GBA.
        val raven = raven ?: return
        coreLock.withLock { raven.setCheats(ops) }
    }

    // ── Enlace LAN entre dos dispositivos ───────────────────────────────────
    //
    // La red vive en el transporte nativo y el candado sólo hace falta para lo
    // que toca la máquina. `LinkManager` (paquete `core.link`) es quien decide
    // cuándo hay sala y cuándo se cierra.

    /** Enlace abierto por esta sesión; 0 si no hay ninguno. */
    @Volatile
    private var linkEndpoint: Long = 0

    /**
     * Abre el transporte de red del enlace LAN.
     *
     * A diferencia de [linkAttach] y [linkClose] no toma el candado: abrir
     * sockets no comparte estado con la máquina, y la conexión del invitado
     * puede tardar segundos en una red mal configurada —con el candado tomado,
     * el hilo de emulación quedaría parado ese tiempo—.
     *
     * Devuelve un código negativo si no hay núcleo de enlace: la Game Boy
     * Advance es la única de las tres consolas con este puerto.
     */
    fun linkConnect(server: Boolean, host: String?, port: Int): Long =
        (raven?.linkConnect(server, host, port) ?: NO_LINK)
            .also { if (it > 0) linkEndpoint = it }

    /**
     * Engancha el enlace a la máquina que emula. Con el candado tomado: el hilo
     * de emulación puede estar dentro de `runFrame` y tocar su SIO a la vez.
     */
    fun linkAttach(endpoint: Long): Boolean =
        coreLock.withLock { raven?.linkAttach(endpoint) ?: false }

    /** Consulta al transporte: no toca la máquina y no necesita candado. */
    fun linkStatus(endpoint: Long): Int =
        if (endpoint == 0L) STATUS_CLOSED else (raven?.linkStatus(endpoint) ?: STATUS_CLOSED)

    /**
     * Cierra el enlace. Con el candado tomado y antes de liberar el núcleo: el
     * desenganche toca la máquina nativa.
     */
    fun linkClose(endpoint: Long) {
        coreLock.withLock { raven?.linkClose(endpoint) }
        if (linkEndpoint == endpoint) linkEndpoint = 0
    }

    /** Cierra el enlace que abrió esta sesión, si lo hay. Lo llama [release]. */
    private fun closeLink() {
        val endpoint = linkEndpoint
        if (endpoint == 0L) return
        coreLock.withLock { raven?.linkClose(endpoint) }
        linkEndpoint = 0
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
        // El enlace antes que el núcleo: su desenganche toca la máquina nativa,
        // y una vez liberado el núcleo esa máquina ya no existe.
        closeLink()
        core.close()
    }

    private fun saveBatteryIfDirty() {
        val callback = onBatterySave ?: return
        coreLock.withLock {
            if (!core.hasBatteryRam || !core.batteryRamDirty) return
            val snapshot = core.takeBatterySnapshot() ?: return
            if (callback(snapshot)) {
                core.acknowledgeBatterySaved()
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
            val snapshot = core.takeBatterySnapshot() ?: return
            if (callback(snapshot)) {
                core.acknowledgeBatterySaved()
            }
        }
    }
}
