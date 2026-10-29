package com.example.core.emulation

/**
 * Formato de píxel que el núcleo entrega en su framebuffer.
 *
 * Coincide con el `ravenemu::FramebufferFormat` nativo, ordinal por ordinal:
 * el puente JNI sólo transporta el número y aquí se vuelve a tipar.
 */
enum class FramebufferFormat {
    /** Píxeles ARGB completos, como los produce el núcleo GBA. */
    ARGB_8888,

    /**
     * Niveles lógicos `0..3` de una pantalla monócroma: el núcleo Game Boy en
     * modo DMG entrega sombras, no color, y quién las colorea es la interfaz.
     */
    INDEXED_4,
}
