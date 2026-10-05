package com.example.ui.screens

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.core.GBACore
import com.example.data.EmulatorSettings
import com.example.data.ROMItem
import com.example.data.SaveStateManager
import com.example.data.TouchControl
import com.example.data.TouchLayout
import com.example.input.GamepadInput
import com.example.ui.components.ScreenRenderer
import com.example.ui.components.TouchControllerOverlay
import com.example.ui.theme.*
import kotlinx.coroutines.launch

/**
 * Pantalla de juego, al estilo Big Picture de Steam.
 *
 * Barra superior compacta sobre fondo OLED y hoja inferior con la rejilla de
 * acciones.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameScreen(
    core            : GBACore,
    currentRom      : ROMItem,
    settings        : EmulatorSettings,
    saveStateManager : SaveStateManager,
    onBackToLobby   : () -> Unit,
    onUpdateSettings : (EmulatorSettings) -> Unit
) {
    val context      = LocalContext.current
    val activity     = context as? Activity
    val coroutineScope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current

    val isDeviceLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    var isManualLandscape by remember { mutableStateOf(false) }
    val isLandscape = isDeviceLandscape || isManualLandscape

    var frameTick         by remember { mutableStateOf(0L) }
    val fps               by core.currentFps.collectAsState()
    var isFastForwardActive by remember { mutableStateOf(false) }

    var showSaveStateDialog  by remember { mutableStateOf(false) }
    var showSettingsDialog   by remember { mutableStateOf(false) }
    var showQuickMenuSheet   by remember { mutableStateOf(false) }
    val slots                by saveStateManager.slots.collectAsState()

    // Recolocador de los botones táctiles.
    //
    // El borrador va aparte de los ajustes guardados a propósito: mientras se
    // arrastra y se pellizca no se escribe nada, y sólo al pulsar Guardar se
    // decide. Cancelar y volver atrás, por tanto, no dejan el mando con los
    // botones donde el usuario los dejó sin querer.
    var editingTouchControls by remember { mutableStateOf(false) }
// La explicación se enseña antes de entrar, no mientras se edita: abajo hace
// falta sitio para Start y Select, que es justo lo que tapaba el recuadro.
var showTouchHelp by remember { mutableStateOf(false) }
    var draftTouchLayout    by remember { mutableStateOf(settings.touchLayout) }
    var selectedTouchControl by remember { mutableStateOf<TouchControl?>(null) }

    // Al rotar se entra y se sale del editor: una distribución editada en
    // horizontal no se aplica sola en vertical, porque cada orientación guarda la
    // suya y el usuario tiene que ver cuál está tocando.
    LaunchedEffect(isLandscape) {
        editingTouchControls = false
        draftTouchLayout = settings.touchLayout
    }

    // Inmersivo a pantalla completa en horizontal
    DisposableEffect(isLandscape) {
        val window = activity?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            if (isLandscape) {
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            val win = activity?.window
            if (win != null) {
                WindowCompat.getInsetsController(win, win.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    BackHandler {
        // El editor se cierra antes que el juego: si no, un "atrás" mientras se
        // está moviendo un botón sacaría de la partida de golpe.
        if (editingTouchControls) {
            draftTouchLayout = settings.touchLayout
            editingTouchControls = false
            selectedTouchControl = null
        } else if (isLandscape && isManualLandscape) {
            isManualLandscape = false
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            core.stop()
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            onBackToLobby()
        }
    }

    LaunchedEffect(currentRom.id) { saveStateManager.refreshSlots(currentRom.id) }
    LaunchedEffect(currentRom) { core.start { frameTick++ } }

    LaunchedEffect(settings.audioEnabled, settings.audioVolume) {
        core.audioEnabled = settings.audioEnabled
        core.audioVolume  = settings.audioVolume
    }
    LaunchedEffect(settings.defaultFastForwardSpeed, isFastForwardActive) {
        core.fastForwardSpeed =
            if (isFastForwardActive) settings.defaultFastForwardSpeed else 1.0f
    }

    var connectedGamepads by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(showQuickMenuSheet) {
        if (showQuickMenuSheet) connectedGamepads = GamepadInput.connectedGamepads()
    }

    DisposableEffect(Unit) {
        onDispose {
            core.stop()
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    fun toggleLandscapeMode() {
        isManualLandscape = !isManualLandscape
        activity?.requestedOrientation = if (isManualLandscape)
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    // Raíz
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SteamBg)
    ) {
        if (isLandscape) {
            // Horizontal a pantalla completa
            Box(modifier = Modifier.fillMaxSize()) {
                ScreenRenderer(
                    bitmap          = core.frameBitmap,
                    videoFilter     = settings.videoFilter,
                    aspectRatioMode = settings.aspectRatio,
                    fps             = fps,
                    fastForwardSpeed = if (isFastForwardActive) settings.defaultFastForwardSpeed else 1.0f,
                    showFps         = settings.showFpsCounter,
                    frameTick       = frameTick,
                    modifier        = Modifier.fillMaxSize()
                )

                if (!settings.hideTouchControls && !editingTouchControls) {
                    TouchControllerOverlay(
                        core                   = core,
                        opacity                = settings.controllerOpacity,
                        scale                  = settings.controllerScale,
                        hapticFeedbackEnabled  = settings.hapticFeedback,
                        layout                 = settings.touchLayout,
                        isLandscape            = true,
                        modifier               = Modifier.fillMaxSize()
                    )
                }

                // Píldora del HUD, arriba y centrada
                // Durante la edición de controles no se muestra: ahí no estorba
                // para tocar y su sitio lo ocupan los tres botones de la edición.
                if (!editingTouchControls) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp)
                        // Sombra suave para despegar la píldora del juego
                        .shadow(
                            elevation = 10.dp,
                            shape     = RoundedCornerShape(50.dp),
                            ambientColor = Color.Black,
                            spotColor   = Color.Black
                        )
                        .clip(RoundedCornerShape(50.dp))
                        // Base translúcida: asoma el juego por detrás, como el
                        // cristal de iOS, pero sin oscurecer los iconos
                        .background(SteamSurface1.copy(alpha = 0.72f))
                        // Brillo especular en la mitad superior
                        .background(
                            Brush.verticalGradient(
                                0f    to Color.White.copy(alpha = 0.16f),
                                0.55f to Color.White.copy(alpha = 0.04f),
                                1f    to Color.White.copy(alpha = 0f)
                            )
                        )
                        // Borde luminoso degradado: el filo que caracteriza al vidrio
                        .border(
                            width = 1.dp,
                            brush = Brush.verticalGradient(
                                listOf(
                                    Color.White.copy(alpha = 0.30f),
                                    Color.White.copy(alpha = 0.06f)
                                )
                            ),
                            shape = RoundedCornerShape(50.dp)
                        )
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(0.dp),
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    GameHudButton(icon = Icons.Default.ScreenRotation, desc = "Rotar") {
                        toggleLandscapeMode()
                    }
                    GameHudButton(
                        icon = Icons.Default.FastForward,
                        desc = "Fast-Forward",
                        tint = if (isFastForwardActive) SteamOrange else SteamTextSecondary
                    ) {
                        isFastForwardActive = !isFastForwardActive
                        core.fastForwardSpeed =
                            if (isFastForwardActive) settings.defaultFastForwardSpeed else 1.0f
                    }
                    GameHudButton(icon = Icons.Default.Save, desc = "Guardar") {
                        coroutineScope.launch {
                            saveToSlot(context, core, saveStateManager, currentRom.id, 1, "Guardado en Ranura 1")
                        }
                    }
                    GameHudButton(icon = Icons.Default.PhotoCamera, desc = "Captura") {
                        captureScreenshot(context, core, currentRom.displayTitle)
                    }
                    GameHudButton(icon = Icons.Default.MoreVert, desc = "Menú") {
                        core.pause()
                        showQuickMenuSheet = true
                    }
                }
                }
            }
        } else {
            // Vertical
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
            ) {
                // Barra superior
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SteamSurface1)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Botón de atrás
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(SteamSurface2)
                            .clickable {
                                core.stop()
                                onBackToLobby()
                            }
                            .testTag("btn_back_to_lobby"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector        = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Volver",
                            tint               = SteamTextPrimary,
                            modifier           = Modifier.size(18.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    // Datos de la partida
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text     = currentRom.displayTitle,
                            style    = MaterialTheme.typography.titleSmall,
                            color    = SteamTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            // Indicador de FPS
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(SteamGreen)
                            )
                            Text(
                                text  = "$fps FPS",
                                style = MaterialTheme.typography.labelSmall,
                                color = SteamGreen,
                                fontWeight = FontWeight.Bold
                            )
                            Text("·", color = SteamTextMuted, fontSize = 10.sp)
                            Text(
                                text  = currentRom.formattedSize,
                                style = MaterialTheme.typography.labelSmall,
                                color = SteamTextMuted
                            )
                        }
                    }

                    // Controles
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        GameTopBarButton(
                            icon = Icons.Default.StayCurrentLandscape,
                            tint = SteamCyan,
                            desc = "Pantalla Horizontal",
                            tag  = "btn_fullscreen_horizontal"
                        ) { toggleLandscapeMode() }

                        GameTopBarButton(
                            icon = Icons.Default.FastForward,
                            tint = if (isFastForwardActive) SteamOrange else SteamTextSecondary,
                            desc = "Acelerar",
                            tag  = "btn_fast_forward"
                        ) {
                            isFastForwardActive = !isFastForwardActive
                            core.fastForwardSpeed =
                                if (isFastForwardActive) settings.defaultFastForwardSpeed else 1.0f
                            Toast.makeText(
                                context,
                                if (isFastForwardActive) "Velocidad: ${settings.defaultFastForwardSpeed}x"
                                else "Velocidad normal (1x)",
                                Toast.LENGTH_SHORT
                            ).show()
                        }

                        GameTopBarButton(
                            icon = Icons.Default.MoreVert,
                            tint = SteamTextSecondary,
                            desc = "Opciones",
                            tag  = "btn_quick_menu"
                        ) {
                            core.pause()
                            showQuickMenuSheet = true
                        }
                    }
                }

                // Línea de acento bajo la barra
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(SteamCyan.copy(alpha = 0.4f), SteamBorder, Color.Transparent)
                            )
                        )
                )

                // Lienzo de la pantalla
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1.0f)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black)
                        .border(1.dp, SteamCyan.copy(alpha = 0.2f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    ScreenRenderer(
                        bitmap          = core.frameBitmap,
                        videoFilter     = settings.videoFilter,
                        aspectRatioMode = settings.aspectRatio,
                        fps             = fps,
                        fastForwardSpeed = if (isFastForwardActive) settings.defaultFastForwardSpeed else 1.0f,
                        showFps         = false,
                        frameTick       = frameTick,
                        modifier        = Modifier.fillMaxSize()
                    )
                }

                // Controles táctiles
                if (!settings.hideTouchControls && !editingTouchControls) {
                    TouchControllerOverlay(
                        core                  = core,
                        opacity               = settings.controllerOpacity,
                        scale                 = settings.controllerScale,
                        hapticFeedbackEnabled = settings.hapticFeedback,
                        layout                = settings.touchLayout,
                        isLandscape           = false,
                        modifier              = Modifier
                            .fillMaxWidth()
                            .weight(1.3f)
                            .padding(bottom = 12.dp)
                    )
                }
            }
        }

        // Editor de controles táctiles
        // Vive dentro del Box raíz y no en un Dialog: el Dialog de Compose abre
        // su propia ventana, se comería los toques y taparía justo lo que hay que
        // colocar. Aquí los botones se ven sobre el juego mientras se mueven.
        if (editingTouchControls) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f))
            ) {
                TouchControllerOverlay(
                    core          = core,
                    opacity       = settings.controllerOpacity,
                    scale         = settings.controllerScale,
                    // En el editor no vibra nada: no se está pulsando nada, y el
                    // zumbido en cada movimiento del botón cansa en seguida.
                    hapticFeedbackEnabled = false,
                    layout        = draftTouchLayout,
                    isLandscape   = isLandscape,
                    editing       = true,
                    selectedControl = selectedTouchControl,
                    onSelectControl = { selectedTouchControl = it },
                    onPlacementChange = { control, placement ->
                        val updated = draftTouchLayout
                            .forOrientation(isLandscape)
                            .toMutableMap()
                            .apply { put(control, placement) }
                        draftTouchLayout = draftTouchLayout.with(isLandscape, updated)
                    },
                    modifier      = Modifier.fillMaxSize()
                )

// Los tres botones, en versión minimalista: la explicación ya se
                // dio antes de entrar y en edición no se quiere tapar la parte
                // de abajo de la pantalla, que es justo donde Start y Select
                // están. En el sitio que dejaba libre la barra de juego.
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp)
                        .clip(RoundedCornerShape(50.dp))
                        .background(SteamSurface1.copy(alpha = 0.72f))
                        .background(
                            Brush.verticalGradient(
                                0f    to Color.White.copy(alpha = 0.16f),
                                0.55f to Color.White.copy(alpha = 0.04f),
                                1f    to Color.White.copy(alpha = 0f)
                            )
                        )
                        .border(
                            width = 1.dp,
                            brush = Brush.verticalGradient(
                                listOf(
                                    Color.White.copy(alpha = 0.30f),
                                    Color.White.copy(alpha = 0.06f)
                                )
                            ),
                            shape = RoundedCornerShape(50.dp)
                        )
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(0.dp),
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            onUpdateSettings(settings.copy(touchLayout = draftTouchLayout))
                            editingTouchControls = false
                            selectedTouchControl = null
                            core.resume()
                        },
                        modifier = Modifier.size(40.dp).testTag("btn_edit_save")
                    ) {
                        Icon(
                            imageVector        = Icons.Default.Check,
                            contentDescription = "Guardar",
                            tint               = SteamGreen,
                            modifier           = Modifier.size(20.dp)
                        )
                    }
                    IconButton(
                        onClick = {
                            // Vaciar el mapa es devolver los controles al reparto
                            // de fábrica: lo que no está guardado se dibuja con
                            // las mismas fórmulas que usaba la app antes del
                            // editor. Se vacían las dos orientaciones porque el
                            // editor sólo existe en horizontal.
                            draftTouchLayout = TouchLayout()
                        },
                        modifier = Modifier.size(40.dp).testTag("btn_edit_reset")
                    ) {
                        Icon(
                            imageVector        = Icons.Default.Refresh,
                            contentDescription = "Restablecer",
                            tint               = SteamTextPrimary,
                            modifier           = Modifier.size(20.dp)
                        )
                    }
                    IconButton(
                        onClick = {
                            draftTouchLayout = settings.touchLayout
                            editingTouchControls = false
                            selectedTouchControl = null
                            core.resume()
                        },
                        modifier = Modifier.size(40.dp).testTag("btn_edit_cancel")
                    ) {
                        Icon(
                            imageVector        = Icons.Default.Close,
                            contentDescription = "Cancelar",
                            tint               = SteamTextSecondary,
                            modifier           = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }

    // Menú rápido
    if (showQuickMenuSheet) {
        ModalBottomSheet(
            onDismissRequest = {
                showQuickMenuSheet = false
                core.resume()
            },
            containerColor = SteamSurface1,
            dragHandle = {
                Box(
                    modifier = Modifier
                        .padding(vertical = 10.dp)
                        .size(36.dp, 3.dp)
                        .clip(CircleShape)
                        .background(SteamBorder)
                )
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp)
            ) {
                // Cabecera con los datos de la partida
                Column {
                    Text(
                        text  = currentRom.displayTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = SteamTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text  = "${currentRom.gameCode} · ${currentRom.formattedSize}",
                            style = MaterialTheme.typography.bodySmall,
                            color = SteamTextSecondary
                        )
                        if (connectedGamepads.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(SteamGreenSoft)
                                    .padding(horizontal = 5.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    text  = "MANDO",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = SteamGreen,
                                    fontWeight = FontWeight.ExtraBold
                                )
}
        }

    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                HorizontalDivider(color = SteamBorder, thickness = 0.5.dp)
                Spacer(modifier = Modifier.height(16.dp))

                // Rejilla de acciones
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SteamMenuTile(
                        icon     = Icons.Default.Save,
                        title    = "Guardar",
                        subtitle = "Ranura 1",
                        modifier = Modifier.weight(1f),
                        onClick  = {
                            coroutineScope.launch {
                                saveToSlot(context, core, saveStateManager, currentRom.id, 1, "Guardado en Ranura 1")
                            }
                        }
                    )
                    SteamMenuTile(
                        icon     = Icons.Default.Download,
                        title    = "Cargar",
                        subtitle = "Ranura 1",
                        modifier = Modifier.weight(1f),
                        onClick  = {
                            coroutineScope.launch {
                                loadFromSlot(context, core, saveStateManager, currentRom.id, 1) {
                                    showQuickMenuSheet = false
                                    core.resume()
                                }
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SteamMenuTile(
                        icon     = Icons.Default.FolderZip,
                        title    = "Ranuras",
                        subtitle = "5 ranuras",
                        modifier = Modifier.weight(1f),
                        onClick  = {
                            showQuickMenuSheet = false
                            showSaveStateDialog = true
                        }
                    )
                    SteamMenuTile(
                        icon     = Icons.Default.PhotoCamera,
                        title    = "Captura",
                        subtitle = "Guardar imagen",
                        modifier = Modifier.weight(1f),
                        onClick  = {
                            showQuickMenuSheet = false
                            captureScreenshot(context, core, currentRom.displayTitle)
                        }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
// El editor sólo existe en horizontal. En vertical el
                    // reparto de los botones ya está resuelto y no hay nada que
                    // colocar, así que en ese modo Ajustes ocupa la fila entera.
                    if (isLandscape) {
                        SteamMenuTile(
                            icon     = Icons.Default.OpenWith,
                            title    = "Controles",
                            subtitle = "Mover y tamaño",
                            modifier = Modifier.weight(1f),
                            onClick  = {
                                // El editor no es un Dialog: el menú se cierra y el
                                // juego se queda pausado detrás para ver los botones
                                // donde van a caer.
showQuickMenuSheet = false
                                   draftTouchLayout = settings.touchLayout
                                   selectedTouchControl = null
                                   showTouchHelp = true
                            }
                        )
                    }
                    SteamMenuTile(
                        icon     = Icons.Default.Settings,
                        title    = "Ajustes",
                        subtitle = "Vídeo / Audio",
                        modifier = Modifier.weight(1f),
                        onClick  = {
                            showQuickMenuSheet = false
                            showSettingsDialog = true
                        }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SteamMenuTile(
                        icon     = Icons.Default.RestartAlt,
                        title    = "Reiniciar",
                        subtitle = "Consola",
                        modifier = Modifier.weight(1f),
                        onClick  = {
                            core.reset()
                            showQuickMenuSheet = false
                            core.resume()
                            Toast.makeText(context, "Consola reiniciada", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }
    }

    // Ayuda de la edición de controles. Se enseña una vez, antes de entrar:
    // dentro del editor no hay sitio para texto, y abajo están Start y Select.
    if (showTouchHelp) {
        AlertDialog(
            onDismissRequest = { showTouchHelp = false },
            containerColor = SteamSurface1,
            titleContentColor = SteamTextPrimary,
            textContentColor = SteamTextSecondary,
            title = { Text(text = "Editar los botones") },
            text = {
                Text(
                    text = "Arrastra los botones para moverlos y pellizca " +
                        "para cambiar su tamaño."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showTouchHelp = false
                    editingTouchControls = true
                }) {
                    Text(text = "Entendido", color = SteamCyan)
                }
            }
        )
    }

    // Diálogo de partidas guardadas
    if (showSaveStateDialog) {
        SaveStateDialog(
            slots      = slots,
            onSaveSlot = { slotIndex ->
                coroutineScope.launch {
                    saveToSlot(context, core, saveStateManager, currentRom.id, slotIndex, "Guardado en Ranura $slotIndex")
                }
            },
            onLoadSlot = { slotIndex ->
                coroutineScope.launch {
                    loadFromSlot(context, core, saveStateManager, currentRom.id, slotIndex) {
                        showSaveStateDialog = false
                        core.resume()
                    }
                }
            },
            onDismissRequest = {
                showSaveStateDialog = false
                core.resume()
            }
        )
    }

    // Diálogo de ajustes
    if (showSettingsDialog) {
        SettingsDialog(
            currentSettings  = settings,
            onSaveSettings   = { updated ->
                onUpdateSettings(updated)
                core.fastForwardSpeed =
                    if (isFastForwardActive) updated.defaultFastForwardSpeed else 1.0f
            },
            onDismissRequest = {
                showSettingsDialog = false
                core.resume()
            },
            // La carpeta de ROMs es cosa de la biblioteca: desde la partida no
            // hay dónde mostrar el resultado de un escaneo, así que aquí el
            // diálogo se abre sin esa sección. El resto de ajustes, incluido el
            // reasignado de botones, sí está disponible en ambos sitios.
            onPickRomFolder = null,
            onScanRomFolder = null,
            // Mismo criterio que en el menú: colocar botones sólo tiene sentido
            // en horizontal, así que en vertical la fila no se muestra siquiera.
            onEditTouchLayout = if (isLandscape) {
                {
                    // El diálogo se cierra antes de abrir el editor. Si no, esta
                    // ventana se quedaría encima del juego y los botones no se
                    // podrían ni ver ni mover.
showSettingsDialog = false
                       draftTouchLayout = settings.touchLayout
                       selectedTouchControl = null
                       showTouchHelp = true
                }
            } else null
        )
    }
}

// Piezas pequeñas del HUD

@Composable
private fun SteamActionButton(
    text     : String,
    modifier : Modifier = Modifier,
    primary  : Boolean  = false,
    onClick  : () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (primary) SteamCyan.copy(alpha = 0.22f) else SteamSurface2
            )
            .border(
                1.dp,
                if (primary) SteamCyan.copy(alpha = 0.55f) else SteamBorder,
                RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text      = text,
            color     = if (primary) SteamCyan else SteamTextPrimary,
            fontSize  = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun GameHudButton(
    icon    : androidx.compose.ui.graphics.vector.ImageVector,
    desc    : String,
    tint    : Color = SteamTextPrimary,
    onClick : () -> Unit
) {
    IconButton(
        onClick  = onClick,
        modifier = Modifier.size(36.dp)
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = desc,
            tint               = tint,
            modifier           = Modifier.size(19.dp)
        )
    }
}

@Composable
private fun GameTopBarButton(
    icon    : androidx.compose.ui.graphics.vector.ImageVector,
    tint    : Color,
    desc    : String,
    tag     : String = "",
    onClick : () -> Unit
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(SteamSurface2)
            .clickable { onClick() }
            .then(if (tag.isNotEmpty()) Modifier.testTag(tag) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = desc,
            tint               = tint,
            modifier           = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun SteamMenuTile(
    icon     : androidx.compose.ui.graphics.vector.ImageVector,
    title    : String,
    subtitle : String,
    modifier : Modifier = Modifier,
    onClick  : () -> Unit
) {
    Surface(
        onClick      = onClick,
        shape        = RoundedCornerShape(12.dp),
        color        = SteamSurface2,
        modifier     = modifier.border(1.dp, SteamBorder, RoundedCornerShape(12.dp))
    ) {
        Row(
            modifier          = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(SteamCyan.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector        = icon,
                    contentDescription = null,
                    tint               = SteamCyan,
                    modifier           = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text       = title,
                    style      = MaterialTheme.typography.labelLarge,
                    color      = SteamTextPrimary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text  = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = SteamTextSecondary
)
                }
            }
        }

    }

// Auxiliares de estado

/**
 * Guarda la partida en una ranura y avisa siempre del resultado.
 */
private suspend fun saveToSlot(
    context        : Context,
    core           : GBACore,
    saveStateManager : SaveStateManager,
    romId          : String,
    slotIndex      : Int,
    successMessage : String
) {
    val stateData = core.saveState()
    if (stateData == null) {
        Toast.makeText(context, "No se pudo capturar el estado", Toast.LENGTH_SHORT).show()
        return
    }
    val thumbnail = synchronized(core.frameBitmap) {
        core.frameBitmap.copy(Bitmap.Config.ARGB_8888, false)
    }
    val saved = saveStateManager.saveSlot(romId, slotIndex, stateData, thumbnail)
    Toast.makeText(
        context,
        if (saved) successMessage else "No se pudo guardar la partida",
        Toast.LENGTH_SHORT
    ).show()
}

/**
 * Captura la pantalla actual y la deja en la carpeta de imágenes de la app.
 *
 * Se copia el framebuffer bajo el mismo candado que usa la emulación: sin él, la
 * imagen puede salir a medio escribir y con franjas.
 */
private fun captureScreenshot(context: Context, core: GBACore, romTitle: String) {
    val bitmap = synchronized(core.frameBitmap) {
        core.frameBitmap.copy(Bitmap.Config.ARGB_8888, false)
    }
    val folder = File(context.getExternalFilesDir(null), "Screenshots").apply { mkdirs() }
    val stamp  = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
    val safeTitle = romTitle.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().replace(' ', '_')
        .ifBlank { "captura" }
    val file = File(folder, "ASGBA_${safeTitle}_$stamp.png")

    val saved = try {
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        true
    } catch (e: Exception) {
        Toast.makeText(context, "No se pudo guardar la captura", Toast.LENGTH_SHORT).show()
        false
    }
    if (saved) {
        Toast.makeText(context, "Captura guardada en Imágenes/ASGBA", Toast.LENGTH_LONG).show()
    }
}

/**
 * Carga una ranura y avisa del resultado.
 */
private suspend fun loadFromSlot(
    context          : Context,
    core             : GBACore,
    saveStateManager : SaveStateManager,
    romId            : String,
    slotIndex        : Int,
    onLoaded         : () -> Unit
) {
    val stateData = saveStateManager.loadSlot(romId, slotIndex)
    if (stateData == null) {
        Toast.makeText(context, "Ranura $slotIndex vacía", Toast.LENGTH_SHORT).show()
        return
    }
    val loaded = core.loadState(stateData)
    Toast.makeText(
        context,
        if (loaded) "Partida cargada de Ranura $slotIndex" else "Esa ranura no se pudo cargar",
        Toast.LENGTH_SHORT
    ).show()
    if (loaded) onLoaded()
}
