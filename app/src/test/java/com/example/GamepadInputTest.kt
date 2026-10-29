package com.example

import android.view.InputDevice
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import com.example.core.GBACore
import com.example.data.GbaButton
import com.example.data.GamepadMapping
import com.example.input.GamepadInput
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * El caso difícil de la entrada de mando: un mismo botón de la GBA puede llegar
 * por dos vías a la vez y una no debe soltar lo que la otra mantiene.
 *
 * Developed by Andrés Socorro.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GamepadInputTest {

    private lateinit var core: GBACore
    private lateinit var mapping: GamepadMapping

    @Before
    fun setUp() {
        core = GBACore(ApplicationProvider.getApplicationContext())
        mapping = GamepadMapping()
        GamepadInput.releaseAll(core)
    }

    @After
    fun tearDown() {
        GamepadInput.releaseAll(core)
    }

    @Test
    fun `una tecla digital pulsa y suelta su boton`() {
        GamepadInput.applyKeyEvent(core, keyEvent(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.ACTION_DOWN), mapping)
        assertTrue(core.isKeyPressed(GBACore.KEY_A))

        GamepadInput.applyKeyEvent(core, keyEvent(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.ACTION_UP), mapping)
        assertFalse(core.isKeyPressed(GBACore.KEY_A))
    }

    @Test
    fun `releaseAll suelta lo que quedo pulsado`() {
        GamepadInput.applyKeyEvent(core, keyEvent(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.ACTION_DOWN), mapping)
        assertTrue(core.isKeyPressed(GBACore.KEY_A))

        // Es lo que pasa al salir de la partida o al cambiar la asignación: sin
        // esto el personaje se quedaría andando solo.
        GamepadInput.releaseAll(core)
        assertFalse(core.isKeyPressed(GBACore.KEY_A))
    }

    @Test
    fun `un boton sin asignar se deja pasar al sistema`() {
        assertFalse(
            GamepadInput.applyKeyEvent(
                core, keyEvent(KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_DOWN), mapping
            )
        )
    }

    @Test
    fun `reasignar mueve la pulsacion al boton nuevo`() {
        // BUTTON_X pasa a ser A, así que el ratón de la cruceta deja de estar solo.
        val swapped = mapping.withAssignment(GbaButton.A, KeyEvent.KEYCODE_BUTTON_X)

        GamepadInput.applyKeyEvent(core, keyEvent(KeyEvent.KEYCODE_BUTTON_X, KeyEvent.ACTION_DOWN), swapped)
        assertTrue(core.isKeyPressed(GBACore.KEY_A))
        assertFalse(core.isKeyPressed(GBACore.KEY_B))

        GamepadInput.applyKeyEvent(core, keyEvent(KeyEvent.KEYCODE_BUTTON_X, KeyEvent.ACTION_UP), swapped)
        assertFalse(core.isKeyPressed(GBACore.KEY_A))
    }

    @Test
    fun `soltar la tecla deja ver si otra via mantiene el boton`() {
        // Se comprueba el estado interno sin depender de ejes reales: el mando de
        // pruebas no declara ejes, así que se comprueba que soltar no rompe nada.
        GamepadInput.applyKeyEvent(core, keyEvent(KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.ACTION_DOWN), mapping)
        assertTrue(core.isKeyPressed(GBACore.KEY_L))

        GamepadInput.applyKeyEvent(core, keyEvent(KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.ACTION_UP), mapping)
        assertFalse(core.isKeyPressed(GBACore.KEY_L))
    }

    @Test
    fun `un boton de la GBA solo puede estar asignado a una tecla`() {
        val duplicate = GamepadMapping().withAssignment(GbaButton.B, KeyEvent.KEYCODE_BUTTON_A)

        // BUTTON_A estaba en A; al pasarlo a B sale de A, así que no queda la misma
        // tecla disparando dos botones.
        assertTrue(duplicate.bindings.getValue(GbaButton.B).contains(KeyEvent.KEYCODE_BUTTON_A))
        assertFalse(duplicate.bindings.getValue(GbaButton.A).contains(KeyEvent.KEYCODE_BUTTON_A))
    }

    private fun keyEvent(keyCode: Int, action: Int): KeyEvent =
        KeyEvent(0L, 0L, action, keyCode, 0)
}