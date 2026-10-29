package com.example.core.emulation

/**
 * Lo que AS necesita de un núcleo de emulación, sea el que sea.
 *
 * El motivo de que exista es que hay dos motores que no se parecen en nada:
 * RavenEmu —C++20, Game Boy y GBA— y melonDS —C++17, Nintendo DS—, cada uno con
 * su puente JNI, su frontera y sus rarezas. Lo único que comparten es la
 * frontera *de la interfaz*: quién manda el fotograma, quién recibe los botones
 * y dónde se pide la partida guardada.
 *
 * Lo que se deja fuera es justamente lo que no es común, y no por descuido:
 * el reloj de cartucho, los tipos de guardado forzables, los trucos de memoria,
 * la fotografía de depuración y el enlace por cable son de la GBA; el puntero
 * sobre la pantalla táctil es de la DS. Meterlos aquí con valores inventados
 * daría la impresión de que AS conoce un hardware que en realidad no conoce.
 *
 * El núcleo sigue siendo pasivo: no tiene reloj ni hilo propio, y quien lo
 * llama decide cuándo emular un fotograma. melonDS crea hilos por dentro, pero
 * eso no altera el contrato.
 */
interface EmulatorBackend : AutoCloseable {

    /** Consola que este motor emula. Fija de fábrica y no cambia. */
    val console: ConsoleKind

    /**
     * Formato de píxeles que entrega [runFrame].
     *
     * `INDEXED_4` es el modo monocromo de la Game Boy DMG, donde el núcleo
     * entrega niveles del 0 al 3 y quien pinta los colorea. Las demás consolas
     * entregan color.
     */
    val framebufferFormat: FramebufferFormat
        get() = FramebufferFormat.ARGB_8888

    /**
     * Frecuencia a la que el núcleo entrega el audio, en hercios.
     *
     * No es un detalle: el sumidero de AS abre la pista a esta frecuencia y
     * dimensiona su relleno con ella. RavenEmu da 32 768 Hz para Game Boy y
     * GBA; melonDS remuestrea a 48 kHz. Confundir los dos es un sonido que
     * corre más deprisa o más lento que la imagen.
     */
    val sourceRateHz: Int

    /** Frecuencia de refresco de la consola, en hercios. */
    val refreshRateHz: Double

    /**
     * Inserta el cartucho.
     *
     * @param batteryRam la partida guardada anterior, o `null` si es la primera
     *   vez que se abre el juego. Se inyecta con la propia carga porque es lo
     *   que el juego relee por su cuenta al arrancar.
     */
    fun loadRom(rom: ByteArray, batteryRam: ByteArray? = null)

    /** Reinicia la consola conservando el cartucho. */
    fun reset()

    /**
     * false si la consola se ha apagado sola.
     *
     * La Nintendo DS puede apagarse —el juego llama a PowerOff, o el jugador
     * cierra la tapa— y a partir de ahí `RunFrame` no vuelve a hacer nada
     * útil. Game Boy y GBA no tienen esa situación, de ahí el `true` por
     * omisión: no es que no se pueda apagar, es que no hay forma de que el juego
     * lo pida.
     */
    val isRunning: Boolean
        get() = true

    /** Emula un fotograma y lo pinta en el arreglo dado. */
    fun runFrame(framebuffer: IntArray, renderVideo: Boolean = true)

    /** Fija el estado de un botón. El registro de teclas guarda niveles, no eventos. */
    fun setButton(button: EmulatorButton, pressed: Boolean)

    /**
     * Copia muestras de audio y devuelve cuántos valores `short` copió.
     *
     * Cada muestra estéreo ocupa dos `short` entrelazados; el valor devuelto
     * cuenta ambos canales, igual que la longitud que consume la salida de audio.
     *
     * Llamar a esto en cada fotograma es también lo que mantiene acotada la cola
     * de audio del núcleo, y por eso no hay un "vaciar audio" aparte: el SPU de
     * melonDS y el de RavenEmu son búferes circulares que se sobrescriben solos,
     * así que leerlos siempre los deja en su sitio aunque no se escriba nada en
     * la pista —que es justo lo que pasa al avanzar en silencio.
     */
    fun readAudio(buffer: ShortArray): Int

    /** Serializa el estado de la consola, o `null` si no se pudo. */
    fun saveState(): ByteArray?

    /** Restaura un estado. Devuelve `false` si el estado no era válido. */
    fun loadState(state: ByteArray): Boolean

    /**
     * Puntero sobre la pantalla táctil, en coordenadas de pantalla.
     *
     * Sólo la Nintendo DS tiene pantalla táctil; en las demás no hay dónde
     * señalar, y la entrada va entera por [setButton].
     */
    fun setStylus(x: Int, y: Int, pressed: Boolean) {}

    /** true si el cartucho tiene memoria de guardado. */
    val hasBatteryRam: Boolean
        get() = false

    /** true si el juego ha escrito en la partida guardada desde el último volcado. */
    val batteryRamDirty: Boolean
        get() = false

    /**
     * Vuelca la memoria de partida del cartucho.
     *
     * El volcado recuerda su número de generación para poder confirmar luego la
     * escritura con [acknowledgeBatterySaved]; quien llama no necesita verlo.
     */
    fun takeBatterySnapshot(): ByteArray? = null

    /** Confirma que el volcado anterior se escribió en disco. */
    fun acknowledgeBatterySaved() {}
}
