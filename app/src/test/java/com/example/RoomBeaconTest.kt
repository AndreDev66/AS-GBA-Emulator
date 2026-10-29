package com.example

import com.example.core.link.DEFAULT_ROOM_NAME
import com.example.core.link.LINK_DISCOVERY_PORT
import com.example.core.link.LINK_TCP_PORT
import com.example.core.link.RoomBeacon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pruebas del anuncio de sala del enlace LAN.
 *
 * Lo que importa aquí es el parecido: un anuncio escrito por un teléfono tiene
 * que volver a leerse igual en el otro, y cualquier otra cosa que circule por el
 * mismo canal UDP (otra aplicación, una versión futura) tiene que descartarse
 * en silencio en vez de aparecer en el listado de salas.
 */
class RoomBeaconTest {

    @Test
    fun `un anuncio vuelve a leerse igual`() {
        val encoded = RoomBeacon.encode(LINK_TCP_PORT, "Sala de Andrés")

        val decoded = RoomBeacon.decode(encoded)

        assertEquals(LINK_TCP_PORT, decoded?.port)
        assertEquals("Sala de Andrés", decoded?.name)
    }

    @Test
    fun `el puertoTcp sobrevive al viaje`() {
        // El puerto va en dos bytes: los puertos altos (0xFFFF = 65535) son el
        // caso donde un error de un byte pasaria inadvertido.
        val encoded = RoomBeacon.encode(65535, "Sala")

        assertEquals(65535, RoomBeacon.decode(encoded)?.port)
    }

    @Test
    fun `los acentos y la enye viajan enteros`() {
        val encoded = RoomBeacon.encode(LINK_TCP_PORT, "Ñandú nº 1")

        val decoded = RoomBeacon.decode(encoded)

        // UTF-8 ocupa dos bytes por carácter: el nombre se decodifica con su
        // longitud en bytes, no en caracteres.
        assertEquals("Ñandú nº 1", decoded?.name)
    }

    @Test
    fun `un nombre vacio toma el nombre por defecto`() {
        val encoded = RoomBeacon.encode(LINK_TCP_PORT, "   ")

        assertEquals(DEFAULT_ROOM_NAME, RoomBeacon.decode(encoded)?.name)
    }

    @Test
    fun `un nombre largo se recorta sin partir caracteres`() {
        // El recorte es en caracteres: a media secuencia UTF-8 el otro extremo
        // leeria un caracter roto.
        val encoded = RoomBeacon.encode(LINK_TCP_PORT, "á".repeat(80))

        val name = RoomBeacon.decode(encoded)?.name ?: ""

        assertEquals(32, name.length)
        assertEquals("á".repeat(32), name)
    }

    @Test
    fun `el paquete empieza por la magia ASGB`() {
        val encoded = RoomBeacon.encode(LINK_TCP_PORT, "Sala")

        assertEquals('A'.code.toByte(), encoded[0])
        assertEquals('S'.code.toByte(), encoded[1])
        assertEquals('G'.code.toByte(), encoded[2])
        assertEquals('B'.code.toByte(), encoded[3])
    }

    @Test
    fun `lo que no es un anuncio se descarta`() {
        assertNull(RoomBeacon.decode(ByteArray(0)))
        assertNull(RoomBeacon.decode(ByteArray(4)))
    }

    @Test
    fun `otro protocolo con la misma longitud se descarta`() {
        val foreign = ByteArray(32) { 0x7f }

        assertNull(RoomBeacon.decode(foreign))
    }

    @Test
    fun `una version futura se descarta`() {
        val encoded = RoomBeacon.encode(LINK_TCP_PORT, "Sala")
        encoded[4] = 99

        assertNull(RoomBeacon.decode(encoded))
    }

    @Test
    fun `una longitud mayor que el paquete se descarta`() {
        val encoded = RoomBeacon.encode(LINK_TCP_PORT, "Sala")
        // Anuncia 200 bytes de nombre en un paquete que no los tiene.
        encoded[7] = 200.toByte()

        assertNull(RoomBeacon.decode(encoded))
    }

    @Test
    fun `un puerto fuera de rango se descarta`() {
        val encoded = RoomBeacon.encode(LINK_TCP_PORT, "Sala")
        encoded[5] = 0
        encoded[6] = 0

        assertNull(RoomBeacon.decode(encoded))
    }

    @Test
    fun `el paquete truncado se descarta`() {
        val encoded = RoomBeacon.encode(LINK_TCP_PORT, "Sala de Andrés")

        // Recortado a la mitad del nombre: la longitud anunciada ya no cabe.
        assertNull(RoomBeacon.decode(encoded, length = encoded.size / 2))
    }

    @Test
    fun `los puertos por defecto son los del enlace`() {
        // Son el contrato entre los dos teléfonos: si uno cambia el puerto TCP y
        // el otro sigue escuchando en UDP por el mismo sitio, la sala deja de
        // aparecer en el listado.
        assertEquals(17017, LINK_TCP_PORT)
        assertEquals(17018, LINK_DISCOVERY_PORT)
    }
}
