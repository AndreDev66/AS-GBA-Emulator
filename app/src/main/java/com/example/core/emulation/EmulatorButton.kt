package com.example.core.emulation

/**
 * Botones lógicos transmitidos al motor nativo.
 *
 * El orden de los valores es un contrato con el puente JNI: cada constante se
 * transporta por su ordinal y el núcleo C++ lo interpreta como
 * `ravenemu::Button`. No reordenar sin cambiar ambos lados.
 */
enum class EmulatorButton {
    UP,
    DOWN,
    LEFT,
    RIGHT,
    A,
    B,
    START,
    SELECT,
    L,
    R,
}
