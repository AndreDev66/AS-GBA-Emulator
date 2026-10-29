package com.example.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.example.core.GBACore
import com.example.data.GbaButton
import com.example.data.GamepadMapping
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * Traducción de un mando físico a los botones de una Game Boy Advance.
 *
 * Los mandos Bluetooth llegan al proceso por dos caminos, y ninguno basta solo:
 *
 *  - Eventos de tecla, para botones y cruceta física. Es lo que envía la mayoría,
 *    incluidos los de Xbox, DualSense, 8BitDo y GameSir. La traducción sale de
 *    [GamepadMapping], que el usuario reasigna desde Ajustes, de modo que un mando
 *    con la A y la B cruzadas se arregla sin tocar el código.
 *  - Eventos de eje, para la palanca izquierda y los gatillos.
 *
 * El caso difícil es el segundo: un evento de eje no dice qué eje se movió. Si al
 * leerlos se dieran por perdidas las direcciones, cada gatillo soltaría la palanca.
 * Por eso las direcciones sólo se tocan cuando el mando declara ejes direccionales, y
 * de los dos ejes que describen la misma dirección (palanca y gorra) se toma el de
 * mayor valor absoluto. Los gatillos se leen aparte y sólo se tocan los declarados.
 *
 * Los ejes no participan en el reasignado: no son teclas y cada mando los nombra
 * distinto. Lo que sí se puede apagar, para un mando que reporte la palanca dos
 * veces, son los interruptores de la propia asignación.
 */
object GamepadInput {

    /** Por debajo de este valor la palanca se considera en el centro. */
    private const val STICK_DEADZONE = 0.35f

    /** Por debajo de este valor el gatillo analógico se considera suelto. */
    private const val TRIGGER_THRESHOLD = 0.5f

    /**
     * Qué botones mantiene pulsados cada vía de entrada, como máscara de bits.
     *
     * Hace falta porque un mismo botón de la GBA puede llegar por dos sitios: el
     * hombro izquierdo, por ejemplo, si el mando lo declara como tecla digital
     * `KEYCODE_BUTTON_L1` y además trae eje de gatillo. Con una sola bandera por
     * botón, el evento de eje, que no lleva información sobre la tecla, pondría el
     * hombro a cero y el personaje dejaría de correr a media pulsación. Con dos
     * máscaras, un botón está pulsado mientras alguna de las dos vías lo mantenga.
     *
     * Los bits son los ordinales de [GbaButton], no las máscaras de tecla de la GBA:
     * aquí se indexa por botón y allí cada tecla pide su propio bit.
     */
    private val digitalHeld = AtomicInteger(0)
    private val analogHeld = AtomicInteger(0)

    /**
     * Deja el botón en el estado que resulte de unir las dos vías y lo escribe en
     * el núcleo.
     */
    private fun applyHeld(core: GBACore, button: GbaButton, digital: Boolean, analog: Boolean) {
        val bit = 1 shl button.ordinal
        val digitalMask = if (digital) digitalHeld.get() or bit else digitalHeld.get() and bit.inv()
        val analogMask = if (analog) analogHeld.get() or bit else analogHeld.get() and bit.inv()
        digitalHeld.set(digitalMask)
        analogHeld.set(analogMask)
        core.setKey(button.keyMask, (digitalMask or analogMask) and bit != 0)
    }

    /**
     * Aplica un evento de tecla. Devuelve `true` si venía de un mando y ya está
     * traducido, para que el llamante no se lo trague el sistema.
     *
     * Las teclas que la asignación declara huérfanas (BACK, MENU, el botón de inicio
     * del sistema) se dejan pasar al sistema en vez de tragárselas aquí: sin esto,
     * pulsar "atrás" con el mando no sacaría de la partida.
     */
    fun applyKeyEvent(core: GBACore, event: KeyEvent, mapping: GamepadMapping): Boolean {
        if (event.device?.isExternal == false) return false
        val button = mapping.buttonFor(event.keyCode) ?: return false
        applyHeld(core, button, event.action == KeyEvent.ACTION_DOWN, false)
        return true
    }

    /**
     * Suelta todos los botones.
     *
     * Se llama al salir de la partida y al cambiar la asignación: un botón que se
     * quedó pulsado dejaría al personaje andando solo, y un mapeo nuevo que ya no
     * incluye la tecla anterior la dejaría clavada.
     */
    fun releaseAll(core: GBACore) {
        digitalHeld.set(0)
        analogHeld.set(0)
        for (button in GbaButton.entries) {
            core.setKey(button.keyMask, false)
        }
    }

    /**
     * Aplica un evento de eje. Devuelve `true` si el evento llevaba direcciones
     * o gatillos y ya está traducido.
     */
    fun applyAxisEvent(core: GBACore, event: MotionEvent, mapping: GamepadMapping): Boolean {
        // `ACTION_SCROLL` es el nombre público del mismo valor 8 que Android
        // llama `ACTION_AXIS_EVENT`; el original vive en `InputEvent` y no está
        // en el SDK público. Es la acción que llega al mover palanca o gatillo.
        if (event.action != MotionEvent.ACTION_SCROLL) return false
        val device = event.device ?: return false
        var handled = false

        // L2 y R2 sólo llegan como tecla si el mando los declara digitales; en los
        // de gatillo analógico llegan como eje y, sin esto, L y R no harían nada.
        // Cada mando los nombra de una manera: PS4 usa BRAKE/GAS, el resto
        // LTRIGGER/RTRIGGER.
        if (mapping.useAnalogTriggers) {
            val leftTrigger = analogValue(event, device, MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE)
            val rightTrigger = analogValue(event, device, MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS)
            if (leftTrigger != null) {
                val pressed = leftTrigger > TRIGGER_THRESHOLD
                applyHeld(
                    core, GbaButton.L,
                    digital = digitalHeld.get() and (1 shl GbaButton.L.ordinal) != 0,
                    analog = pressed
                )
                handled = true
            }
            if (rightTrigger != null) {
                val pressed = rightTrigger > TRIGGER_THRESHOLD
                applyHeld(
                    core, GbaButton.R,
                    digital = digitalHeld.get() and (1 shl GbaButton.R.ordinal) != 0,
                    analog = pressed
                )
                handled = true
            }
        }

        // Sin ejes direccionales declarados el resto del evento no es una
        // palanca, y no hay cruceta que actualizar.
        if (mapping.useAnalogStick) {
            val declaresDirections = device.motionRanges.any {
                it.axis == MotionEvent.AXIS_X || it.axis == MotionEvent.AXIS_HAT_X ||
                    it.axis == MotionEvent.AXIS_Y || it.axis == MotionEvent.AXIS_HAT_Y
            }
            if (declaresDirections) {
                val x = strongest(event, MotionEvent.AXIS_X, MotionEvent.AXIS_HAT_X)
                val y = strongest(event, MotionEvent.AXIS_Y, MotionEvent.AXIS_HAT_Y)

                // Mismo cuidado que en los gatillos: la cruceta física puede estar
                // asignada mientras la palanca también incline esa dirección.
                for (button in GbaButton.entries) {
                    if (!button.isDirectional) continue
                    val pressed = when (button) {
                        GbaButton.LEFT  -> x < -STICK_DEADZONE
                        GbaButton.RIGHT -> x > STICK_DEADZONE
                        GbaButton.UP    -> y < -STICK_DEADZONE
                        else            -> y > STICK_DEADZONE
                    }
                    applyHeld(
                        core, button,
                        digital = digitalHeld.get() and (1 shl button.ordinal) != 0,
                        analog = pressed
                    )
                }
                handled = true
            }
        }
        return handled
    }

    /**
     * Mandos conectados ahora mismo, por nombre, para mostrarlos en la interfaz.
     *
     * Ni `getGamePads()` ni `getInputDevices()` sirven: están ocultos en el SDK. Los
     * públicos son `getDeviceIds()` y `getDevice(id)`.
     */
    fun connectedGamepads(): List<String> {
        val names = ArrayList<String>()
        // Bucle y no cadena de extensiones a propósito: el array de ids viene
        // como `IntArray` de Java y el encadenado se lee mejor así.
        for (id in InputDevice.getDeviceIds()) {
            val device = InputDevice.getDevice(id) ?: continue
            if (!device.isExternal) continue
            if (!device.supportsSource(InputDevice.SOURCE_GAMEPAD)) continue
            val name = device.name
            if (!name.isNullOrBlank() && !names.contains(name)) names.add(name)
        }
        return names
    }

    /** De varios ejes que describen la misma dirección, gana el de mayor valor. */
    private fun strongest(event: MotionEvent, vararg axes: Int): Float {
        var best = 0f
        for (axis in axes) {
            val value = event.getAxisValue(axis)
            if (abs(value) > abs(best)) best = value
        }
        return best
    }

    /** Valor del primer eje que el mando declare de verdad, o `null` si no lo tiene. */
    private fun analogValue(
        event: MotionEvent,
        device: InputDevice,
        vararg axes: Int
    ): Float? {
        for (axis in axes) {
            if (device.motionRanges.any { it.axis == axis }) return event.getAxisValue(axis)
        }
        return null
    }
}
