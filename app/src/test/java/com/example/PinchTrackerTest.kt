package com.example

import com.example.data.TouchControl
import com.example.ui.components.PinchTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El acumulador de zoom del pellizco de edición.
 *
 * El síntoma que se persigue: al pellizcar un botón hasta hacerlo grande, a
 * veces volvía de golpe al tamaño original. Pasaba cuando un dedo se levantaba
 * y volvía a entrar, o el sistema lo reenganchaba, a mitad de gesto: el zoom de
 * ese evento mezcla posiciones de dedos viejos y nuevos y, multiplicado contra
 * la base del principio, colapsaba el tamaño hacia el de antes. Con el
 * re-anclaje, esa transición no produce tamaño y el siguiente evento sigue
 * desde lo dibujado.
 */
class PinchTrackerTest {

    @Test
    fun `con los mismos dedos acumula el zoom`() {
        val t = PinchTracker(0.45f, 2.6f)
        t.anchor(TouchControl.A, 1f, setOf(1L, 2L))

        assertEquals(1.2f, t.event(1.2f), 0.0001f)
        assertEquals(1.44f, t.event(1.2f), 0.0001f)
    }

    @Test
    fun `un dedo que sale y vuelve sigue desde lo dibujado, no desde la base vieja`() {
        val t = PinchTracker(0.45f, 2.6f)
        t.anchor(TouchControl.A, 1f, setOf(1L, 2L))
        // El pellizco ya ha agrandado hasta 2x.
        assertEquals(2.0f, t.event(2.0f), 0.0001f)

        // Se levanta un dedo: se espera, sin cancelar.
        t.singleFinger()

        // El dedo vuelve: el gesto re-ancla sobre lo dibujado (2.0). A partir
        // de ahí se sigue multiplicando desde 2.0, nunca desde la base vieja 1.0.
        t.reanchor(2.0f, setOf(1L, 2L))
        assertEquals(2.4f, t.event(1.2f), 0.0001f)
    }

    @Test
    fun `un dedo nuevo con el viejo dispara el re-anclaje y no retrocede`() {
        val t = PinchTracker(0.45f, 2.6f)
        t.anchor(TouchControl.A, 1f, setOf(1L, 2L))
        assertEquals(2.0f, t.event(2.0f), 0.0001f)

        // Entra otro dedo (o el sistema reengancha el mismo con otro id).
        assertFalse(t.sameFingers(setOf(1L, 3L)))
        // El gesto no pellizca la transición: re-ancla sobre lo dibujado.
        t.anchor(TouchControl.A, 2.0f, setOf(1L, 3L))
        assertEquals(2.4f, t.event(1.2f), 0.0001f)
    }

    @Test
    fun `un dedo en el aire se nota como cambio de dedos`() {
        val t = PinchTracker(0.45f, 2.6f)
        t.anchor(TouchControl.A, 1f, setOf(1L, 2L))
        assertTrue(t.sameFingers(setOf(1L, 2L)))

        t.singleFinger()

        assertFalse(t.sameFingers(setOf(1L, 2L)))
    }

    @Test
    fun `el tamaño queda acotado entre el suelo y el techo`() {
        val t = PinchTracker(0.45f, 2.6f)
        t.anchor(TouchControl.B, 1f, setOf(1L, 2L))

        assertEquals(2.6f, t.event(9f), 0.0001f)
        t.reanchor(2.6f, setOf(1L, 2L))
        assertEquals(0.45f, t.event(0.001f), 0.0001f)
    }

    @Test
    fun `al terminar se olvida el objetivo`() {
        val t = PinchTracker(0.45f, 2.6f)
        t.anchor(TouchControl.START, 1f, setOf(1L, 2L))

        t.end()

        assertNull(t.target)
    }

    @Test
    fun `el objetivo cambia al re-anclar con otro control`() {
        val t = PinchTracker(0.45f, 2.6f)
        t.anchor(TouchControl.A, 1f, setOf(1L, 2L))

        t.anchor(TouchControl.B, 1.5f, setOf(1L, 3L))

        assertEquals(TouchControl.B, t.target)
        assertEquals(1.8f, t.event(1.2f), 0.0001f)
    }
}