package com.example.data

import android.view.KeyEvent
import com.example.core.GBACore

/**
 * Los diez botones de una Game Boy Advance, con su máscara en [GBACore].
 *
 * El orden del enum no importa para el mapeo, porque no se serializa por posición.
 * En [com.example.core.emulation.EmulatorButton] el ordinal sí es el contrato con
 * el JNI, así que ese enum no se puede reordenar sin romper nada; éste sí.
 */
enum class GbaButton(
    val label: String,
    val glyph: String,
    val keyMask: Int,
    val defaultBindings: List<Int>
) {
    UP("Arriba", "▲", GBACore.KEY_UP, listOf(KeyEvent.KEYCODE_DPAD_UP)),
    DOWN("Abajo", "▼", GBACore.KEY_DOWN, listOf(KeyEvent.KEYCODE_DPAD_DOWN)),
    LEFT("Izquierda", "◀", GBACore.KEY_LEFT, listOf(KeyEvent.KEYCODE_DPAD_LEFT)),
    RIGHT("Derecha", "▶", GBACore.KEY_RIGHT, listOf(KeyEvent.KEYCODE_DPAD_RIGHT)),

    // A y B aceptan dos teclas por defecto porque los mandos con etiquetas de
    // Nintendo envían el círculo como BUTTON_A y la cruz como BUTTON_B, mientras
    // que los de Xbox/PlayStation envían BUTTON_X = cruz e BUTTON_Y = círculo.
    A("Botón A", "A", GBACore.KEY_A, listOf(
        KeyEvent.KEYCODE_BUTTON_A,
        KeyEvent.KEYCODE_BUTTON_Y,
        KeyEvent.KEYCODE_DPAD_CENTER
    )),
    B("Botón B", "B", GBACore.KEY_B, listOf(
        KeyEvent.KEYCODE_BUTTON_B,
        KeyEvent.KEYCODE_BUTTON_X
    )),

    START("Start", "START", GBACore.KEY_START, listOf(KeyEvent.KEYCODE_BUTTON_START)),
    SELECT("Select", "SELECT", GBACore.KEY_SELECT, listOf(KeyEvent.KEYCODE_BUTTON_SELECT)),

    L("Hombro L", "L", GBACore.KEY_L, listOf(
        KeyEvent.KEYCODE_BUTTON_L1,
        KeyEvent.KEYCODE_BUTTON_L2
    )),
    R("Hombro R", "R", GBACore.KEY_R, listOf(
        KeyEvent.KEYCODE_BUTTON_R1,
        KeyEvent.KEYCODE_BUTTON_R2
    ));

    /**
     * Si el botón es una dirección de la cruceta.
     *
     * Lo consume la entrada analógica para recorrer sólo las cuatro direcciones
     * sin escribir a mano los cuatro casos.
     */
    val isDirectional: Boolean get() = this == UP || this == DOWN || this == LEFT || this == RIGHT

    companion object {
        /** Mapa de fábrica: cada botón con sus teclas por defecto. */
        fun defaults(): Map<GbaButton, List<Int>> = entries.associateWith { it.defaultBindings }
    }
}

/**
 * Nombre legible de una tecla de Android, para la lista de reasignación.
 *
 * `KeyEvent.keyCodeToString` devuelve el identificador técnico (`KEYCODE_BUTTON_L1`).
 * Quitar el prefijo y cambiar los guiones por espacios deja `BUTTON L1`, que es lo
 * que está impreso en el propio mando.
 */
fun gbaKeyLabel(keyCode: Int): String {
    if (keyCode <= 0) return "Sin asignar"
    val raw = runCatching { KeyEvent.keyCodeToString(keyCode) }.getOrNull() ?: return "Tecla $keyCode"
    return raw.removePrefix("KEYCODE_").replace('_', ' ').trim().ifBlank { "Tecla $keyCode" }
}

/**
 * Asignación de teclas del mando, editable por el usuario.
 *
 * Lo normal es que Android ya entregue botones con la etiqueta correcta, pero los
 * mandos Bluetooth no respetan ninguna convención: hay mando con los gatillos como
 * botones digitales, mando que envía la palanca como cruceta y mando que trae la A y
 * la B intercambiadas. Antes esto sólo se arreglaba editando código; aquí cada botón
 * de la GBA acepta la tecla que se pulse.
 *
 * Dos reglas mantienen el mapeo coherente: una tecla sólo puede pertenecer a un
 * botón, así que asignarla a uno la quita de los demás (si no, la misma pulsación
 * movería dos botones a la vez), y un botón puede vaciarse por completo, que es como
 * se desactiva la palanca analógica si el mando la envía duplicada.
 *
 * La palanca y los gatillos analógicos ([MotionEvent]) no son reasignables: no son
 * teclas sino ejes, y cada mando los expone con nombres distintos. Se controlan por
 * separado con los dos interruptores.
 */
data class GamepadMapping(
    val bindings: Map<GbaButton, List<Int>> = GbaButton.defaults(),
    val useAnalogStick: Boolean = true,
    val useAnalogTriggers: Boolean = true
) {
    /** Teclas de Android que alimentan este botón de la GBA. */
    fun keyCodesFor(button: GbaButton): List<Int> = bindings[button].orEmpty()

    /** Texto que resume las teclas asignadas, para la lista de ajustes. */
    fun labelFor(button: GbaButton): String {
        val codes = keyCodesFor(button)
        if (codes.isEmpty()) return "Sin asignar"
        return codes.joinToString(" · ") { gbaKeyLabel(it) }
    }

    /** Botón al que pertenece una tecla, o `null` si no está asignada a ninguno. */
    fun buttonFor(keyCode: Int): GbaButton? =
        GbaButton.entries.firstOrNull { keyCodesFor(it).contains(keyCode) }

    /**
     * Devuelve el mapeo con [button] escuchando a [keyCode] y a nada más.
     *
     * Sustituye la lista en vez de añadir, porque quien reasigna quiere que el
     * botón obedezca a lo que acaba de pulsar y no a lo que ya tenía.
     */
    fun withAssignment(button: GbaButton, keyCode: Int): GamepadMapping =
        withAssignment(button, listOf(keyCode))

/**
     * Igual que [withAssignment], pero con varias teclas a la vez.
     *
     * Existe para los acordes: quien configura un hombro y pulsa L1 y L2 a la vez
     * quiere que las dos valan, no que se quede con la primera y pierda la segunda.
     * Se mantienen las dos reglas de siempre: ninguna tecla puede vivir en dos
     * botones y las que no se pulsaron se descartan.
     */
    fun withAssignment(button: GbaButton, keyCodes: List<Int>): GamepadMapping {
        // Las teclas repetidas dentro del propio acorde son un accidento del
        // lector de teclado, no dos asignaciones distintas.
        val wanted = keyCodes.filter { it > 0 }.distinct()
        val others = bindings.mapValues { (_, codes) ->
            codes.filter { it !in wanted }
        }
        // Una lista vacía o sólo inválida significa "desasignar": la lista del
        // botón se vacía en vez de dejarse como estaba.
        val updated = others + (button to wanted)
        return copy(bindings = updated)
    }

    /** Vuelve a la asignación de fábrica. */
    fun withDefaults(): GamepadMapping = GamepadMapping()

    val isDefault: Boolean
        get() = bindings == GbaButton.defaults() && useAnalogStick && useAnalogTriggers

    /**
     * Formato: `stick|triggers;UP;DOWN;LEFT;RIGHT;A;B;START;SELECT;L;R`, donde cada
     * grupo es una lista de códigos separados por comas. El orden de [GbaButton]
     * sí forma parte del formato, pero como el enum es fijo y sólo se añaden
     * botones al final, un mapeo guardado sigue leyéndose bien.
     */
    fun serialize(): String = buildString {
        append(if (useAnalogStick) '1' else '0')
        append(if (useAnalogTriggers) '1' else '0')
        for (button in GbaButton.entries) {
            append(';')
            for (code in keyCodesFor(button)) {
                append(code)
                append(',')
            }
        }
    }

    companion object {
        /**
         * Lee un mapeo guardado; cualquier dato corrupto cae en el de fábrica.
         *
         * Se valida el grupo entero antes de fiarse de él. Un `keyCode` ilegible se
         * descarta, pero si el grupo tenía contenido y se descarta todo, el mapeo se
         * da por roto y se vuelve al de fábrica. A medias sería peor que no tener
         * nada: el usuario creería tener un mando con la A y la B cambiadas cuando en
         * realidad la A no responde.
         */
        fun deserialize(raw: String?): GamepadMapping {
            if (raw.isNullOrEmpty()) return GamepadMapping()
            val parts = raw.split(';')
            val flags = parts.getOrNull(0).orEmpty()
            val result = GbaButton.defaults().toMutableMap()

            for ((index, button) in GbaButton.entries.withIndex()) {
                val chunk = parts.getOrNull(index + 1) ?: continue
                val tokens = chunk.split(',').filter { it.isNotBlank() }
                val codes = tokens.map { it.trim().toIntOrNull() }
                // Grupo vacío = "sin asignar", que es distinto de "no guardado".
                if (tokens.isNotEmpty() && codes.any { it == null }) return GamepadMapping()
                result[button] = codes.filter { it != null && it > 0 }.map { it!! }
            }
            return GamepadMapping(
                bindings = result,
                useAnalogStick = flags.getOrNull(0) != '0',
                useAnalogTriggers = flags.getOrNull(1) != '0'
            )
        }
    }
}