package com.example.core.emulation

import com.example.core.MelonDsCoreBridge
import com.example.core.MelonDsRomInfo
import java.io.File

/**
 * Adaptador en Kotlin sobre el núcleo de Nintendo DS (melonDS).
 *
 * Es el gemelo de [RavenNativeCore] para la otra consola, y tiene la misma
 * forma a propósito: pasivo, mono-hilo en cuanto a quién llama, y de un solo uso
 * por partida. La diferencia de fondo es que melonDS **sí** crea hilos propios
 * —el del render 3D y el del fastmem del JIT—, pero eso no cambia el contrato:
 * AS sigue decidiendo cuándo emular un fotograma.
 *
 * Lo que no hay aquí y sí hay en [RavenNativeCore] es deliberado. La Game Boy
 * Advance tiene reloj de cartucho, tipos de guardado forzables, trucos de
 * memoria y fotografía de depuración; la Nintendo DS no tiene nada de eso, o
 * tiene otra cosa. Rellenar los huecos con valores inventados daría la
 * impresión de que AS sabe algo del hardware que en realidad no sabe.
 */
class MelonDsNativeCore(
    /**
     * Directorio privado de la aplicación.
     *
     * melonDS escribe ahí lo suyo (firmware, configuración), igual que haría en
     * el escritorio. Necesita un sitio real y escribible: por eso se le pasa el
     * `filesDir` de la aplicación en lugar de una cadena vacía.
     */
    private val dataDir: String,
) : EmulatorBackend {

    override val console: ConsoleKind = ConsoleKind.NINTENDO_DS

    override val sourceRateHz: Int = SOURCE_RATE_HZ

    override val refreshRateHz: Double = REFRESH_RATE_HZ

    private var handle: Long = 0L
    private var closed = false

    /** Metadatos que melonDS leyó de la cabecera al reconocer el cartucho. */
    var romInfo: MelonDsRomInfo? = null
        private set

    /**
     * false cuando el juego apaga la consola.
     *
     * Una ROM de DS puede apagar el sistema por su cuenta —PowerOff, o el juego
     * al cerrar la tapa—, y a partir de ahí no hay nada que emular.
     *
     * Sin núcleo todavía se responde `true`: no se sabe que esté apagada, y
     * decir lo contrario cortaría un bucle de emulación que aún no ha empezado.
     */
    override val isRunning: Boolean
        get() = handleOrNull()?.let { MelonDsCoreBridge.isRunning(it) } ?: true

    /**
     * Carga el cartucho y lo deja listo para arrancar.
     *
     * Los metadatos no se devuelven aquí sino en [romInfo]: la interfaz que
     * comparten los dos núcleos devuelve `Unit`, y el título y el código del
     * cartucho se leen una vez, después de cargar, no en cada llamada.
     *
     * @param batteryRam partida guardada anterior, o `null` si es la primera
     *   vez que se abre el juego. La memoria de guardado de la DS no la declara
     *   la cabecera: la decide el código de juego, y por eso se le pasa ya
     *   montada en lugar de dejar que melonDS la suponga.
     */
    override fun loadRom(rom: ByteArray, batteryRam: ByteArray?) {
        val current = handle()
        romInfo = MelonDsCoreBridge.loadRom(current, rom, batteryRam ?: EMPTY)
        // melonDS distingue `Reset` de `Start`: el primero deja la máquina
        // montada y el segundo es lo que pone `Running`. Sin esta llamada el
        // núcleo acepta cualquier llamada a `RunFrame` y no avanza.
        MelonDsCoreBridge.start(current)
    }

    /**
     * Carga el cartucho desde disco.
     *
     * Es el camino que usa la aplicación: una ROM de DS pasa de 200 MB con
     * facilidad, y traerla al montículo de Java sólo para entregarla al nativo
     * sería un pico de memoria que no hace falta. melonDS la lee él mismo.
     */
    fun loadRom(file: File, batteryRam: ByteArray?) {
        val current = handle()
        romInfo = MelonDsCoreBridge.loadRomFile(current, file.absolutePath, batteryRam ?: EMPTY)
        MelonDsCoreBridge.start(current)
    }

    override fun reset() {
        MelonDsCoreBridge.reset(handle())
    }

    /**
     * Emula un fotograma y lo pinta en [framebuffer].
     *
     * El arreglo tiene que medir [MelonDsCoreBridge.FRAME_PIXELS] enteros: dos
     * pantallas de 256×192 colocadas en horizontal, con la táctil a la izquierda.
     */
    override fun runFrame(framebuffer: IntArray, renderVideo: Boolean) {
        MelonDsCoreBridge.runFrame(handle(), framebuffer)
    }

    /**
     * Fija el estado de un botón.
     *
     * El registro de teclas de la DS guarda *niveles*, no eventos, así que
     * esto se puede llamar en cada fotograma sin más.
     */
    override fun setButton(button: EmulatorButton, pressed: Boolean) {
        MelonDsCoreBridge.setButton(handle(), button.ordinal, pressed)
    }

    /**
     * Puntero sobre la pantalla táctil, en coordenadas de pantalla: 0..255 en
     * horizontal y 0..191 en vertical.
     *
     * Las coordenadas van en el espacio de la imagen, no en el del móvil: quien
     * las captura es quien tiene que deshacer el escalado y el recorte.
     */
    override fun setStylus(x: Int, y: Int, pressed: Boolean) {
        MelonDsCoreBridge.setStylus(handle(), x, y, pressed)
    }

    /**
     * Copia muestras estéreo entrelazadas y devuelve cuántas copió.
     *
     * melonDS entrega el audio ya remuestreado a 48 kHz, y no a los 32 768 Hz de
     * Game Boy y GBA, así que el sumidero de AS se abre con la cadencia que dicte
     * la consola.
     *
     * No hace falta vaciar la cola aparte: leerla en cada fotograma ya la
     * mantiene acotada, porque el SPU es un búfer circular que se sobrescribe
     * solo.
     */
    override fun readAudio(buffer: ShortArray): Int =
        MelonDsCoreBridge.readAudio(handle(), buffer)

    /** Serializa el estado de la consola, o `null` si no se pudo. */
    override fun saveState(): ByteArray? =
        handleOrNull()?.let { MelonDsCoreBridge.saveState(it) }

    override fun loadState(state: ByteArray): Boolean =
        handleOrNull()?.let { MelonDsCoreBridge.loadState(it, state) } ?: false

    /**
     * true si el cartucho tiene memoria de guardado.
     *
     * En la DS no se declara en la cabecera: melonDS la deduce del código de
     * juego, y un homebrew puede no traer ninguna. Por eso se pregunta tras
     * insertar el cartucho y no antes.
     *
     * Se pregunta con `hasBattery` y no volcando la partida para mirar si el
     * resultado era nulo: esto se consulta en cada guardado periódico, y copiar
     * el megabyte de la partida sólo para saber si existe sería pagar el volcado
     * entero por una respuesta de sí o no.
     */
    override val hasBatteryRam: Boolean
        get() = handleOrNull()?.let { MelonDsCoreBridge.hasBattery(it) } ?: false

    /** true si el juego ha escrito en la partida guardada desde el último volcado. */
    override val batteryRamDirty: Boolean
        get() = handleOrNull()?.let { MelonDsCoreBridge.batteryDirty(it) } ?: false

    /** Volcado de la memoria de partida, o `null` si el cartucho no tiene. */
    override fun takeBatterySnapshot(): ByteArray? =
        handleOrNull()?.let { MelonDsCoreBridge.snapshotBattery(it) }

    /** Da por guardado lo que había pendiente. */
    override fun acknowledgeBatterySaved() {
        handleOrNull()?.let { MelonDsCoreBridge.acknowledgeBattery(it) }
    }

    override fun close() {
        if (closed) return
        closed = true
        val current = handle
        handle = 0L
        if (current != 0L) MelonDsCoreBridge.destroy(current)
    }

    private fun handleOrNull(): Long? = handle.takeIf { it != 0L }

    private fun handle(): Long {
        check(!closed) { "El núcleo nativo de Nintendo DS está cerrado" }
        if (handle == 0L) {
            handle = MelonDsCoreBridge.create(dataDir)
            check(handle != 0L) { "No se pudo crear el núcleo nativo de Nintendo DS" }
        }
        return handle
    }

    companion object {
        /**
         * melonDS entrega el audio ya remuestreado, a 48 kHz, y no a los
         * 32 768 Hz con los que RavenEmu alimenta a Game Boy y GBA. El sumidero
         * de AS se abre con esta frecuencia, no con la de las otras consolas.
         */
        const val SOURCE_RATE_HZ = 48_000

        /**
         * La DS va a 33,51 MHz sobre 560 190 puntos por fotograma, o sea
         * 59,8261 Hz, frente a los 59,7275 Hz de la Game Boy: casi igual, pero
         * no igual, y la diferencia se nota al cabo de un minuto de juego.
         */
        const val REFRESH_RATE_HZ = 33_513_982.0 / 560_190.0

        private val EMPTY = ByteArray(0)
    }
}
