package com.example

import com.example.core.cheats.CheatEntry
import com.example.core.cheats.CheatParser
import com.example.core.cheats.CheatStorageCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas del formato de guardado de trucos.
 *
 * El formato es texto plano a propósito, así que estas pruebas comprueban
 * sobre todo que un archivo escrito se vuelve a leer igual y que un archivo
 * raro no revienta la app al abrir la lista.
 */
class CheatStorageCodecTest {

    private fun entry(
        id: String = "new0",
        name: String = "Vida infinita",
        enabled: Boolean = true,
        lines: List<String> = listOf("82003ABC 0064"),
    ) = CheatEntry(id, name, enabled, lines)

    @Test
    fun `una lista vacia se guarda como texto vacio`() {
        assertEquals("", CheatStorageCodec.encode(emptyList()))
        assertTrue(CheatStorageCodec.decode("").isEmpty())
    }

    @Test
    fun `un truco solo conserva nombre, marca y lineas`() {
        val entries = listOf(entry(enabled = false, lines = listOf("82003ABC 0064", "82003ABE 0064")))
        val decoded = CheatStorageCodec.decode(CheatStorageCodec.encode(entries))

        assertEquals(1, decoded.size)
        assertEquals("Vida infinita", decoded[0].name)
        assertFalse(decoded[0].enabled)
        assertEquals(listOf("82003ABC 0064", "82003ABE 0064"), decoded[0].lines)
    }

    @Test
    fun `varios trucos se guardan en orden`() {
        val entries = listOf(
            entry(id = "new0", name = "Vida infinita"),
            entry(id = "new1", name = "Munición", enabled = false),
            entry(id = "new2", name = "Sin fondo"),
        )
        val decoded = CheatStorageCodec.decode(CheatStorageCodec.encode(entries))

        assertEquals(listOf("Vida infinita", "Munición", "Sin fondo"), decoded.map { it.name })
        assertEquals(listOf(true, false, true), decoded.map { it.enabled })
    }

    @Test
    fun `el texto guardado es legible a mano`() {
        val text = CheatStorageCodec.encode(
            listOf(entry(enabled = false, lines = listOf("82003ABC 0064")))
        )

        assertEquals("0\nVida infinita\n82003ABC 0064", text)
    }

    @Test
    fun `un nombre con salto de linea se aplana`() {
        // Si no, rompería el formato: el nombre ocupa una sola línea.
        val decoded = CheatStorageCodec.decode(
            CheatStorageCodec.encode(listOf(entry(name = "Vida\ninfinita")))
        )

        assertEquals(1, decoded.size)
        assertEquals("Vida infinita", decoded[0].name)
    }

    @Test
    fun `un bloque sin marca se descarta sin romper el resto`() {
        // Una marca que no sea 0 ni 1: no se sabe si el truco iba activo, así
        // que se descarta el bloque en vez de adivinarlo.
        val text = listOf(
            "X",
            "Marca inválida",
            "82003ABC 0064",
            "",
            "1",
            "Con marca",
            "82003ABC 0064",
        ).joinToString("\n")

        val decoded = CheatStorageCodec.decode(text)

        assertEquals(1, decoded.size)
        assertEquals("Con marca", decoded[0].name)
    }

    @Test
    fun `una linea en blanco dentro del codigo no parte el bloque`() {
        // El separador de bloques es una línea en blanco, así que al guardar se
        // descartan: el pegado de la wiki las trae entre códigos.
        val decoded = CheatStorageCodec.decode(
            CheatStorageCodec.encode(
                listOf(entry(lines = listOf("82003ABC 0064", "", "82003ABE 0064")))
            )
        )

        assertEquals(1, decoded.size)
        assertEquals(listOf("82003ABC 0064", "82003ABE 0064"), decoded[0].lines)
    }

    @Test
    fun `un bloque sin nombre se descarta`() {
        val decoded = CheatStorageCodec.decode(
            listOf("1", "", "82003ABC 0064", "", "1", "Bien", "82003ABC 0064").joinToString("\n")
        )

        assertEquals(1, decoded.size)
        assertEquals("Bien", decoded[0].name)
    }

    @Test
    fun `un truco sin lineas de codigo se conserva`() {
        // Puede ser un código recién creado y todavía sin pegar; borrarlo al
        // recargar sería desconcertante.
        val decoded = CheatStorageCodec.decode(CheatStorageCodec.encode(listOf(entry(lines = emptyList()))))

        assertEquals(1, decoded.size)
        assertTrue(decoded[0].lines.isEmpty())
    }

    @Test
    fun `los identificadores reconstruidos no chocan con los nuevos`() {
        val decoded = CheatStorageCodec.decode(
            CheatStorageCodec.encode(listOf(entry(id = "new7"), entry(id = "new8", name = "Otro")))
        )

        // Tras recargar, los identificadores pasan a ser los de almacenamiento y
        // el siguiente truco nuevo no puede duplicar ninguno de ellos.
        val nextId = CheatStorageCodec.newId(decoded)

        assertTrue(decoded.none { it.id == nextId })
    }

    @Test
    fun `newId sigue la numeracion y no reinicia`() {
        val existing = listOf(entry(id = "new0"), entry(id = "new1"), entry(id = "saved0"))

        assertEquals("new2", CheatStorageCodec.newId(existing))
    }

    @Test
    fun `el truco guardado se puede volver a analizar`() {
        // Ida y vuelta completa: guardar, cargar y pasar el código por el
        // decodificador debe dar la misma operación que antes.
        val source = listOf(entry(lines = listOf("82003ABC 0064", "72001000 0002")))
        val decoded = CheatStorageCodec.decode(CheatStorageCodec.encode(source))

        val result = CheatParser.parse(decoded.single().source)

        assertTrue(result.errors.toString(), result.accepted)
        assertEquals(2, result.ops.size)
        assertEquals(0x02003ABC, result.ops[0].address)
    }

    @Test
    fun `source devuelve las lineas listas para analizar`() {
        assertEquals(
            "82003ABC 0064\n82003ABE 0064",
            entry(lines = listOf("82003ABC 0064", "82003ABE 0064")).source,
        )
    }
}
