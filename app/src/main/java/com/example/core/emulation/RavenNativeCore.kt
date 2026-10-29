package com.example.core.emulation

import com.example.core.NativeCoreBridge
import com.example.core.NativeCoreHandle
import com.example.core.cheats.CheatOp
import com.example.core.cheats.packForNative
import com.example.core.emulation.gba.GbaDebugSnapshot
import com.example.core.emulation.gba.GbaDiagnostics
import com.example.core.emulation.gba.GbaSaveType
import java.security.MessageDigest

/**
 * Adaptador fino en Kotlin sobre los núcleos C++20 de RavenEmu: Game Boy /
 * Game Boy Color y Game Boy Advance, elegidos por [console].
 *
 * El núcleo es pasivo y mono-hilo: no tiene reloj propio ni lanza tramas por sí
 * solo. Quien lo posee decide cuándo emular una trama y con qué cadencia. Este
 * adaptador se limita a traducir esa frontera a objetos Kotlin; la política de
 * emulación vive en [com.example.core.GBACore].
 *
 * Sólo una parte del adaptador es de la GBA: tipos de guardado, reloj de
 * cartucho, fotografía de depuración y trucos de memoria. El núcleo Game Boy
 * no los implementa y responde por omisión (ningún guardado forzable, sin
 * reloj, sin fotografía, trucos ignorados), de modo que llamarlos con una ROM
 * de Game Boy es inofensivo y no hace falta bifurcar el código aquí.
 *
 * Es de un solo uso por partida: [loadRom] fija el cartucho y [close] libera la
 * memoria nativa. Crear y cerrar es barato frente a la emulación misma.
 */
class RavenNativeCore(
    /** Consola que este motor emula; fija de fábrica y no cambia después. */
    override val console: ConsoleKind = ConsoleKind.GAME_BOY_ADVANCE,
    forcedSaveType: GbaSaveType? = null,
    forcedRtc: Boolean? = null,
) : EmulatorBackend {

    /** El APU de RavenEmu entrega siempre a 32 768 Hz, en las dos consolas. */
    override val sourceRateHz: Int
        get() = SOURCE_RATE_HZ

    override val refreshRateHz: Double
        get() = REFRESH_RATE_HZ

    var forcedSaveType: GbaSaveType? = forcedSaveType
        set(value) {
            field = value
            native?.let {
                NativeCoreBridge.setGbaForcedSaveType(it.value(), value?.ordinal ?: NO_FORCED_SAVE)
            }
        }

    /**
     * Impone la presencia del reloj de cartucho, o `null` para dejar que la
     * detección decida.
     *
     * La detección busca la biblioteca Seiko de los cartuchos originales y no
     * puede afirmar nada de una ROM modificada: un juego puede pilotar el reloj
     * sin portar esa firma. Sin este ajuste, tal juego anuncia que la hora es
     * ilegible y el jugador no tiene recurso.
     *
     * Se tiene en cuenta en la próxima carga de ROM. Sólo rige en GBA: el
     * reloj de los cartuchos MBC3 de Game Boy lo declara el propio cartucho.
     */
    var forcedRtc: Boolean? = forcedRtc
        set(value) {
            field = value
            native?.let { NativeCoreBridge.setGbaForcedRtc(it.value(), value.toNativeRtc()) }
        }

    /** Reloj realmente presente en el cartucho cargado. */
    val rtcActive: Boolean
        get() = native?.let { NativeCoreBridge.gbaRtcActive(it.value()) } ?: false

    private var native: NativeCoreHandle? = null
    private var closed = false

    var romHash: ByteArray = ByteArray(0)
        private set

    var onDiagnosticEvent: ((GbaDiagnostics.Event, String) -> Unit)? = null

    private var measuringTimeRequested = false

    var measuringTime: Boolean
        get() = measuringTimeRequested
        set(value) {
            measuringTimeRequested = value
            native?.let { NativeCoreBridge.setMeasuringTime(it.value(), value) }
        }

    val saveType: GbaSaveType
        get() = native
            ?.let { GbaSaveType.entries[NativeCoreBridge.gbaSaveType(it.value())] }
            ?: GbaSaveType.NONE

    /**
     * Formato de píxeles que entrega [runFrame]. Se conoce tras cargar la ROM:
     * en Game Boy es `INDEXED_4` para una DMG y `ARGB_8888` para una CGB, y en
     * GBA es siempre `ARGB_8888`.
     */
    override val framebufferFormat: FramebufferFormat
        get() = native
            ?.let { FramebufferFormat.entries.getOrElse(NativeCoreBridge.framebufferFormat(it.value())) { FramebufferFormat.ARGB_8888 } }
            ?: FramebufferFormat.ARGB_8888

    override fun loadRom(rom: ByteArray, batteryRam: ByteArray?) {
        NativeCoreBridge.loadRom(handle(), rom, batteryRam)
        // Cargar una ROM retira los trucos nativos (pertenecen a la anterior);
        // si esta sesión ya tenía lista, se restituye antes de emular.
        if (cheats.isNotEmpty()) NativeCoreBridge.setCheats(handle(), cheats.packForNative())
        romHash = MessageDigest.getInstance("SHA-256").digest(rom)
        drainDiagnostics()
    }

    override fun reset() {
        NativeCoreBridge.reset(handle())
        drainDiagnostics()
    }

    override fun runFrame(framebuffer: IntArray, renderVideo: Boolean) {
        NativeCoreBridge.runFrame(handle(), framebuffer, renderVideo)
        drainDiagnostics()
    }

    override fun setButton(button: EmulatorButton, pressed: Boolean) {
        NativeCoreBridge.setButton(handle(), button.ordinal, pressed)
    }

    /**
     * Fija los trucos de memoria activos; una lista vacía los retira.
     *
     * El núcleo nativo borra su lista al cargar una ROM —los trucos
     * pertenecen a la ROM que los trajo—, de modo que aquí se recuerdan y se
     * vuelven a enviar tras [loadRom]: quien cambia de cartucho sólo tiene que
     * llamar a este método una vez por sesión de juego.
     *
     * Sólo los entiende el núcleo GBA; el Game Boy los descarta.
     */
    fun setCheats(ops: List<CheatOp>) {
        cheats = ops
        NativeCoreBridge.setCheats(handle(), ops.packForNative())
    }

    private var cheats: List<CheatOp> = emptyList()

    // ── Enlace LAN entre dos dispositivos ───────────────────────────────────
    //
    // El núcleo no sabe de redes: aquí sólo se traduce la frontera. El papel de
    // padre o hijo lo fija el transporte al abrirse (la sala es el padre), no
    // el juego.

    /**
     * Abre el transporte TCP del enlace y devuelve su asa, o un código
     * negativo si no se pudo abrir (-1 socket, -2 sin respuesta a tiempo).
     * `server = true` crea la sala; `false` se une a la de `host`.
     */
    fun linkConnect(server: Boolean, host: String?, port: Int): Long =
        NativeCoreBridge.linkConnect(server, host, port)

    /**
     * Engancha el enlace a la máquina. Hecho una vez, sobrevive a la carga de
     * ROM: el nativo se recablea solo en cada máquina nueva.
     */
    fun linkAttach(endpoint: Long): Boolean = NativeCoreBridge.linkAttach(endpoint, handle())

    /** 0 esperando al par, 1 enlazado, 2 cerrado o caído. */
    fun linkStatus(endpoint: Long): Int = NativeCoreBridge.linkStatus(endpoint)

    /**
     * Desengancha y destruye el enlace. Con el núcleo ya cerrado no hay máquina
     * de la que desengancharse —se llevó el cable consigo—, así que no se toca
     * un asa liberada.
     */
    fun linkClose(endpoint: Long) {
        if (closed) return
        val current = native ?: return
        NativeCoreBridge.linkClose(endpoint, current.value())
    }

    override fun readAudio(buffer: ShortArray): Int =
        NativeCoreBridge.readAudio(handle(), buffer)

    override val hasBatteryRam: Boolean
        get() = native?.let { NativeCoreBridge.hasBatteryRam(it.value()) } ?: false

    override val batteryRamDirty: Boolean
        get() = native?.let { NativeCoreBridge.batteryRamDirty(it.value()) } ?: false

    fun snapshotBatteryRam(): Pair<ByteArray, Long>? {
        val current = native ?: return null
        val snapshot = NativeCoreBridge.snapshotBatteryRam(current.value()) ?: return null
        return snapshot.data to snapshot.generation
    }

    /**
     * Número de generación del último volcado.
     *
     * El nativo lo usa para saber *qué* volcado se confirmó: si el juego ha
     * escrito otra vez entre el volcado y la escritura en disco, confirmar el
     * antiguo dejaría la RAM marcada como sucia y se volvería a volcar. Vive
     * aquí dentro porque quien llama no debería enterarse de que existe.
     */
    private var lastBatteryGeneration = 0L

    override fun takeBatterySnapshot(): ByteArray? {
        val snapshot = snapshotBatteryRam() ?: return null
        lastBatteryGeneration = snapshot.second
        return snapshot.first
    }

    override fun acknowledgeBatterySaved() {
        val current = native ?: return
        NativeCoreBridge.acknowledgeBatteryRamSaved(current.value(), lastBatteryGeneration)
    }

    fun acknowledgeBatteryRamSaved(generation: Long) {
        native?.let { NativeCoreBridge.acknowledgeBatteryRamSaved(it.value(), generation) }
    }

    override fun saveState(): ByteArray = NativeCoreBridge.saveState(handle())

    override fun loadState(state: ByteArray): Boolean {
        NativeCoreBridge.loadState(handle(), state)
        drainDiagnostics()
        return true
    }

    /** Fotografía de depuración de la GBA, o `null` si el núcleo no es GBA. */
    fun debugSnapshot(): GbaDebugSnapshot? {
        val current = native ?: return null
        val snapshot = NativeCoreBridge.debugSnapshot(current.value()) ?: return null
        val s = snapshot.scalars
        require(s.size >= SNAPSHOT_SCALAR_COUNT) { "Fotografía GBA nativa inválida" }
        // El orden de `s` es el contrato fijado en `asgba_jni.cpp`, campo por
        // campo: no se reordena sin cambiar ambos.
        return GbaDebugSnapshot(
            instructionsPerFrame = s[0],
            programCounter = s[1],
            thumb = s[2] != 0,
            halted = s[3] != 0,
            lastSwi = s[4],
            lastInterruptMask = s[5],
            vcount = s[6],
            lastDmaChannel = s[7],
            dmaActive = s[8] != 0,
            fifoASize = s[9],
            fifoBSize = s[10],
            fifoAEmptyReads = s[11],
            fifoBEmptyReads = s[12],
            audioUnderruns = s[13],
            unsupportedSwiCount = s[14],
            undefinedInstructionCount = s[15],
            unsupportedAccessCount = s[16],
            missingInterruptCount = s[17],
            decompressionErrorCount = s[18],
            firstUnsupportedAddress = s[19],
            dispcnt = s[20],
            bg0Control = s[21],
            bg1Control = s[22],
            bg2Control = s[23],
            bg3Control = s[24],
            blendControl = s[25],
            blendAlpha = s[26],
            blendBrightness = s[27],
            windowInside = s[28],
            windowOutside = s[29],
            lumaMeasured = measuringTime,
            lumaMin = s[30],
            lumaMax = s[31],
            lumaMean = s[32],
            layerPixels = snapshot.layerPixels,
            bg2ReferenceX = s[33],
            bg2ReferenceY = s[34],
            bg2ScaleX = s[35],
            bg2ScaleY = s[36],
            bg2MatrixWrites = s[37],
            bg2ReferenceWrites = s[38],
            swiCounts = snapshot.swiCounts,
            ppuMillis = snapshot.timingsMillis.getOrElse(0) { 0.0 },
            dmaMillis = snapshot.timingsMillis.getOrElse(1) { 0.0 },
            apuMillis = snapshot.timingsMillis.getOrElse(2) { 0.0 },
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        native?.close()
    }

    private fun drainDiagnostics() {
        val current = native ?: return
        val batch = NativeCoreBridge.drainDiagnostics(current.value()) ?: return
        val listener = onDiagnosticEvent ?: return
        for (index in batch.events.indices) {
            val event = GbaDiagnostics.Event.entries.getOrNull(batch.events[index]) ?: continue
            listener(event, batch.details.getOrElse(index) { "" })
        }
    }

    private fun newHandle(saveType: GbaSaveType?): NativeCoreHandle =
        NativeCoreHandle(console.storageId, saveType?.ordinal ?: NO_FORCED_SAVE).also { handle ->
            NativeCoreBridge.setMeasuringTime(handle.value(), measuringTimeRequested)
            // El núcleo nativo se construye con el solo tipo de guardado; el
            // ajuste de reloj se aplica justo después, antes de toda carga de
            // ROM, único momento en que se lee. En Game Boy ambas llamadas son
            // sin efecto: el tipo de guardado lo declara la ROM y no hay RTC
            // que forzar fuera de los cartuchos que lo traen.
            NativeCoreBridge.setGbaForcedRtc(handle.value(), forcedRtc.toNativeRtc())
        }

    private fun handle(): Long {
        check(!closed) { "El núcleo nativo RavenEmu está cerrado" }
        val current = native ?: newHandle(forcedSaveType).also { native = it }
        return current.value()
    }

    companion object {
        // Ciclos por trama de la GBA (16 777 216 Hz / 280 896 = 59,7275 Hz).
        // La Game Boy corre a 4 194 304 Hz sobre 70 224 puntos por trama, que
        // es exactamente la misma cadencia: REFRESH_RATE_HZ vale para ambas
        // consolas y no hace falta saber cuál se emula para cadenciar.
        const val CYCLES_PER_FRAME = 280_896
        const val REFRESH_RATE_HZ = 16_777_216.0 / CYCLES_PER_FRAME

        /** El APU de Game Boy y GBA entrega siempre a 32 768 Hz. */
        const val SOURCE_RATE_HZ = 32_768

        private const val NO_FORCED_SAVE = -1
        private const val SNAPSHOT_SCALAR_COUNT = 39

        /** Tri-estado de reloj tal como lo espera el puente: -1 auto, 0 ausente, 1 presente. */
        private fun Boolean?.toNativeRtc(): Int = when (this) {
            null -> -1
            false -> 0
            true -> 1
        }
    }
}
