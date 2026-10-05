package com.example.core.emulation.gba

import com.example.core.NativeCoreBridge
import com.example.core.NativeCoreHandle
import com.example.core.emulation.EmulatorButton
import java.security.MessageDigest

/**
 * Adaptador fino en Kotlin sobre el núcleo C++20 de Game Boy Advance de RavenEmu.
 *
 * El núcleo es pasivo y mono-hilo: no tiene reloj propio ni lanza tramas por sí
 * solo. Quien lo posee decide cuándo emular una trama y con qué cadencia. Este
 * adaptador se limita a traducir esa frontera a objetos Kotlin; la política de
 * emulación vive en [com.example.core.GBACore].
 *
 * Es de un solo uso por partida: [loadRom] fija el cartucho y [close] libera la
 * memoria nativa. Crear y cerrar es barato frente a la emulación misma.
 */
class RavenGbaCore(
    forcedSaveType: GbaSaveType? = null,
    forcedRtc: Boolean? = null,
) : AutoCloseable {

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
     * Se tiene en cuenta en la próxima carga de ROM.
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

    fun loadRom(rom: ByteArray, batteryRam: ByteArray? = null) {
        NativeCoreBridge.loadRom(handle(), rom, batteryRam)
        romHash = MessageDigest.getInstance("SHA-256").digest(rom)
        drainDiagnostics()
    }

    fun reset() {
        NativeCoreBridge.reset(handle())
        drainDiagnostics()
    }

    fun runFrame(framebuffer: IntArray, renderVideo: Boolean = true) {
        NativeCoreBridge.runFrame(handle(), framebuffer, renderVideo)
        drainDiagnostics()
    }

    fun setButton(button: EmulatorButton, pressed: Boolean) {
        NativeCoreBridge.setButton(handle(), button.ordinal, pressed)
    }

    fun readAudio(buffer: ShortArray): Int =
        NativeCoreBridge.readAudio(handle(), buffer)

    val hasBatteryRam: Boolean
        get() = native?.let { NativeCoreBridge.hasBatteryRam(it.value()) } ?: false

    val batteryRamDirty: Boolean
        get() = native?.let { NativeCoreBridge.batteryRamDirty(it.value()) } ?: false

    fun snapshotBatteryRam(): Pair<ByteArray, Long>? {
        val current = native ?: return null
        val snapshot = NativeCoreBridge.snapshotBatteryRam(current.value()) ?: return null
        return snapshot.data to snapshot.generation
    }

    fun acknowledgeBatteryRamSaved(generation: Long) {
        native?.let { NativeCoreBridge.acknowledgeBatteryRamSaved(it.value(), generation) }
    }

    fun saveState(): ByteArray = NativeCoreBridge.saveState(handle())

    fun loadState(state: ByteArray) {
        NativeCoreBridge.loadState(handle(), state)
        drainDiagnostics()
    }

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
        NativeCoreHandle(consoleStorageId(), saveType?.ordinal ?: NO_FORCED_SAVE).also { handle ->
            NativeCoreBridge.setMeasuringTime(handle.value(), measuringTimeRequested)
            // El núcleo nativo se construye con el solo tipo de guardado; el
            // ajuste de reloj se aplica justo después, antes de toda carga de
            // ROM, único momento en que se lee.
            NativeCoreBridge.setGbaForcedRtc(handle.value(), forcedRtc.toNativeRtc())
        }

    private fun handle(): Long {
        check(!closed) { "El núcleo nativo RavenEmu está cerrado" }
        val current = native ?: newHandle(forcedSaveType).also { native = it }
        return current.value()
    }

    companion object {
        const val CYCLES_PER_FRAME = 280_896
        const val REFRESH_RATE_HZ = 16_777_216.0 / CYCLES_PER_FRAME
        private const val NO_FORCED_SAVE = -1
        private const val SNAPSHOT_SCALAR_COUNT = 39

        /** Identificador de almacenamiento de Game Boy Advance en el núcleo. */
        private fun consoleStorageId(): Int = 2

        /** Tri-estado de reloj tal como lo espera el puente: -1 auto, 0 ausente, 1 presente. */
        private fun Boolean?.toNativeRtc(): Int = when (this) {
            null -> -1
            false -> 0
            true -> 1
        }
    }
}
