package com.example

import android.view.KeyEvent
import com.example.data.GbaButton
import com.example.data.GamepadMapping
import com.example.data.gbaKeyLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas del reasignado de teclas del mando.
 *
 * Lo que importa aquí no es que el mapa sea bonito, sino que la asignación nunca
 * deje al emulador en un estado imposible: una tecla en dos botones a la vez, o
 * un botón que se queda pulsado para siempre porque su tecla ya no está.
 *
 * Developed by Andrés Socorro.
 */
class GamepadMappingTest {

    @Test
    fun `el mapeo de fabrica manda BUTTON_A a A y BUTTON_X a B`() {
        val mapping = GamepadMapping()

        assertEquals(GbaButton.A, mapping.buttonFor(KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(GbaButton.B, mapping.buttonFor(KeyEvent.KEYCODE_BUTTON_B))
        // Mandos con etiquetas de Nintendo: el círculo llega como BUTTON_Y.
        assertEquals(GbaButton.A, mapping.buttonFor(KeyEvent.KEYCODE_BUTTON_Y))
        assertEquals(GbaButton.B, mapping.buttonFor(KeyEvent.KEYCODE_BUTTON_X))
    }

    @Test
    fun `cada tecla del mapeo de fabrica pertenece a un unico boton`() {
        val mapping = GamepadMapping()

        val duplicates = mapping.bindings.values.flatten().groupBy { it }
            .filterValues { it.size > 1 }

        assertTrue("Teclas asignadas a más de un botón: $duplicates", duplicates.isEmpty())
    }

    @Test
    fun `reasignar mueve la tecla y la quita del boton anterior`() {
        val mapping = GamepadMapping()

        // En el mando con la A y la B cruzadas, BUTTON_X es A y BUTTON_Y es B.
        val swapped = mapping
            .withAssignment(GbaButton.A, KeyEvent.KEYCODE_BUTTON_X)
            .withAssignment(GbaButton.B, KeyEvent.KEYCODE_BUTTON_Y)

        assertEquals(GbaButton.A, swapped.buttonFor(KeyEvent.KEYCODE_BUTTON_X))
        assertEquals(GbaButton.B, swapped.buttonFor(KeyEvent.KEYCODE_BUTTON_Y))
        // La tecla anterior de cada botón no debe quedar en ningún otro sitio,
        // o la misma pulsación movería los dos botones.
        assertNull(swapped.buttonFor(KeyEvent.KEYCODE_BUTTON_A))
        assertNull(swapped.buttonFor(KeyEvent.KEYCODE_BUTTON_B))
    }

    @Test
    fun `un boton sin asignar no se dispara nunca`() {
        val mapping = GamepadMapping().withAssignment(GbaButton.A, 0)

        assertEquals(emptyList<Int>(), mapping.keyCodesFor(GbaButton.A))
        assertEquals("Sin asignar", mapping.labelFor(GbaButton.A))
        assertNull(mapping.buttonFor(KeyEvent.KEYCODE_BUTTON_A))
    }

    @Test
    fun `el round trip por serializacion conserva el mapeo`() {
        val original = GamepadMapping()
            .withAssignment(GbaButton.L, KeyEvent.KEYCODE_BUTTON_THUMBL)
            .copy(useAnalogStick = false, useAnalogTriggers = true)

        val restored = GamepadMapping.deserialize(original.serialize())

        assertEquals(original.bindings, restored.bindings)
        assertFalse(restored.useAnalogStick)
        assertTrue(restored.useAnalogTriggers)
        assertEquals(
            KeyEvent.KEYCODE_BUTTON_THUMBL,
            restored.keyCodesFor(GbaButton.L).single()
        )
    }

    @Test
    fun `un boton vacio sobrevive al round trip`() {
        val original = GamepadMapping().withAssignment(GbaButton.B, 0)

        val restored = GamepadMapping.deserialize(original.serialize())

        // El caso límite del formato: un grupo vacío tiene que seguir leyendo
        // como "sin asignar", no como "no guardado", que restauraría el defecto.
        assertEquals(emptyList<Int>(), restored.keyCodesFor(GbaButton.B))
    }

    @Test
    fun `un mapeo corrupto cae en el de fabrica`() {
        for (raw in listOf("", "   ", "no-es-un-mapeo", "1;;broken")) {
            val mapping = GamepadMapping.deserialize(raw)
            assertEquals("\"$raw\" no debería cambiar nada", GbaButton.defaults(), mapping.bindings)
        }
    }

    @Test
    fun `isDefault distingue el mapeo intacto del customization`() {
        assertTrue(GamepadMapping().isDefault)
        assertFalse(GamepadMapping().withAssignment(GbaButton.A, KeyEvent.KEYCODE_BUTTON_X).isDefault)
        assertFalse(GamepadMapping(useAnalogStick = false).isDefault)
        assertTrue(GamepadMapping().withDefaults().isDefault)
    }

    @Test
    fun `un acorde asigna varias teclas al mismo boton`() {
        val mapping = GamepadMapping().withAssignment(
            GbaButton.L,
            listOf(KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_L2)
        )

        assertEquals(
            listOf(KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_L2),
            mapping.keyCodesFor(GbaButton.L)
        )
        // Las dos teclas siguen dando el mismo botón de la GBA.
        assertEquals(GbaButton.L, mapping.buttonFor(KeyEvent.KEYCODE_BUTTON_L1))
        assertEquals(GbaButton.L, mapping.buttonFor(KeyEvent.KEYCODE_BUTTON_L2))
    }

    @Test
    fun `asignar un acorde quita sus teclas de los demas botones`() {
        val mapping = GamepadMapping().withAssignment(
            GbaButton.A,
            listOf(KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_L2)
        )

        // Si L1 se quedara en Hombro L, apretarlo movería A y L a la vez.
        assertFalse(mapping.keyCodesFor(GbaButton.L).contains(KeyEvent.KEYCODE_BUTTON_L1))
        assertFalse(mapping.keyCodesFor(GbaButton.R).contains(KeyEvent.KEYCODE_BUTTON_L2))
        assertEquals(GbaButton.A, mapping.buttonFor(KeyEvent.KEYCODE_BUTTON_L1))
    }

    @Test
    fun `un acorde conserva el orden en que se pulsaron y descarta repeticiones`() {
        val mapping = GamepadMapping().withAssignment(
            GbaButton.R,
            listOf(
                KeyEvent.KEYCODE_BUTTON_R2,
                KeyEvent.KEYCODE_BUTTON_R1,
                KeyEvent.KEYCODE_BUTTON_R2,
                0,
                -7
            )
        )

        assertEquals(
            listOf(KeyEvent.KEYCODE_BUTTON_R2, KeyEvent.KEYCODE_BUTTON_R1),
            mapping.keyCodesFor(GbaButton.R)
        )
    }

    @Test
    fun `un acorde sobrevive a guardar y restaurar`() {
        val mapping = GamepadMapping().withAssignment(
            GbaButton.L,
            listOf(KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_L2)
        )

        val restored = GamepadMapping.deserialize(mapping.serialize())

        assertEquals(
            listOf(KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_L2),
            restored.keyCodesFor(GbaButton.L)
        )
    }

    @Test
    fun `un acorde vacio desasigna el boton`() {
        val mapping = GamepadMapping().withAssignment(GbaButton.A, emptyList())

        assertEquals(emptyList<Int>(), mapping.keyCodesFor(GbaButton.A))
        assertEquals("Sin asignar", mapping.labelFor(GbaButton.A))
        assertNull(mapping.buttonFor(KeyEvent.KEYCODE_BUTTON_A))
    }

    @Test
    fun `las etiquetas de tecla no exponen el prefijo tecnico`() {
        assertFalse(gbaKeyLabel(KeyEvent.KEYCODE_BUTTON_L1).startsWith("KEYCODE"))
        assertEquals("Sin asignar", gbaKeyLabel(0))
    }
}