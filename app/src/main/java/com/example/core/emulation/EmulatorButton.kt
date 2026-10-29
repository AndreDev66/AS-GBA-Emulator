package com.example.core.emulation

/**
 * Botones lógicos transmitidos al motor nativo.
 *
 * El orden de los valores es un contrato con el puente JNI: cada constante se
 * transporta por su ordinal y el núcleo C++ lo interpreta como
 * `ravenemu::Button`. No reordenar sin cambiar ambos lados.
 *
 * [X] y [Y] se añaden al final, y sólo los entiende el núcleo de Nintendo DS,
 * que es el único con esos dos botones en el cartucho. Los ordinales anteriores
 * no se mueven: RavenEmu los traduce por posición.
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

    /** Botón X, propio de Nintendo DS. RavenEmu lo descarta. */
    X,

    /** Botón Y, propio de Nintendo DS. RavenEmu lo descarta. */
    Y,
}
