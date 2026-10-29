package com.example.ui.components

import com.example.data.TouchControl

/**
 * Acumulador de zoom del pellizco de edición, separado del puntero para poder
 * probarlo.
 *
 * El pellizco multiplica los zooms de cada evento (`calculateZoom`, la
 * proporción entre la apertura de los dedos de un evento y la del anterior)
 * sobre una base fijada al anclar. Es el gesto quien decide cuándo re-anclar:
 * cada vez que cambia el juego de dedos —uno sale y entra otro, o el sistema
 * lo reengancha— la base se vuelve a fijar sobre el tamaño que está dibujado
 * en ese momento, y el tamaño de la transición no se multiplica.
 *
 * Sin ese re-anclaje, el evento que mezcla dedos viejos (sus posiciones
 * anteriores) y nuevos (sus posiciones de llegada) producía una proporción
 * basura que, multiplicada acumulada contra la base del principio, hacía que
 * el botón saltara casi siempre hacia el tamaño de antes: el "vuelve a su
 * tamaño original" que se veía al pellizcar.
 *
 * Un solo dedo en el aire no cancela nada ([singleFinger]): el gesto espera y
 * re-ancla sobre lo dibujado cuando el segundo dedo vuelve. El tamaño queda
 * además acotado por [minScale] y [maxScale].
 */
class PinchTracker(
    private val minScale: Float,
    private val maxScale: Float
) {

    /** Control al que se está aplicando el pellizco, o `null` si no hay. */
    var target: TouchControl? = null
        private set

    private var anchorScale = 1f
    private var totalZoom = 1f
    private var lastIds: Set<Long> = emptySet()

    /** Empieza un pellizco sobre [targetAt], anclado al tamaño [drawnScale]. */
    fun anchor(targetAt: TouchControl, drawnScale: Float, ids: Set<Long>) {
        target = targetAt
        reanchor(drawnScale, ids)
    }

    /**
     * Re-ancla el mismo objetivo sobre lo que hay dibujado ahora ([drawnScale])
     * y fija el juego de dedos actual. La acumulación se reinicia pero desde la
     * base nueva: cualquier proporción basura de la transición queda fuera.
     */
    fun reanchor(drawnScale: Float, ids: Set<Long>) {
        anchorScale = drawnScale
        totalZoom = 1f
        lastIds = ids
    }

    /** `true` si [ids] es el mismo juego de dedos que el último evento. */
    fun sameFingers(ids: Set<Long>): Boolean = ids == lastIds

    /** Un dedo en el aire: se espera sin tocar nada. */
    fun singleFinger() {
        lastIds = emptySet()
    }

    /** Tamaño nuevo que produce un evento con el juego de dedos ya asentado. */
    fun event(eventZoom: Float): Float {
        totalZoom *= eventZoom
        return (anchorScale * totalZoom).coerceIn(minScale, maxScale)
    }

    /** Termina el pellizco: todos los dedos fuera. */
    fun end() {
        target = null
        anchorScale = 1f
        totalZoom = 1f
        lastIds = emptySet()
    }
}