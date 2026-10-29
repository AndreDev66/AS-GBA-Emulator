package com.example.core.link

import android.os.SystemClock
import android.util.Log
import com.example.core.GBACore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Papel en el enlace multiprocesador de Game Boy Advance.
 *
 * En el cable lo decidía la posición en la cadena; en red lo decide el
 * transporte: quien crea la sala es el padre (ID 0, el que genera el reloj) y
 * quien se une el hijo (ID 1, que no puede iniciar transferencias).
 */
enum class LinkRole { NONE, PARENT, CHILD }

/** Estado del enlace tal como lo pinta la interfaz. */
sealed interface LinkState {

    /** Sin enlace: el juego corre como siempre, en solitario. */
    data object Inactive : LinkState

    /** Hay transporte abierto pero el par todavía no está. */
    data class Waiting(val role: LinkRole) : LinkState

    /** El par está y los dos SIO ya se hablan por la red. */
    data class Linked(val role: LinkRole) : LinkState
}

/** Resultado de abrir un enlace; [Error] lleva el motivo ya en castellano. */
sealed interface LinkResult {
    data object Ok : LinkResult
    data class Error(val reason: String) : LinkResult
}

/**
 * Enlace LAN entre dos dispositivos: cada jugador en su teléfono, un solo
 * juego en la pantalla de cada uno.
 *
 * El núcleo no sabe de redes; esta clase es quien decide cuándo hay sala, quién
 * la espera y cuándo se cierra, y delega el cable en el transporte nativo.
 * Todo lo que toca la máquina pasa por `GBACore`, que toma su candado: aquí no
 * se sincroniza nada, porque el hilo de emulación y el de red ya están
 * separados por diseño del lado nativo.
 */
class LinkManager(private val core: GBACore) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<LinkState>(LinkState.Inactive)

    /** Estado del enlace; la interfaz lo observa para el distintivo y la sala. */
    val state: StateFlow<LinkState> = _state.asStateFlow()

    private val _rooms = MutableStateFlow<List<DiscoveredRoom>>(emptyList())

    /** Salas que se han anunciado en la red, de la más reciente a la más vieja. */
    val rooms: StateFlow<List<DiscoveredRoom>> = _rooms.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)

    /** Último aviso para el usuario; la interfaz lo muestra y lo limpia. */
    val message: StateFlow<String?> = _message.asStateFlow()

    @Volatile
    private var endpoint: Long = 0L

    @Volatile
    private var role: LinkRole = LinkRole.NONE

    private var pollJob: Job? = null
    private var beaconJob: Job? = null
    private var discoveryJob: Job? = null
    private var beacon: RoomBeaconSender? = null
    private var listener: RoomListener? = null

    /**
     * Crea la sala: abre la escucha TCP y empieza a anunciarse por UDP.
     *
     * El juego puede cargarse después. El enganche al núcleo sobrevive a la
     * carga de ROM porque el nativo se recablea solo en cada máquina nueva.
     */
    suspend fun createRoom(
        roomName: String = DEFAULT_ROOM_NAME,
        port: Int = LINK_TCP_PORT
    ): LinkResult {
        close()
        return open(
            server = true,
            host = null,
            port = port,
            newRole = LinkRole.PARENT,
            roomName = roomName
        )
    }

    /** Se une a una sala concreta del listado de descubrimiento. */
    suspend fun joinRoom(room: DiscoveredRoom): LinkResult =
        joinRoom(room.address, room.port)

    /**
     * Se une a la sala de `host` escribiendo la dirección a mano.
     *
     * Es la vía de escape del descubrimiento: si la difusión no llega (Wi-Fi
     * con clientes aislados, por ejemplo), conocer la IP del otro sigue siendo
     * suficiente.
     */
    suspend fun joinRoom(host: String, port: Int = LINK_TCP_PORT): LinkResult {
        val address = host.trim()
        if (address.isEmpty()) {
            return fail("Escribe la dirección del otro dispositivo.")
        }
        close()
        return open(
            server = false,
            host = address,
            port = port,
            newRole = LinkRole.CHILD,
            roomName = null
        )
    }

    /** Cierra el enlace y vuelve al juego en solitario. Idempotente. */
    fun close() {
        val handle = endpoint
        Log.i(TAG, "Cerrando el enlace (asa $handle, papel $role)")
        pollJob?.cancel()
        pollJob = null
        stopBeacon()
        stopDiscovery()
        endpoint = 0L
        if (handle != 0L) {
            try {
                // El candado de la máquina lo toma `GBACore` por dentro, y esto
                // ocurre antes de que se libere el núcleo: el enlace vive de él.
                core.linkClose(handle)
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo cerrar el enlace: ${e.message}")
            }
        }
        role = LinkRole.NONE
        _state.value = LinkState.Inactive
    }

    /**
     * Empieza a escuchar las salas de la red.
     *
     * La pantalla de enlace lo llama al abrirse y [stopDiscovery] al cerrarse:
     * el socket de descubrimiento no tiene por qué existir el resto del tiempo.
     */
    fun startDiscovery() {
        if (discoveryJob?.isActive == true) return
        val opened = RoomListener()
        listener = opened
        discoveryJob = scope.launch {
            val seen = LinkedHashMap<String, DiscoveredRoom>()
            while (isActive) {
                val room = opened.receive() // espera hasta un segundo
                val now = SystemClock.elapsedRealtime()
                if (room != null) seen[room.address] = room
                // Poda por antigüedad: una IP que dejó de anunciar es una sala
                // a la que ya no se puede llegar y no debe quedar en la lista.
                val alive = seen.values.filter { now - it.lastSeen < ROOM_TIMEOUT_MS }
                seen.keys.retainAll(alive.mapTo(HashSet()) { it.address })
                _rooms.value = alive.sortedBy { it.name }
            }
        }
    }

    fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
        listener?.close()
        listener = null
        _rooms.value = emptyList()
    }

    private suspend fun open(
        server: Boolean,
        host: String?,
        port: Int,
        newRole: LinkRole,
        roomName: String?
    ): LinkResult {
        val handle = try {
            // Fuera del hilo principal y sin el candado de la máquina: la
            // conexión del invitado puede tardar segundos en una red mal
            // configurada, y con el candado tomado el hilo de emulación
            // quedaría parado ese tiempo.
            withContext(Dispatchers.IO) { core.linkConnect(server, host, port) }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo abrir el enlace: ${e.message}")
            LINK_ERROR_SOCKET
        }
        if (handle <= 0L) {
            return fail(
                when (handle) {
                    LINK_ERROR_TIMEOUT ->
                        "El otro dispositivo no respondió a tiempo. ¿Estáis en la misma red?"
                    LINK_ERROR_SOCKET ->
                        "No se pudo abrir el enlace en el puerto $port."
                    else -> "Dirección o puerto de enlace no válidos."
                }
            )
        }
        val attached = try {
            core.linkAttach(handle)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo enganchar el enlace: ${e.message}")
            false
        }
        if (!attached) {
            core.linkClose(handle)
            return fail("No se pudo enganchar el enlace al núcleo.")
        }
        endpoint = handle
        role = newRole
        Log.i(TAG, "Enlace abierto (asa $handle, papel $newRole, puerto $port)")
        _state.value = LinkState.Waiting(newRole)
        if (newRole == LinkRole.PARENT && roomName != null) startBeacon(roomName, port)
        startPolling()
        return LinkResult.Ok
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive && endpoint != 0L) {
                delay(POLL_INTERVAL_MS)
                refresh()
            }
        }
    }

    /**
     * Pregunta al transporte cómo va el enlace y lo refleja en el estado.
     *
     * Si el par se va, el enlace se cierra solo y el juego sigue en solitario:
     * volver a enhebrarlo es cosa de la interfaz, no del núcleo.
     */
    private fun refresh() {
        val handle = endpoint
        if (handle == 0L) return
        val status = try {
            core.linkStatus(handle)
        } catch (e: Exception) {
            STATUS_CLOSED
        }
        val next = when (status) {
            STATUS_LINKED -> LinkState.Linked(role)
            STATUS_WAITING -> LinkState.Waiting(role)
            else -> {
                Log.w(TAG, "El transporte informa del estado $status: se cierra el enlace")
                _message.value = "Se perdió el enlace con el otro dispositivo."
                close()
                return
            }
        }
        // Sólo se registra cuando cambia: el sondeo va medio segundo y un
        // registro por sondeo llenaría el registro del sistema de nada útil.
        if (_state.value != next) {
            Log.i(TAG, "Estado del enlace: $next")
            _state.value = next
        }
    }

    private fun startBeacon(roomName: String, port: Int) {
        stopBeacon()
        val sender = RoomBeaconSender(port, roomName)
        beacon = sender
        beaconJob = scope.launch {
            while (isActive) {
                if (!sender.announce()) break
                delay(ANNOUNCE_INTERVAL_MS)
            }
        }
    }

    private fun stopBeacon() {
        beaconJob?.cancel()
        beaconJob = null
        beacon?.close()
        beacon = null
    }

    /** Limpia el último aviso una vez la interfaz ya lo ha mostrado. */
    fun clearMessage() {
        _message.value = null
    }

    private fun fail(reason: String): LinkResult {
        _message.value = reason
        return LinkResult.Error(reason)
    }

    companion object {
        private const val TAG = "LinkManager"

        // Códigos de `NativeCoreBridge.linkConnect`.
        private const val LINK_ERROR_SOCKET = -1L
        private const val LINK_ERROR_TIMEOUT = -2L

        // Estados de `NativeCoreBridge.linkStatus`.
        private const val STATUS_WAITING = 0
        private const val STATUS_LINKED = 1
        private const val STATUS_CLOSED = 2

        private const val POLL_INTERVAL_MS = 500L
        private const val ANNOUNCE_INTERVAL_MS = 1_000L
    }
}
