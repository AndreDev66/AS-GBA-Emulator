package com.example.ui.screens

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.link.DEFAULT_ROOM_NAME
import com.example.core.link.DiscoveredRoom
import com.example.core.link.LINK_TCP_PORT
import com.example.core.link.LinkManager
import com.example.core.link.LinkRole
import com.example.core.link.LinkState
import com.example.ui.theme.SteamBg
import com.example.ui.theme.SteamBorder
import com.example.ui.theme.SteamCyan
import com.example.ui.theme.SteamGreen
import com.example.ui.theme.SteamOnAccent
import com.example.ui.theme.SteamOrange
import com.example.ui.theme.SteamPink
import com.example.ui.theme.SteamSurface1
import com.example.ui.theme.SteamSurface2
import com.example.ui.theme.SteamSurface3
import com.example.ui.theme.SteamTextMuted
import com.example.ui.theme.SteamTextPrimary
import com.example.ui.theme.SteamTextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Enlace LAN: dos jugadores, un juego, cada uno en su teléfono.
 *
 * El enlace se abre **antes** de cargar la partida, no durante: al entrar en el
 * juego cada jugador ve sólo su propia pantalla y su mando, y el cable de datos
 * cruza por la red sin que la interfaz tenga que hacer nada más. Por eso el
 * flujo es sala o unión aquí, y salir a la biblioteca con el enlace ya vivo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkScreen(
    linkManager : LinkManager,
    onBack      : () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()

    val state   by linkManager.state.collectAsState()
    val rooms   by linkManager.rooms.collectAsState()
    val message by linkManager.message.collectAsState()

    var roomName by remember { mutableStateOf(defaultRoomName()) }
    var hostAddress by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    // El socket de descubrimiento sólo existe mientras esta pantalla está
    // abierta: dentro del juego no hace falta y ocupa un puerto sin motivo.
    DisposableEffect(Unit) {
        linkManager.startDiscovery()
        onDispose { linkManager.stopDiscovery() }
    }

    // El aviso se retira solo a los pocos segundos: dejarlo pegado haría que
    // un fallo de hace un minuto siguiera ocupando sitio al volver.
    LaunchedEffect(message) {
        if (message != null) {
            delay(5000)
            linkManager.clearMessage()
        }
    }

    fun createRoom() {
        if (busy) return
        busy = true
        coroutineScope.launch {
            linkManager.createRoom(roomName)
            busy = false
        }
    }

    fun join(address: String, port: Int = LINK_TCP_PORT) {
        if (busy) return
        busy = true
        coroutineScope.launch {
            linkManager.joinRoom(address, port)
            busy = false
        }
    }

    Scaffold(
        containerColor = SteamBg,
        topBar = {
            LinkTopBar(
                isActive = state !is LinkState.Inactive,
                onBack   = onBack
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            LinkStatusCard(state)

            when (state) {
                LinkState.Inactive -> {
                    OutlinedTextField(
                        value         = roomName,
                        onValueChange = { roomName = it },
                        label         = { Text("Nombre de la sala") },
                        singleLine    = true,
                        modifier      = Modifier
                            .fillMaxWidth()
                            .testTag("input_link_room")
                    )
                    FilledTonalButton(
                        onClick   = ::createRoom,
                        enabled   = !busy,
                        shape     = RoundedCornerShape(12.dp),
                        colors    = ButtonDefaults.filledTonalButtonColors(
                            containerColor = SteamCyan,
                            contentColor   = SteamOnAccent
                        ),
                        contentPadding = PaddingValues(vertical = 12.dp),
                        modifier  = Modifier
                            .fillMaxWidth()
                            .testTag("btn_link_create")
                    ) {
                        Icon(
                            Icons.Default.Link,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Crear sala",
                            style      = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Unirse
                    SectionTitle("Unirse a una sala")

                    if (rooms.isEmpty()) {
                        Text(
                            "Buscando salas en la red…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SteamTextMuted
                        )
                    } else {
                        rooms.forEach { room ->
                            RoomRow(room = room, enabled = !busy, onJoin = { join(room.address, room.port) })
                        }
                    }

                    Text(
                        "Si la sala no aparece, escribe la dirección del otro dispositivo:",
                        style = MaterialTheme.typography.bodySmall,
                        color = SteamTextMuted
                    )
                    Row(
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value         = hostAddress,
                            onValueChange = { hostAddress = it },
                            placeholder   = { Text("192.168.1.42") },
                            singleLine    = true,
                            modifier      = Modifier
                                .weight(1f)
                                .testTag("input_link_host")
                        )
                        Button(
                            onClick   = { join(hostAddress) },
                            enabled   = !busy && hostAddress.isNotBlank(),
                            shape     = RoundedCornerShape(12.dp),
                            colors    = ButtonDefaults.buttonColors(
                                containerColor = SteamSurface3,
                                contentColor   = SteamTextPrimary
                            ),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            modifier  = Modifier.testTag("btn_link_join")
                        ) {
                            Text("Unirse", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }

                is LinkState.Waiting -> ActivePanel(
                    state    = state,
                    onClose  = { linkManager.close() }
                )

                is LinkState.Linked -> ActivePanel(
                    state    = state,
                    onClose  = { linkManager.close() }
                )
            }

            if (message != null) {
                Text(
                    text  = message.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = SteamPink,
                    modifier = Modifier.testTag("text_link_message")
                )
            }
        }
    }
}

@Composable
private fun LinkTopBar(
    isActive : Boolean,
    onBack   : () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SteamSurface1)
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(SteamSurface2)
                .clickable { onBack() }
                .testTag("btn_link_back"),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector        = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Volver",
                tint               = SteamTextPrimary,
                modifier           = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text  = "Enlace LAN",
            style = MaterialTheme.typography.titleMedium,
            color = SteamTextPrimary
        )
        Spacer(modifier = Modifier.weight(1f))
        if (isActive) LinkBadge(state = null, compact = true)
    }
}

@Composable
private fun LinkStatusCard(state: LinkState) {
    val (title, subtitle) = when (state) {
        LinkState.Inactive ->
            "Sin enlace" to "Jugarás en solitario. Puedes abrir una sala o unirte a una antes de cargar el juego."
        is LinkState.Waiting ->
            "Esperando al otro jugador" to
                    "Sala abierta en el puerto ${LINK_TCP_PORT}. ${state.role.label()}. " +
                "Cuando el otro se una, la conexión se hará sola."
        is LinkState.Linked ->
            "Enlazado" to
                    "Los dos estáis conectados por la red. Ya puedes cargar el juego."
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SteamSurface1)
            .border(1.dp, SteamBorder, RoundedCornerShape(14.dp))
            .padding(16.dp)
            .testTag("card_link_status"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text       = title,
            style      = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color      = when (state) {
                is LinkState.Linked -> SteamGreen
                is LinkState.Waiting -> SteamOrange
                LinkState.Inactive -> SteamTextPrimary
            }
        )
        Text(
            text  = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = SteamTextSecondary
        )
    }
}

@Composable
private fun ActivePanel(
    state   : LinkState,
    onClose : () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state is LinkState.Linked) {
            Text(
                "Vuelve a la biblioteca y carga el juego. Los dos jugadores deben cargar el mismo título.",
                style = MaterialTheme.typography.bodySmall,
                color = SteamTextMuted
            )
        }
        Button(
            onClick   = onClose,
            shape     = RoundedCornerShape(12.dp),
            colors    = ButtonDefaults.buttonColors(
                containerColor = SteamSurface3,
                contentColor   = SteamTextPrimary
            ),
            contentPadding = PaddingValues(vertical = 12.dp),
            modifier  = Modifier
                .fillMaxWidth()
                .testTag("btn_link_disconnect")
        ) {
            Icon(
                Icons.Default.Refresh,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Desconectar", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun RoomRow(
    room    : DiscoveredRoom,
    enabled : Boolean,
    onJoin  : () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SteamSurface1)
            .border(1.dp, SteamBorder, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onJoin)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Person,
            contentDescription = null,
            tint   = SteamTextSecondary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text       = room.name,
                style      = MaterialTheme.typography.bodyMedium,
                color      = SteamTextPrimary,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis
            )
            Text(
                text  = "${room.address}:${room.port}",
                style = MaterialTheme.typography.bodySmall,
                color = SteamTextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            "Unirse",
            style = MaterialTheme.typography.labelLarge,
            color = SteamCyan
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text       = text,
        style      = MaterialTheme.typography.titleSmall,
        color      = SteamTextPrimary,
        fontWeight = FontWeight.SemiBold
    )
}

/**
 * Distintivo de enlace para las pantallas de biblioteca y juego.
 *
 * Es informativo: el enlace no se gobierna desde la partida, así que el punto
 * no es pulsable. `state` nulo en la barra superior (allí el botón ya está
 * justo al lado y sólo se marca el color).
 *
 * En [compact] no cabe una etiqueta sobre el juego: se dibuja sólo el icono,
 * con el halo pulsando despacio para que se note sin tapar la pantalla. El
 * texto se queda para la biblioteca, donde hay sitio de sobra y el usuario
 * viene a mirar la lista, no a jugar.
 */
@Composable
fun LinkBadge(
    state     : LinkState?,
    compact   : Boolean = false,
    modifier  : Modifier = Modifier
) {
    val linked  = state is LinkState.Linked
    val waiting = state is LinkState.Waiting
    val color   = if (linked) SteamGreen else if (waiting) SteamOrange else SteamTextSecondary
    val label   = when {
        linked -> "Enlazado"
        waiting -> "Esperando"
        else -> "Sin enlace"
    }

    if (compact) {
        // Halo que respira: un círculo que crece y se apaga alrededor del
        // icono. Con un icono tan pequeño, un color plano pasa desapercibido
        // en mitad de la partida; el pulso se lee de reojo sin tapar nada.
        val halo = rememberInfiniteTransition(label = "linkHalo")
        val pulse by halo.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(1800, easing = LinearEasing),
                RepeatMode.Restart
            ),
            label = "linkHaloProgress"
        )
        Box(
            modifier = modifier
                .size(30.dp)
                .clip(CircleShape)
                // Fondo apenas perceptible: el halo tiene que verse sobre
                // un juego claro sin dejar un círculo opaco encima.
                .background(color.copy(alpha = 0.10f))
                .testTag("badge_link"),
            contentAlignment = Alignment.Center
        ) {
            if (linked) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .graphicsLayer {
                            scaleX = 0.5f + pulse * 0.6f
                            scaleY = 0.5f + pulse * 0.6f
                            alpha = (1f - pulse) * 0.45f
                        }
                        .clip(CircleShape)
                        .background(color)
                )
            }
            Icon(
                imageVector        = Icons.Default.Link,
                contentDescription = label,
                tint               = color,
                modifier           = Modifier.size(15.dp)
            )
        }
        return
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50.dp))
            .background(SteamSurface3)
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .testTag("badge_link"),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text     = label,
            style    = MaterialTheme.typography.labelSmall,
            color    = color,
            maxLines = 1
        )
    }
}

/** Nombre de sala por defecto: el modelo del dispositivo, que es lo que el otro
 *  reconocerá de un vistazo. */
private fun defaultRoomName(): String {
    val model = Build.MODEL?.trim().orEmpty()
    return if (model.isBlank()) DEFAULT_ROOM_NAME else "Sala de $model"
}

/** Cómo se llama cada papel en la interfaz. */
internal fun LinkRole.label(): String = when (this) {
    LinkRole.PARENT -> "Eres el anfitrión"
    LinkRole.CHILD -> "Estás como invitado"
    LinkRole.NONE -> "Sin enlace"
}
