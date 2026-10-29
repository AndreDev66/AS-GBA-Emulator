package com.example.core.emulation.gba

/**
 * Fotografía del estado del motor en un instante, para una capa de depuración.
 * Todos los valores son números simples: la capa de aplicación los formatea sin
 * conocer el interior del motor.
 */
data class GbaDebugSnapshot(
    val instructionsPerFrame: Int,
    val programCounter: Int,
    val thumb: Boolean,
    val halted: Boolean,
    val lastSwi: Int,
    val lastInterruptMask: Int,
    val vcount: Int,
    val lastDmaChannel: Int,
    val dmaActive: Boolean,
    val fifoASize: Int,
    val fifoBSize: Int,
    val fifoAEmptyReads: Int,
    val fifoBEmptyReads: Int,
    val audioUnderruns: Int,
    val unsupportedSwiCount: Int,
    val undefinedInstructionCount: Int,
    val unsupportedAccessCount: Int,
    val missingInterruptCount: Int,
    val decompressionErrorCount: Int,
    val firstUnsupportedAddress: Int,
    val dispcnt: Int,
    val bg0Control: Int,
    val bg1Control: Int,
    val bg2Control: Int,
    val bg3Control: Int,
    val blendControl: Int,
    val blendAlpha: Int,
    val blendBrightness: Int,
    val windowInside: Int,
    val windowOutside: Int,
    val lumaMeasured: Boolean,
    val lumaMin: Int,
    val lumaMax: Int,
    val lumaMean: Int,
    val layerPixels: IntArray,
    val bg2ReferenceX: Int,
    val bg2ReferenceY: Int,
    val bg2ScaleX: Int,
    val bg2ScaleY: Int,
    val bg2MatrixWrites: Int,
    val bg2ReferenceWrites: Int,
    val swiCounts: IntArray,
    val ppuMillis: Double,
    val dmaMillis: Double,
    val apuMillis: Double,
) {
    /** Prioridad declarada del plan [bg] (0 = el más cercano). */
    fun bgPriority(bg: Int): Int = bgControl(bg) and 0x3

    /** `true` si el plan [bg] está activado en `DISPCNT`. */
    fun bgEnabled(bg: Int): Boolean = dispcnt and (1 shl (8 + bg)) != 0

    private fun bgControl(bg: Int): Int = when (bg) {
        0 -> bg0Control
        1 -> bg1Control
        2 -> bg2Control
        else -> bg3Control
    }

    /** `true` si se ha detectado al menos una anomalía desde la carga. */
    val hasAnomalies: Boolean
        get() = unsupportedSwiCount > 0 ||
            undefinedInstructionCount > 0 ||
            unsupportedAccessCount > 0 ||
            missingInterruptCount > 0 ||
            decompressionErrorCount > 0
}
