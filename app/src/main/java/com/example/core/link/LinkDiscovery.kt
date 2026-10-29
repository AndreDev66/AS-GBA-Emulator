package com.example.core.link

import android.os.SystemClock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * Anuncio de sala por UDP de difusión: así el invitado encuentra al anfitrión
 * sin escribir IP a mano.
 *
 * Se difunde (broadcast) y no se responde a peticiones: en la LAN de casa cada
 * persona es dueña de su sala y basta con que el anfitrión diga "aquí estoy"
 * una vez por segundo. Quien vaya a unirse escucha ese canal y muestra lo que
 * oye. Es un descubrimiento, no un protocolo: si el paquete se pierde, el
 * siguiente segundo lo repone.
 *
 * La lista se poda por antigüedad ([ROOM_TIMEOUT_MS]): una sala que deja de
 * anunciarse es una sala a la que ya no se puede llegar, y una IP muerta en el
 * listado invita a intentarlo y fracasar.
 */

/** Puerto TCP del enlace entre dos dispositivos. */
const val LINK_TCP_PORT = 17017

/** Puerto UDP donde las salas se anuncian. */
const val LINK_DISCOVERY_PORT = 17018

/** Margen sin anuncios tras el que una sala se considera cerrada. */
const val ROOM_TIMEOUT_MS = 5_000L

/** Sala vista por el invitado: dirección, puerto y nombre del anfitrión. */
data class DiscoveredRoom(
    val address: String,
    val port: Int,
    val name: String,
    val lastSeen: Long
)

/** Contenido de un anuncio de sala. */
data class RoomBeaconData(val port: Int, val name: String)

/** Nombre de sala por defecto cuando el anfitrión no pone ninguno. */
const val DEFAULT_ROOM_NAME = "Sala"

/**
 * Codifica y decodifica el anuncio de sala.
 *
 * Formato: "ASGB" | versión (1) | puerto TCP (u16) | longitud del nombre (u8)
 * | nombre en UTF-8. Se reconoce la magia y la versión para que un anuncio de
 * otra aplicación, o de una versión futura, no acabe en el listado.
 *
 * El recorte del nombre es en caracteres, nunca en bytes: cortar por la mitad
 * de una secuencia UTF-8 dejaría un carácter partido en la lista.
 */
object RoomBeacon {

    /** "ASGB", como el saludo que intercambian los extremos del enlace TCP. */
    private val MAGIC = byteArrayOf(0x41, 0x53, 0x47, 0x42)

    private const val VERSION = 1
    private const val HEADER_BYTES = 8 // 4 de magia, 1 de versión, 2 de puerto, 1 de longitud
    private const val MAX_NAME_CHARS = 32

    fun encode(port: Int, name: String): ByteArray {
        val text = name.take(MAX_NAME_CHARS).ifBlank { DEFAULT_ROOM_NAME }.toByteArray(Charsets.UTF_8)
        val packet = ByteArray(HEADER_BYTES + text.size)
        MAGIC.copyInto(packet)
        packet[4] = VERSION.toByte()
        packet[5] = (port and 0xff).toByte()
        packet[6] = ((port ushr 8) and 0xff).toByte()
        packet[7] = text.size.toByte()
        text.copyInto(packet, HEADER_BYTES)
        return packet
    }

    /** `null` si el paquete no es un anuncio de sala de esta versión. */
    fun decode(data: ByteArray, length: Int = data.size): RoomBeaconData? {
        if (length < HEADER_BYTES) return null
        for (index in MAGIC.indices) if (data[index] != MAGIC[index]) return null
        if (data[4].toInt() != VERSION) return null
        val port = (data[5].toInt() and 0xff) or ((data[6].toInt() and 0xff) shl 8)
        if (port !in 1..65535) return null
        val size = data[7].toInt() and 0xff
        if (size == 0 || HEADER_BYTES + size > length) return null
        val name = String(data, HEADER_BYTES, size, Charsets.UTF_8).ifBlank { DEFAULT_ROOM_NAME }
        return RoomBeaconData(port, name)
    }
}

/**
 * Anuncia la sala del anfitrión una vez por segundo mientras exista.
 *
 * Si la plataforma no deja difundir (políticas de ahorro de datos, por
 * ejemplo), [announce] devuelve `false` y quien lo llama deja de insistir: el
 * enlace TCP sigue sirviendo, sólo se pierde el hallazgo automático, que el
 * usuario resuelve escribiendo la IP a mano.
 */
class RoomBeaconSender(private val port: Int, private val name: String) {

    private val packet: ByteArray = RoomBeacon.encode(port, name)

    private val socket: DatagramSocket? = try {
        DatagramSocket().apply { broadcast = true }
    } catch (e: Exception) {
        null
    }

    fun announce(): Boolean {
        val open = socket ?: return false
        return try {
            open.send(
                DatagramPacket(
                    packet,
                    packet.size,
                    InetAddress.getByName(BROADCAST_ADDRESS),
                    LINK_DISCOVERY_PORT
                )
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    fun close() {
        try {
            socket?.close()
        } catch (e: Exception) {
            // Cerrar un socket que ya no vale: nada que hacer ni que informar.
        }
    }
}

/**
 * Escucha los anuncios de sala. Se abre en cuanto se muestra la pantalla de
 * enlace y se cierra al salir de ella.
 */
class RoomListener {

    private val socket: DatagramSocket? = try {
        DatagramSocket(null).apply {
            reuseAddress = true
            // Espera corta: el bucle vuelve a llamar enseguida y así una sala
            // que deja de anunciar se poda a los pocos segundos.
            soTimeout = RECEIVE_TIMEOUT_MS
            bind(InetSocketAddress(LINK_DISCOVERY_PORT))
        }
    } catch (e: Exception) {
        null
    }

    /** La sala recién anunciada, o `null` si en esta espera no oyó ninguna. */
    fun receive(): DiscoveredRoom? {
        val open = socket ?: return null
        return try {
            val buffer = ByteArray(MAX_PACKET_BYTES)
            val packet = DatagramPacket(buffer, buffer.size)
            open.receive(packet)
            val beacon = RoomBeacon.decode(buffer, packet.length) ?: return null
            val address = packet.address?.hostAddress ?: return null
            DiscoveredRoom(address, beacon.port, beacon.name, SystemClock.elapsedRealtime())
        } catch (e: SocketTimeoutException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    fun close() {
        try {
            socket?.close()
        } catch (e: Exception) {
            // Como en [RoomBeaconSender.close]: cerrar no falla de forma útil.
        }
    }
}

private const val BROADCAST_ADDRESS = "255.255.255.255"
private const val RECEIVE_TIMEOUT_MS = 1_000
private const val MAX_PACKET_BYTES = 256
