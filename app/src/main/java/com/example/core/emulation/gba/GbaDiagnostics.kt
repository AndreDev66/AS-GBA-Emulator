package com.example.core.emulation.gba

/**
 * Categorías de anomalías e instrumentación del núcleo Game Boy Advance.
 *
 * El orden de [Event] es un contrato con el puente JNI: el núcleo C++ envía el
 * ordinal de `ravenemu::DiagnosticEvent`. No reordenar sin cambiar ambos lados.
 */
object GbaDiagnostics {

    /** Categorías de anomalías señaladas por el motor. */
    enum class Event {
        /** Llamada software del BIOS no implementada. */
        UNSUPPORTED_SWI,

        /** Patrón de instrucción no reconocido por un decodificador. */
        UNDEFINED_INSTRUCTION,

        /** Acceso a una dirección fuera del plano de memoria. */
        UNSUPPORTED_ACCESS,

        /** Espera de una interrupción que no llega. */
        MISSING_INTERRUPT,

        /** Flujo comprimido incoherente o truncado. */
        DECOMPRESSION_ERROR,
    }

    /** Número de llamadas al BIOS seguidas: 0x00 a 0x2F cubre todo uso de un juego. */
    const val SWI_RANGE = 0x30
}
