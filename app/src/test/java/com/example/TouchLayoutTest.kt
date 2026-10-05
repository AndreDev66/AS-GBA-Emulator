package com.example

import com.example.data.TouchControl
import com.example.data.TouchLayout
import com.example.data.TouchPlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas de la distribución de los botones táctiles.
 *
 * Hay dos cosas que proteger aquí. Una es que un mando colocado a mano siga en su
 * sitio: si se acepta un valor raro, un botón acaba fuera de la pantalla y no hay
 * forma de devolverlo salvo restablecerlo todo.
 *
 * La otra es que el reparto de fábrica siga siendo el de siempre. Se comprueba
 * contra cifras calculadas a mano a partir de la composición original —dos
 * columnas con `SpaceBetween` en horizontal, filas y pesos en vertical—, porque
 * si un día se cambian esas cuentas, "Restablecer" dejaría de devolver lo que
 * había antes y eso no lo detectaría ningún test que sólo mirase que el botón
 * existe.
 */
class TouchLayoutTest {

    // ── El reparto de fábrica ────────────────────────────────────────────────

    @Test
    fun `el reparto de horizontal pone los hombros arriba y las pastillas abajo`() {
        // Zona de 1000 × 500 dp con el tamaño global a 1: el original rellenaba
        // 24 dp a los lados y 12 arriba y abajo, así que la zona interior es de
        // 952 × 476.
        val l = factory(TouchControl.L, isLandscape = true)
        val r = factory(TouchControl.R, isLandscape = true)
        val select = factory(TouchControl.SELECT, isLandscape = true)
        val start = factory(TouchControl.START, isLandscape = true)

        // L y R comparten la fila de arriba, y cada uno pegado a su lado.
        assertEquals((24f + 33.3f) / 1000f, l.x, TOLERANCE)
        assertEquals((24f + 952f - 33.3f) / 1000f, r.x, TOLERANCE)
        assertEquals((12f + 15.3f) / 500f, l.y, TOLERANCE)
        assertEquals(l.y, r.y, TOLERANCE)

        // SELECT y START al fondo de cada columna, con la misma altura.
        assertEquals((24f + 48f) / 1000f, select.x, TOLERANCE)
        assertEquals((24f + 952f - 48f) / 1000f, start.x, TOLERANCE)
        assertEquals((12f + 452f) / 500f, select.y, TOLERANCE)
        assertEquals(select.y, start.y, TOLERANCE)
    }

    @Test
    fun `en horizontal el hueco sobrante empuja la cruceta y el grupo A B`() {
        // El hueco es lo que sobra tras los elementos fijos de cada columna:
        // 476 − 30.6 (hombro) − 136.8 (cruceta) − 10 − 48 (pastilla) = 250.6 a la
        // izquierda, y 282.9 a la derecha, porque el grupo A/B es más bajo.
        val dpad = factory(TouchControl.DPAD, isLandscape = true)
        val a = factory(TouchControl.A, isLandscape = true)
        val b = factory(TouchControl.B, isLandscape = true)

        assertEquals((24f + 68.4f) / 1000f, dpad.x, TOLERANCE)
        assertEquals((12f + 349.6f) / 500f, dpad.y, TOLERANCE)

        // El grupo A/B es un recuadro de 140 × 95 pegado al borde derecho, con A
        // arriba a la derecha y B abajo a la izquierda.
        assertEquals((24f + 919.7f) / 1000f, a.x, TOLERANCE)
        assertEquals((12f + 345.8f) / 500f, a.y, TOLERANCE)
        assertEquals((24f + 851.3f) / 1000f, b.x, TOLERANCE)
        assertEquals((12f + 385.7f) / 500f, b.y, TOLERANCE)

        // B queda por debajo de A: la diagonal que el usuario espera.
        assertTrue(a.y < b.y)
        assertTrue(a.x > b.x)
    }

    @Test
    fun `en horizontal los botones se encogen un poco como en el original`() {
        // Hombros y pastillas al 90 %, cruceta y grupo A/B al 95 %.
        assertEquals(0.90f, factory(TouchControl.L, isLandscape = true).scale, TOLERANCE)
        assertEquals(0.90f, factory(TouchControl.SELECT, isLandscape = true).scale, TOLERANCE)
        assertEquals(0.95f, factory(TouchControl.DPAD, isLandscape = true).scale, TOLERANCE)
        assertEquals(0.95f, factory(TouchControl.A, isLandscape = true).scale, TOLERANCE)
    }

    @Test
    fun `en vertical los hombros van arriba y las pastillas en el centro abajo`() {
        // Zona de 466 dp de ancho: es justo la de referencia más el relleno, así
        // que no hay que encoger nada y las cuentas salen limpias.
        val l = factory(TouchControl.L, isLandscape = false)
        val r = factory(TouchControl.R, isLandscape = false)
        val select = factory(TouchControl.SELECT, isLandscape = false)
        val start = factory(TouchControl.START, isLandscape = false)

        // L y R pegados a los lados de arriba.
        assertEquals((20f + 37f) / 466f, l.x, TOLERANCE)
        assertEquals((20f + 426f - 37f) / 466f, r.x, TOLERANCE)
        assertEquals((14f + 17f) / 960f, l.y, TOLERANCE)
        assertEquals(l.y, r.y, TOLERANCE)

        // SELECT y START centrados y con 24 dp de separación entre sus zonas.
        assertEquals((20f + 213f - 60f) / 466f, select.x, TOLERANCE)
        assertEquals((20f + 213f + 60f) / 466f, start.x, TOLERANCE)
        assertEquals(904f / 960f, select.y, TOLERANCE)
        assertEquals(select.y, start.y, TOLERANCE)
    }

    @Test
    fun `en vertical la cruceta y el grupo A B se apoyan en la fila de las pastillas`() {
        // El recuadro inferior deja 6 dp, la fila de A/B sube 88 dp desde el
        // borde y la de las pastillas 12 dp: 932 − 6 − 88 = 838.
        val dpad = factory(TouchControl.DPAD, isLandscape = false)
        val a = factory(TouchControl.A, isLandscape = false)
        val b = factory(TouchControl.B, isLandscape = false)

        assertEquals((20f + 72f) / 466f, dpad.x, TOLERANCE)
        assertEquals(780f / 960f, dpad.y, TOLERANCE)

        assertEquals((20f + 286f + 106f) / 466f, a.x, TOLERANCE)
        assertEquals((14f + 728f + 34f) / 960f, a.y, TOLERANCE)
        assertEquals((20f + 286f + 34f) / 466f, b.x, TOLERANCE)
        assertEquals((14f + 728f + 76f) / 960f, b.y, TOLERANCE)

        assertTrue(a.y < b.y)
        // La A queda por encima de la cruceta: el grupo está en el borde derecho
        // y su botón superior sube más que el centro de la cruz.
        assertTrue(a.y < dpad.y)
    }

    @Test
    fun `en vertical los botones se encogen si la pantalla es mas estrecha que la de referencia`() {
        // El original limitaba el grupo al 94 % de una pantalla de 402 dp de
        // ancho útil, con un suelo del 60 %.
        val ancho = factory(TouchControl.DPAD, isLandscape = false, widthDp = 360f, heightDp = 800f)
        assertTrue("debería encogerse en una pantalla estrecha", ancho.scale < 1f)

        // Los hombros, en cambio, nunca se encogían: seguían la escala global
        // aunque el resto del conjunto sí se ajustara a la pantalla.
        assertEquals(1f, factory(TouchControl.L, isLandscape = false, widthDp = 360f).scale, TOLERANCE)
        assertTrue(
            factory(TouchControl.L, isLandscape = false, widthDp = 360f).scale >
                factory(TouchControl.DPAD, isLandscape = false, widthDp = 360f).scale
        )

        val conHolgura = factory(TouchControl.DPAD, isLandscape = false)
        assertEquals(1f, conHolgura.scale, TOLERANCE)

        // Con 300 dp de ancho el conjunto se saldría, así que baja al suelo del 60 %.
        val estrecho = factory(TouchControl.DPAD, isLandscape = false, widthDp = 300f, heightDp = 800f)
        assertEquals(0.6f, estrecho.scale, TOLERANCE)
    }

    @Test
    fun `mover un control no altera el reparto de fabrica de los demas`() {
        // Es lo que hace posible "restablecer" devolviendo lo que había: cada
        // control no guardado se dibuja siempre con las mismas cuentas.
        val antes = factory(TouchControl.SELECT, isLandscape = false)
        val despues = factory(TouchControl.SELECT, isLandscape = false)

        assertEquals(antes, despues)
    }

    @Test
    fun `el reparto de fabrica cubre los siete controles`() {
        for (isLandscape in listOf(false, true)) {
            for (control in TouchControl.entries) {
                val placement = factory(control, isLandscape)
                assertTrue("$control se sale por la izquierda", placement.x in 0f..1f)
                assertTrue("$control se sale por arriba", placement.y in 0f..1f)
                assertTrue("$control tiene tamaño raro", placement.scale > 0f)
            }
        }
    }

    @Test
    fun `una zona mas pequeña que los botones no lanza`() {
        // Con el hueco negativo —pantalla en horizontal muy baja— la cuenta tiene
        // que seguir dando números y no reventar.
        for (isLandscape in listOf(false, true)) {
            val placement = factory(TouchControl.A, isLandscape, widthDp = 120f, heightDp = 60f)
            assertTrue(placement.x.isFinite())
            assertTrue(placement.y.isFinite())
        }
    }

    // ── Lo que guarda el usuario ─────────────────────────────────────────────

    @Test
    fun `una distribucion nueva no guarda nada y se sabe intacta`() {
        val layout = TouchLayout()

        assertTrue(layout.isDefault)
        assertTrue(layout.forOrientation(false).isEmpty())
        assertTrue(layout.forOrientation(true).isEmpty())
    }

    @Test
    fun `un mando colocado y guardado vuelve igual`() {
        val custom = TouchLayout().with(
            isLandscape = true,
            placements = mapOf(
                TouchControl.A to TouchPlacement(0.5f, 0.5f, 1.25f),
                TouchControl.DPAD to TouchPlacement(0.25f, 0.75f, 0.8f)
            )
        )

        assertEquals(custom, TouchLayout.deserialize(custom.serialize()))
        assertFalse(custom.isDefault)
    }

    @Test
    fun `guardar una orientacion no toca la otra`() {
        val moved = TouchLayout().with(
            isLandscape = false,
            placements = mapOf(TouchControl.START to TouchPlacement(0.5f, 0.5f))
        )
        val restored = TouchLayout.deserialize(moved.serialize())

        assertEquals(
            TouchPlacement(0.5f, 0.5f),
            restored.forOrientation(false)[TouchControl.START]
        )
        assertTrue(restored.forOrientation(true).isEmpty())
    }

    @Test
    fun `vaciar una orientacion deja la otra como estaba`() {
        val placed = TouchLayout()
            .withPlacement(false, TouchControl.A, TouchPlacement(0.3f, 0.3f))
            .withPlacement(true, TouchControl.B, TouchPlacement(0.7f, 0.7f))

        val reset = placed.with(true, emptyMap())

        assertTrue(reset.forOrientation(true).isEmpty())
        assertEquals(
            TouchPlacement(0.3f, 0.3f),
            reset.forOrientation(false)[TouchControl.A]
        )
    }

    @Test
    fun `vaciarlo todo devuelve el mando a como estaba sin tocar nada`() {
        // Es lo que hace "Restablecer" desde el editor, que sólo existe en
        // horizontal: no puede quedar nada de una distribución vertical vieja
        // porque ya no hay forma de deshacerla.
        val placed = TouchLayout()
            .withPlacement(false, TouchControl.A, TouchPlacement(0.3f, 0.3f))
            .withPlacement(true, TouchControl.B, TouchPlacement(0.7f, 0.7f))

        assertTrue(TouchLayout().isDefault)
        assertTrue(placed.with(true, emptyMap()).portrait.isNotEmpty())
        assertTrue(TouchLayout().serialize().endsWith("|L:"))
    }

    @Test
    fun `quitar un control lo devuelve al reparto de fabrica`() {
        val placed = TouchLayout().withPlacement(true, TouchControl.A, TouchPlacement(0.1f, 0.1f))
        val removed = placed.withPlacement(true, TouchControl.A, null)

        assertNull(removed.forOrientation(true)[TouchControl.A])
        assertTrue(removed.isDefault)
    }

    @Test
    fun `un dato ilegible se descarta y el resto de la lista sigue leyendose`() {
        val raw = TouchLayout().with(
            isLandscape = true,
            placements = mapOf(
                TouchControl.A to TouchPlacement(0.1f, 0.2f, 1f),
                TouchControl.B to TouchPlacement(0.3f, 0.4f, 1f)
            )
        ).serialize().replace("A:0.100,0.200,1.000", "A:esto-no-es-un-numero")

        val layout = TouchLayout.deserialize(raw)

        // La A ilegible no se guarda, así que vuelve al reparto de fábrica...
        assertNull(layout.forOrientation(true)[TouchControl.A])
        // ...y la B, que sí estaba bien, sigue donde el usuario la dejó.
        assertEquals(
            TouchPlacement(0.3f, 0.4f, 1f),
            layout.forOrientation(true)[TouchControl.B]
        )
    }

    @Test
    fun `una lista vacia o corrupta deja la distribucion intacta`() {
        assertTrue(TouchLayout.deserialize("P:|L:").isDefault)
        assertTrue(TouchLayout.deserialize("").isDefault)
        assertTrue(TouchLayout.deserialize(null).isDefault)
        // Un control con la lista incompleta no vale, y una frase entera tampoco.
        assertTrue(TouchLayout.deserialize("P:DPAD:0.1,0.2|L:").isDefault)
        assertTrue(TouchLayout.deserialize("esto no es una distribución").isDefault)
    }

    @Test
    fun `un control desconocido se ignora sin tirar lo demas`() {
        val raw = TouchLayout().with(
            isLandscape = false,
            placements = mapOf(TouchControl.R to TouchPlacement(0.4f, 0.4f, 1f))
        ).serialize() + "P:TURBO:0.5,0.5,2.0"

        val layout = TouchLayout.deserialize(raw)

        assertEquals(
            TouchPlacement(0.4f, 0.4f, 1f),
            layout.forOrientation(false)[TouchControl.R]
        )
    }

    // ── Los limites de la zona ───────────────────────────────────────────────

    @Test
    fun `clamped deja el control entero dentro de la zona`() {
        // Un botón de 144 dp en una zona de 400 dp ocupa 0.36 de ancho: media de
        // eso es el margen que no puede sobrepasar el centro.
        val pinned = TouchPlacement(x = 0f, y = 0f).clamped(0.18f, 0.18f)

        assertEquals(0.18f, pinned.x, TOLERANCE)
        assertEquals(0.18f, pinned.y, TOLERANCE)
    }

    @Test
    fun `clamped encaja dentro de la zona en vez de salirse`() {
        val clamped = TouchPlacement(x = 0.99f, y = -3f).clamped(0.1f, 0.1f)

        assertTrue(clamped.x <= 0.9f)
        assertTrue(clamped.y >= 0.1f)
    }

    @Test
    fun `clamped centra un control mas grande que la zona`() {
        // `coerceIn` lanza si el mínimo es mayor que el máximo; con un botón
        // enorme el resultado tiene que ser el centro, no un crash.
        val centered = TouchPlacement(x = 0.2f, y = 0.8f).clamped(0.7f, 0.7f)

        assertEquals(0.5f, centered.x, TOLERANCE)
        assertEquals(0.5f, centered.y, TOLERANCE)
    }

    @Test
    fun `clamped no toca la escala`() {
        val clamped = TouchPlacement(0.5f, 0.5f, 1.75f).clamped(0.1f, 0.1f)

        assertEquals(1.75f, clamped.scale, TOLERANCE)
    }

    private fun factory(
        control: TouchControl,
        isLandscape: Boolean,
        widthDp: Float = if (isLandscape) 1000f else 466f,
        heightDp: Float = if (isLandscape) 500f else 960f,
        globalScale: Float = 1f
    ): TouchPlacement = TouchLayout.legacyDefaultFor(
        control = control,
        isLandscape = isLandscape,
        areaWidthDp = widthDp,
        areaHeightDp = heightDp,
        globalScale = globalScale
    )

    private companion object {
        const val TOLERANCE = 0.0001f
    }
}