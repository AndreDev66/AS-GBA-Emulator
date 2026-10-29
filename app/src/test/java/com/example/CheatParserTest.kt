package com.example

import com.example.core.cheats.CheatKind
import com.example.core.cheats.CheatOp
import com.example.core.cheats.CheatParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas del decodificador de códigos.
 *
 * Los códigos de ejemplo no son de ningún juego: se construyen para caer en un
 * camino concreto del decodificador y poder comprobar la operación exacta que
 * sale. Los vectores cifrados se generaron cifrando con TEA las semillas
 * públicas de GameShark los códigos en claro que dicen descifrar, de modo que
 * se ejercita también el camino de descifrado.
 *
 * Ojo con el formato: `XXXXXXXX XXXX` son 8 + 4 dígitos hexadecimales, así que
 * la dirección ocupa 7 dígitos más el nibble de tipo. Una dirección escrita con
 * 8 dígitos se desborda a la región 0 y el núcleo la trataría como BIOS.
 */
class CheatParserTest {

    // --- CodeBreaker ---------------------------------------------------

    @Test
    fun `escritura de 16 bits de CodeBreaker`() {
        // Tipo 8 = CB_ASSIGN_2 sobre 0x02003ABC.
        val result = CheatParser.parse("82003ABC 0064")

        assertTrue(result.errors.toString(), result.accepted)
        assertEquals(listOf(CheatOp(0x02003ABC, 0x0064, CheatKind.WRITE, 2, 0)), result.ops)
    }

    @Test
    fun `escritura de 8 bits de CodeBreaker`() {
        // Tipo 3 = CB_ASSIGN_1.
        val result = CheatParser.parse("32001E00 00FF")

        assertTrue(result.errors.toString(), result.accepted)
        assertEquals(listOf(CheatOp(0x02001E00, 0x00FF, CheatKind.WRITE, 1, 0)), result.ops)
    }

    @Test
    fun `OR y AND de CodeBreaker se distinguen de una escritura`() {
        val result = CheatParser.parse(
            """
            22004000 0100
            62004002 8000
            """.trimIndent()
        )

        assertTrue(result.errors.toString(), result.accepted)
        assertEquals(
            listOf(
                CheatOp(0x02004000, 0x0100, CheatKind.OR, 2, 0),
                CheatOp(0x02004002, 0x8000, CheatKind.AND, 2, 0),
            ),
            result.ops,
        )
    }

    @Test
    fun `ADD de CodeBreaker`() {
        // Tipo E = CB_ADD_2.
        val result = CheatParser.parse("E2005000 0002")

        assertEquals(listOf(CheatOp(0x02005000, 0x0002, CheatKind.ADD, 2, 0)), result.ops)
    }

    @Test
    fun `cada condicion de CodeBreaker salta una operacion`() {
        // Los tipos 7/A/B/C/F son EQ/NE/GT/LT/AND sobre 16 bits.
        val result = CheatParser.parse(
            """
            72000100 0001
            A2000200 0002
            B2000300 0003
            C2000400 0004
            F2000500 0005
            """.trimIndent()
        )

        assertTrue(result.errors.toString(), result.accepted)
        assertEquals(
            listOf(
                CheatOp(0x02000100, 0x0001, CheatKind.IF_EQ, 2, 1),
                CheatOp(0x02000200, 0x0002, CheatKind.IF_NE, 2, 1),
                CheatOp(0x02000300, 0x0003, CheatKind.IF_GT, 2, 1),
                CheatOp(0x02000400, 0x0004, CheatKind.IF_LT, 2, 1),
                CheatOp(0x02000500, 0x0005, CheatKind.IF_AND, 2, 1),
            ),
            result.ops,
        )
    }

    @Test
    fun `el codigo de boton de CodeBreaker apunta a KEYINPUT con NAND`() {
        // Tipo D = CB_IF_SPECIAL; el operando 0x20 es el único caso conocido.
        val result = CheatParser.parse("D0000020 0300")

        assertTrue(result.errors.toString(), result.accepted)
        assertEquals(listOf(CheatOp(0x04000130, 0x0300, CheatKind.IF_NAND, 2, 1)), result.ops)
    }

    @Test
    fun `una condicion especial desconocida se omite con aviso`() {
        val result = CheatParser.parse("D0123456 0300")

        assertTrue(result.errors.toString(), result.accepted)
        assertTrue(result.ops.isEmpty())
        assertEquals(1, result.warnings.size)
    }

    @Test
    fun `el identificador de juego y el hook de CodeBreaker se omiten con aviso`() {
        // Tipos 0 y 1: no escriben memoria, así que no pueden quedar "a medias".
        val result = CheatParser.parse(
            """
            0D1B56E2 1CE0
            1D1B56E2 1CE0
            """.trimIndent()
        )

        assertTrue(result.errors.toString(), result.accepted)
        assertTrue(result.ops.isEmpty())
        assertEquals(2, result.warnings.size)
    }

    @Test
    fun `una escritura en IO se omite con aviso`() {
        // 0x0400000 es IO: escribir ahí dispararía efectos colaterales.
        val result = CheatParser.parse("80400000 0001")

        assertTrue(result.errors.toString(), result.accepted)
        assertTrue(result.ops.isEmpty())
        assertEquals(1, result.warnings.size)
    }

    @Test
    fun `una escritura en ROM se omite con aviso`() {
        val result = CheatParser.parse("88800000 0001")

        assertTrue(result.errors.toString(), result.accepted)
        assertTrue(result.ops.isEmpty())
        assertEquals(1, result.warnings.size)
    }

    @Test
    fun `una condicion en zona no valida se emite igualmente`() {
        // Fallar cerrada: omitirla dejaría pasar las escrituras que dependen de
        // ella, que es lo contrario de lo que pide el código. El núcleo
        // simplemente no la cumplirá y saltará lo que deba.
        val result = CheatParser.parse(
            """
            74001300 0001
            82003ABC 0064
            """.trimIndent()
        )

        assertTrue(result.errors.toString(), result.accepted)
        assertEquals(
            listOf(
                CheatOp(0x04001300, 0x0001, CheatKind.IF_EQ, 2, 1),
                CheatOp(0x02003ABC, 0x0064, CheatKind.WRITE, 2, 0),
            ),
            result.ops,
        )
    }

    @Test
    fun `una condicion en ROM se emite`() {
        val result = CheatParser.parse(
            """
            78000000 0001
            82003ABC 0064
            """.trimIndent()
        )

        assertTrue(result.errors.toString(), result.accepted)
        assertEquals(
            listOf(
                CheatOp(0x08000000, 0x0001, CheatKind.IF_EQ, 2, 1),
                CheatOp(0x02003ABC, 0x0064, CheatKind.WRITE, 2, 0),
            ),
            result.ops,
        )
    }

    @Test
    fun `FILL de CodeBreaker aborta la lista`() {
        val result = CheatParser.parse(
            """
            82003ABC 0064
            40001C40 1234
            82003ABC 0064
            """.trimIndent()
        )

        assertFalse(result.accepted)
        assertTrue(result.errors.single().contains("FILL"))
        // El número de línea es lo que el usuario necesita para encontrar el
        // código dentro del texto pegado.
        assertTrue(result.errors.single().startsWith("Línea 2"))
    }

    @Test
    fun `FILL_LIST de CodeBreaker aborta la lista`() {
        val result = CheatParser.parse(
            """
            82003ABC 0064
            50001C40 0004
            82003ABC 0064
            """.trimIndent()
        )

        assertFalse(result.accepted)
        assertTrue(result.errors.single().contains("FILL"))
    }

    @Test
    fun `el cifrado de CodeBreaker aborta la lista`() {
        // Tipo 9: cambia las semillas de las líneas siguientes.
        val result = CheatParser.parse("90003ABC 1234")

        assertFalse(result.accepted)
        assertTrue(result.errors.single().contains("cifrado"))
    }

    @Test
    fun `tras un abort no se sigue interpretando`() {
        val result = CheatParser.parse(
            """
            82003ABC 0064
            90003ABC 1234
            82003ABC 0064
            """.trimIndent()
        )

        assertFalse(result.accepted)
        assertEquals(1, result.ops.size)
    }

    // --- GameShark en claro ---------------------------------------------

    @Test
    fun `escritura GameShark de 16 bits en claro`() {
        val result = CheatParser.parse("12003ABC 00000064")

        assertTrue(result.errors.toString(), result.accepted)
        assertEquals(listOf(CheatOp(0x02003ABC, 0x00000064, CheatKind.WRITE, 2, 0)), result.ops)
    }

    @Test
    fun `escritura GameShark de 8 y de 32 bits`() {
        val result = CheatParser.parse(
            """
            02001E00 000000FF
            22004000 11223344
            """.trimIndent()
        )

        assertTrue(result.errors.toString(), result.accepted)
        assertEquals(
            listOf(
                CheatOp(0x02001E00, 0x000000FF, CheatKind.WRITE, 1, 0),
                CheatOp(0x02004000, 0x11223344, CheatKind.WRITE, 4, 0),
            ),
            result.ops,
        )
    }

    @Test
    fun `las cuatro condiciones de GameShark salen de op2`() {
        // El tipo va en los bits 20-23 de op2 y el valor en los 16 bajos.
        val result = CheatParser.parse(
            """
            D2000100 00000001
            D2000200 00100002
            D2000300 00200003
            D2000400 00300004
            """.trimIndent()
        )

        assertTrue(result.errors.toString(), result.accepted)
        assertEquals(
            listOf(
                CheatOp(0x02000100, 0x0001, CheatKind.IF_EQ, 2, 1),
                CheatOp(0x02000200, 0x0002, CheatKind.IF_NE, 2, 1),
                CheatOp(0x02000300, 0x0003, CheatKind.IF_LE, 2, 1),
                CheatOp(0x02000400, 0x0004, CheatKind.IF_GE, 2, 1),
            ),
            result.ops,
        )
    }

    @Test
    fun `una condicion de GameShark de tipo desconocido se omite con aviso`() {
        // El subtipo 5 no existe en el formato.
        val result = CheatParser.parse("D2000500 00500005")

        assertTrue(result.errors.toString(), result.accepted)
        assertTrue(result.ops.isEmpty())
        assertEquals(1, result.warnings.size)
    }

    @Test
    fun `el rango de GameShark toma direccion de op2 y alcance de op1`() {
        // En op1: bits 16-23 = 0x03 = alcance; bits 0-15 = valor. La dirección
        // está en op2. Por eso el nibble D no aparece en op1: es el nibble E.
        val result = CheatParser.parse("E2030005 02006010")

        assertTrue(result.errors.toString(), result.accepted)
        assertEquals(listOf(CheatOp(0x02006010, 0x0005, CheatKind.IF_EQ, 2, 3)), result.ops)
    }

    @Test
    fun `un rango con alcance cero no salta nada`() {
        // Con repeat = 0 la condición no gobierna operaciones, así que es lo
        // mismo que no tenerla; se emite igualmente.
        val result = CheatParser.parse("E2000005 02006010")

        assertEquals(listOf(CheatOp(0x02006010, 0x0005, CheatKind.IF_EQ, 2, 0)), result.ops)
    }

    @Test
    fun `un parche de ROM de GameShark se omite con aviso`() {
        val result = CheatParser.parse(
            """
            62003ABC 00001234
            12003ABC 00000064
            """.trimIndent()
        )

        assertTrue(result.errors.toString(), result.accepted)
        assertEquals(listOf(CheatOp(0x02003ABC, 0x00000064, CheatKind.WRITE, 2, 0)), result.ops)
        assertEquals(1, result.warnings.size)
    }

    // --- GameShark cifrado ----------------------------------------------
    // Los vectores de abajo son los códigos en claro de arriba cifrados con las
    // semillas públicas de GameShark, así que el parser debe reconstruirlos.

    @Test
    fun `una lista cifrada se descifra y aplica igual que en claro`() {
        val result = CheatParser.parse(
            """
            600AB69A 971B8825
            """.trimIndent()
        )

        assertTrue(result.errors.toString(), result.accepted)
        assertTrue(result.warnings.isEmpty())
        assertEquals(listOf(CheatOp(0x02003ABC, 0x00000064, CheatKind.WRITE, 2, 0)), result.ops)
    }

    @Test
    fun `la condicion cifrada se descifra con el mismo criterio`() {
        val result = CheatParser.parse(
            """
            600AB69A 971B8825
            A25CA9EC 9CDF6DC0
            """.trimIndent()
        )

        assertTrue(result.errors.toString(), result.accepted)
        assertEquals(
            listOf(
                CheatOp(0x02003ABC, 0x00000064, CheatKind.WRITE, 2, 0),
                CheatOp(0x02000100, 0x0001, CheatKind.IF_NE, 2, 1),
            ),
            result.ops,
        )
    }

    @Test
    fun `un boton en una lista cifrada se omite con aviso`() {
        val result = CheatParser.parse(
            """
            600AB69A 971B8825
            611FDD78 7E6C17C5
            """.trimIndent()
        )

        assertTrue(result.errors.toString(), result.accepted)
        assertEquals(1, result.ops.size)
        assertEquals(1, result.warnings.size)
    }

    @Test
    fun `un hook en una lista cifrada se omite con aviso`() {
        val result = CheatParser.parse(
            """
            600AB69A 971B8825
            F15DC8A7 6CE1B903
            """.trimIndent()
        )

        assertTrue(result.errors.toString(), result.accepted)
        assertEquals(1, result.ops.size)
        assertEquals(1, result.warnings.size)
    }

    @Test
    fun `los codigos en lista de GameShark abortan`() {
        val result = CheatParser.parse(
            """
            600AB69A 971B8825
            9C376BB6 A538C25F
            """.trimIndent()
        )

        assertFalse(result.accepted)
        assertTrue(result.errors.single().contains("lista"))
    }

    // --- Detección de variante y formatos rechazados ---------------------

    @Test
    fun `un PARv3 se rechaza con un mensaje claro`() {
        // op1 = 0 con el nibble de "relleno" de op2: así empiezan estas listas.
        val result = CheatParser.parse("00000000 81006238")

        assertFalse(result.accepted)
        assertTrue(result.errors.single().contains("Action Replay"))
    }

    @Test
    fun `el reseed DEADFACE de GameShark aborta`() {
        val result = CheatParser.parse("DEADFACE 00001234")

        assertFalse(result.accepted)
        assertTrue(result.errors.single().contains("DEADFACE"))
    }

    @Test
    fun `la variante se decide con la primera linea de la lista`() {
        // Lo que se comprueba es que las dos líneas se interpretan igual: si la
        // variante se recalculara en cada línea, la segunda no coincidiría.
        val result = CheatParser.parse(
            """
            12003ABC 00000064
            12003ABE 00000064
            """.trimIndent()
        )

        assertTrue(result.errors.toString(), result.accepted)
        assertEquals(
            listOf(
                CheatOp(0x02003ABC, 0x00000064, CheatKind.WRITE, 2, 0),
                CheatOp(0x02003ABE, 0x00000064, CheatKind.WRITE, 2, 0),
            ),
            result.ops,
        )
    }

    @Test
    fun `una lista puede mezclar formatos`() {
        // Cada línea se interpreta por su propia forma, así que un usuario puede
        // juntar códigos de distintas fuentes.
        val result = CheatParser.parse(
            """
            82003ABC 0064
            12003ABE 00000064
            """.trimIndent()
        )

        assertTrue(result.errors.toString(), result.accepted)
        assertEquals(2, result.ops.size)
    }

    // --- Entrada general -----------------------------------------------

    @Test
    fun `las lineas en blanco y los comentarios se ignoran`() {
        val result = CheatParser.parse(
            """
            # Vida infinita

            // resultado de la lista de la wiki
            82003ABC 0064

            """.trimIndent()
        )

        assertTrue(result.errors.toString(), result.accepted)
        assertTrue(result.warnings.isEmpty())
        assertEquals(1, result.ops.size)
    }

    @Test
    fun `un texto que no es codigo se avisa pero no aborta`() {
        val result = CheatParser.parse(
            """
            Cheats de la wiki
            82003ABC 0064
            """.trimIndent()
        )

        assertTrue(result.errors.toString(), result.accepted)
        assertEquals(1, result.ops.size)
        assertEquals(1, result.warnings.size)
    }

    @Test
    fun `una linea con un codigo cortado aborta la lista`() {
        // A diferencia del texto anterior, aquí hay un token con forma de código
        // pero la línea no se puede interpretar: no se sabe qué parte de la
        // estructura de control se ha perdido.
        val result = CheatParser.parse(
            """
            82003ABC 0064
            82003ABD 00
            """.trimIndent()
        )

        assertFalse(result.accepted)
        assertTrue(result.errors.single().startsWith("Línea 2"))
    }

    @Test
    fun `los espacios sobrantes no estorban`() {
        val result = CheatParser.parse("  82003ABC   0064  ")

        assertTrue(result.errors.toString(), result.accepted)
        assertEquals(1, result.ops.size)
    }

    @Test
    fun `una lista vacia es valida y no produce operaciones`() {
        val result = CheatParser.parse("")

        assertTrue(result.accepted)
        assertTrue(result.isEmpty)
    }

    @Test
    fun `el orden de las operaciones conserva el de las lineas`() {
        // El alcance de las condiciones cuenta posiciones de la lista, así que
        // el orden es parte del contrato con el núcleo y no un detalle.
        val result = CheatParser.parse(
            """
            82003AA0 0001
            72001000 0002
            82003AA2 0003
            82003AA4 0004
            """.trimIndent()
        )

        assertEquals(
            listOf(0x02003AA0, 0x02001000, 0x02003AA2, 0x02003AA4),
            result.ops.map { it.address },
        )
    }

    @Test
    fun `el ancho de escritura es el que pide el codigo`() {
        val result = CheatParser.parse(
            """
            32001E00 00FF
            22004000 11223344
            """.trimIndent()
        )

        assertEquals(listOf(1, 4), result.ops.map { it.width })
    }
}
