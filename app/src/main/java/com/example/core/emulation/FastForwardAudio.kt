package com.example.core.emulation

/**
 * Qué se hace con el sonido mientras la partida va acelerada.
 *
 * A 1x el audio marca la cadencia de la sesión y no se toca. Al acelerar, el
 * motor produce tantas muestras por segundo real como la velocidad y el sumidero
 * entrega a ritmo fijo, así que hay que decidir qué pasa con las que sobran.
 *
 * [SILENCED] las descarta. El avance rápido no gasta nada en sonido y la cadencia
 * pasa a ser la de la consola dividida por la velocidad.
 *
 * [ACCELERATED] las comprime, así que acompañan a la partida a su velocidad:
 * suenan más agudas, como una cinta acelerada, y la escritura bloqueante vuelve a
 * marcar la cadencia.
 */
enum class FastForwardAudio {
    SILENCED,
    ACCELERATED
}
