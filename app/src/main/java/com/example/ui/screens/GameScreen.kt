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
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
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
import com.example.core.cheats.CheatEntry
import com.example.core.cheats.CheatOp
import com.example.core.cheats.CheatParser
import com.example.core.link.LinkState
import com.example.data.EmulatorSettings
import com.example.data.ROMItem
import com.example.data.SaveStateManager
import com.example.data.TouchControl
import com.example.data.TouchLayout
import com.example.input.GamepadInput
import com.example.ui.components.FrostSurface
import com.example.ui.components.ScreenRenderer
import com.example.ui.components.StylusLayer
import com.example.ui.components.TouchControllerOverlay
import com.example.ui.components.rememberPressScale
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
    cheats          : List<CheatEntry>,
    linkState       : LinkState,
    onBackToLobby   : () -> Unit,
    onUpdateSettings : (EmulatorSettings) -> Unit,
    onSaveCheats    : (List<CheatEntry>) -> Unit
) {
    val context      = LocalContext.current
    val activity     = context as? Activity
    val coroutineScope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current

    val isDeviceLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    var isManualLandscape by remember { mutableStateOf(false) }
    val isLandscape = isDeviceLandscape || isManualLandscape

    // El contador de fotogramas es un `State` y no un `Long` suelto: así lo lee
    // el dibujado del lienzo y no la composición de esta pantalla. Con un `Long`
    // cada fotograma recomponía GameScreen entera —barra, HUD y mandos— sesenta
    // veces por segundo, que es gastar el hilo de interfaz justo cuando hace
    // falta para dibujar.
    val frameTick         = remember { mutableStateOf(0L) }
    val fps               by core.currentFps.collectAsState()
    var isFastForwardActive by remember { mutableStateOf(false) }

    var showSaveStateDialog  by remember { mutableStateOf(false) }
    var showSettingsDialog   by remember { mutableStateOf(false) }
    var showCheatDialog      by remember { mutableStateOf(false) }
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

    // Los trucos se empujan al núcleo cada vez que la lista cambia, y también
    // al entrar en la partida. Es el único sitio desde el que se hace, para que
    // no haya dos caminos que puedan desincronizarse: el núcleo recuerda la
    // lista y la reenvía tras cada `loadRom`.
    LaunchedEffect(cheats) {
        core.setCheats(compileCheats(cheats))
    }

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
    LaunchedEffect(currentRom) { core.start { frameTick.value++ } }

    LaunchedEffect(settings.audioEnabled, settings.audioVolume, settings.fastForwardAudio) {
        core.audioEnabled = settings.audioEnabled
        core.audioVolume  = settings.audioVolume
        core.fastForwardAudio = settings.fastForwardAudio
    }
    LaunchedEffect(settings.defaultFastForwardSpeed, isFastForwardActive) {
        core.fastForwardSpeed =
            if (isFastForwardActive) settings.defaultFastForwardSpeed else 1.0f
    }

    var connectedGamepads by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(showQuickMenuSheet) {
        if (showQuickMenuSheet) connectedGamepads = GamepadInput.connectedGamepads()
    }

    // La Nintendo DS comienza apaisada, pero el botón de rotación permite
    // cambiar a la disposición vertical de pantallas apiladas.
    LaunchedEffect(core.console) {
        if (core.console.prefersLandscape) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            core.stop()
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    fun toggleLandscapeMode() {
        val targetLandscape = !isLandscape
        isManualLandscape = targetLandscape
        activity?.requestedOrientation = if (targetLandscape)
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    // Zona de mandos en vertical, medida en píxeles y con el mismo origen que
    // la raíz.
    //
    // La necesita el overlay para saber cuál es su destino de vertical: el
    // reparto de fábrica está escrito en fracciones de la zona, y en vertical esa
    // zona es sólo la mitad de abajo. Se mide aquí y no dentro del overlay
    // porque depende de los pesos de la columna y del margen de la barra de
    // sistema, que quien compone es quien conoce.
    var portraitControlArea by remember { mutableStateOf(Rect.Zero) }

    // Raíz
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SteamBg)
    ) {
        if (isLandscape) {
            // Horizontal a pantalla completa
            Box(modifier = Modifier.fillMaxSize()) {
                // Dónde ha quedado la imagen del juego. Lo publica
                // `ScreenRenderer` y lo consume la capa de puntero de la DS.
                var contentRect by remember { mutableStateOf<Rect?>(null) }

                ScreenRenderer(
                    bitmap          = core.frameBitmap,
                    videoFilter     = settings.videoFilter,
                    aspectRatioMode = settings.aspectRatio,
                    fps             = fps,
                    fastForwardSpeed = if (isFastForwardActive) settings.defaultFastForwardSpeed else 1.0f,
                    showFps         = settings.showFpsCounter,
                    frameTick       = frameTick,
                    stackScreensVertically = false,
                    // El puntero necesita saber dónde quedó la imagen: es el
                    // único que ha decidido el encuadre, el escalado y el modo
                    // de aspecto. En Game Boy y GBA no hay puntero y se ignora.
                    onContentRect   = { contentRect = it },
                    modifier        = Modifier.fillMaxSize()
                )

                // Pantalla táctil de la Nintendo DS. Se coloca entre la imagen
                // y los mandos: los mandos son opacos y se quedan con lo suyo.
                if (!editingTouchControls && !settings.hideTouchControls) {
                    StylusLayer(
                        core = core,
                        panelWidth = core.console.panelWidth,
                        panelHeight = core.console.screenHeight,
                        contentRect = contentRect,
                        stylusPanel = core.console.stylusPanelIndex,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                if (!settings.hideTouchControls && !editingTouchControls) {
                    TouchControllerOverlay(
                        core                   = core,
                        opacity                = settings.controllerOpacity,
                        scale                  = settings.controllerScale,
                        hapticFeedbackEnabled  = settings.hapticFeedback,
                        layout                 = settings.touchLayout,
                        isLandscape            = true,
                        showShoulderButtons    = core.console.supportsShoulderButtons,
                        showFaceButtons        = core.console.supportsFaceButtonsXY,
                        isNintendoDs          = core.console.hasTwoScreens,
                        // En pantalla completa el mando va sobre el juego, así
                        // que su destino es el overlay entero. No hace falta
                        // medir nada: el área es el propio overlay.
                        portraitArea = Rect.Zero,
                        modifier               = Modifier.fillMaxSize()
                    )
                }

                // Distintivo de enlace, debajo del botón L.
                //
                // Apagado por defecto y a elección del usuario: en horizontal el
                // botón L de los controles táctiles ocupa la esquina
                // superior izquierda, así que el distintivo va justo debajo de
                // él (los hombros por defecto miden 74x34 dp con 24/12 de
                // margen) en vez de encima, donde quedaba tapado.
                //
                // Sólo aparece si hay algo que avisar: sin enlace no hay nada
                // que recordar a mitad de una partida.
                if (settings.showLinkBadgeInFullscreen &&
                    core.console.supportsLinkCable &&
                    linkState !is LinkState.Inactive
                ) {
                    LinkBadge(
                        state   = linkState,
                        compact = true,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(top = 64.dp, start = 24.dp)
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
                        // El mismo reflejo que los mandos, aquí más marcado
                        // porque la píldora es lo único que flota sobre el juego
                        // y sin él se leería como un icono suelto.
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
            //
            // El overlay de mandos va superpuesto a la columna, no dentro de
            // ella: es una capa que ocupa la pantalla entera en los dos modos y
            // coloca cada botón dentro del área que le toca. Como hijo de la
            // `Column`, un `fillMaxSize()` se comería el alto restante y dejaría
            // sin espacio las dos cajas de abajo —la pantalla del juego con
            // cero de alto— que es a donde va el negro.
            Box(modifier = Modifier.fillMaxSize()) {
            var contentRect by remember { mutableStateOf<Rect?>(null) }
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
                        // Durante la edición la barra se apaga. Sus botones
                        // quedan bajo los tres de la edición y su texto se leía de
                        // través; el `alpha` va después del fondo para que la
                        // tira siga siendo opaca, y antes de los hijos, que son
                        // los únicos que se ocultan. El alto no cambia —`alpha` no
                        // toca el layout—, así que el área de mandos no se mueve
                        // ni al entrar ni al salir.
                        .alpha(if (editingTouchControls) 0f else 1f)
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
                            //
                            // El punto y el número cambian de color según el
                            // ritmo: en verde la partida va a la velocidad del
                            // cartucho, en ámbar se está cayendo, y el número
                            // se desliza al cambiar en vez de aparecer de golpe.
                            // Con un contador fijo, una caída de cinco fotogramas
                            // por segundo pasaba inadvertida, que es justo el
                            // dato que uno mira esta barra para ver.
                            val fpsColor = fpsStatusColor(fps)
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(fpsColor)
                            )
                            AnimatedContent(
                                targetState = fps,
                                transitionSpec = {
                                    // Entra por abajo y sale por arriba: el número
                                    // parece subir por su sitio en vez de
                                    // parpadear, y el parpadeo es justo lo que
                                    // convierte un dato útil en ruido.
                                    (slideInVertically { it / 2 } + fadeIn(tween(160)))
                                .togetherWith(
                                    slideOutVertically { -it / 2 } + fadeOut(tween(160))
                                )
                                },
                                label = "fpsCounter"
                            ) { value ->
                                Text(
                                    text  = "$value FPS",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = fpsColor,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text("·", color = SteamTextMuted, fontSize = 10.sp)
                            Text(
                                text  = currentRom.formattedSize,
                                style = MaterialTheme.typography.labelSmall,
                                color = SteamTextMuted
                            )
                        }
                    }

                    // Distintivo de enlace, con su texto.
                    //
                    // Informativo, no pulsable: el enlace se gobierna desde la
                    // biblioteca, no en plena partida, y un botón aquí que
                    // saltara a otra pantalla con el juego corriendo sería peor
                    // que no tenerlo.
                    //
                    // Sólo cuando hay algo que avisar: la pantalla de la
                    // biblioteca tiene sitio y el estado importa; en partida
                    // suena más a aviso de sistema que a información útil.
                    if (core.console.supportsLinkCable && linkState !is LinkState.Inactive) {
                        LinkBadge(state = linkState)
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
                        .weight(if (core.console.hasTwoScreens) 2.4f else 1.0f)
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
                        stackScreensVertically = core.console.hasTwoScreens,
                        onContentRect   = { contentRect = it },
                        modifier        = Modifier.fillMaxSize()
                    )
                }

                // El fondo del mando: un degradado y no un negro plano.
                //
                // Media pantalla en negro deja la zona muerta y hace que el mando
                // parezca un dibujo encima de la nada en vez de un panel. El halo
                // del acento va detrás, difuminado, para que la zona se lea como
                // superficie y no como el vacío.
                val controlBackdrop = rememberControlAreaBackdrop()
                val accentGlow = rememberAccentGlow()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(if (core.console.hasTwoScreens) 0.9f else 1.3f)
                        .padding(bottom = 12.dp)
                        .background(controlBackdrop)
                        .onGloballyPositioned { coords ->
                            val bounds = coords.boundsInRoot()
                            val measured = Rect(bounds.left, bounds.top, bounds.right, bounds.bottom)
                            // Sólo se escribe cuando cambia de verdad: el
                            // callback salta en cada pasada de layout y una
                            // escritura sin novelty provocaría recomposición en
                            // bucle.
                            if (measured != portraitControlArea) portraitControlArea = measured
                        }
                )

            }

            if (core.console.hasStylus && !editingTouchControls) {
                StylusLayer(
                    core = core,
                    panelWidth = core.console.panelWidth,
                    panelHeight = core.console.screenHeight,
                    contentRect = contentRect,
                    stylusPanel = core.console.stylusPanelIndex,
                    stackScreensVertically = true,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Mandos táctiles
            //
            // Van **fuera** de la columna, como capa hermana suya, y no dentro.
            // Un `fillMaxSize()` dentro de una `Column` no significa "lleno el
            // hueco que me dejan", sino "me quedo con todo el alto que sobre":
            // se llevaba por delante el espacio de los dos pesos de arriba y la
            // caja de la pantalla del juego acababa con cero píxeles de alto,
            // que es exactamente el negro que se veía. Como hermana, la columna
            // reparte su alto como siempre y los mandos se dibujan encima.
            //
            // El área donde caen en vertical se la dice [portraitControlArea],
            // que mide la caja del mando; el overlay ya no necesita estar dentro
            // de ella para saber dónde va cada botón.
            if (!settings.hideTouchControls && !editingTouchControls) {
                TouchControllerOverlay(
                    core                  = core,
                    opacity               = settings.controllerOpacity,
                    scale                 = settings.controllerScale,
                    hapticFeedbackEnabled = settings.hapticFeedback,
                    layout                = settings.touchLayout,
                    isLandscape           = false,
                    showShoulderButtons   = core.console.supportsShoulderButtons,
                    showFaceButtons       = core.console.supportsFaceButtonsXY,
                    isNintendoDs         = core.console.hasTwoScreens,
                    portraitArea          = portraitControlArea,
                    modifier              = Modifier.fillMaxSize()
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
                    showShoulderButtons = core.console.supportsShoulderButtons,
                    showFaceButtons = core.console.supportsFaceButtonsXY,
                    isNintendoDs = core.console.hasTwoScreens,
                    // En vertical se pasa la zona de mandos para que los botones
                    // se dibujen en su sitio de juego y no repartidos por toda la
                    // pantalla: se está moviendo lo que se verá al jugar, no otra
                    // cosa. En horizontal no hace falta, porque la zona ya es la
                    // pantalla entera.
                    portraitArea  = portraitControlArea,
                    selectedControl = selectedTouchControl,
                    onSelectControl = { selectedTouchControl = it },
                    onPlacementChange = { control, placement ->
                        // Sólo se toca el mapa de la orientación que se está
                        // editando: mover un botón en vertical no puede cambiar
                        // también su sitio en pantalla completa.
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
                            // editor. Se vacía sólo la orientación que se está
                            // editando: restablecer en vertical no puede tocar
                            // también el reparto de pantalla completa.
                            draftTouchLayout = draftTouchLayout.with(isLandscape, emptyMap())
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
                    // El editor está disponible en las dos orientaciones y cada una
                    // guarda su propio reparto: lo que se mueve aquí sólo cambia
                    // aquí. Mover botones en vertical es la única forma de
                    // arreglarlos en una pantalla estrecha, donde el reparto de
                    // fábrica deja poco hueco.
                    SteamMenuTile(
                        icon     = Icons.Default.OpenWith,
                        title    = "Controles",
                        subtitle = if (isLandscape) {
                            "Mover y tamaño (horizontal)"
                        } else {
                            "Mover y tamaño (vertical)"
                        },
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
                    // Los trucos de memoria son cosa del núcleo de GBA: el de Game
                    // Boy descarta la lista, así que el botón no se ofrece en vez
                    // de dejar añadir códigos que no van a hacer nada.
                    if (core.console.supportsMemoryCheats) {
                        SteamMenuTile(
                            icon     = Icons.Default.BugReport,
                            title    = "Trucos",
                            subtitle = cheatsSummary(cheats),
                            modifier = Modifier.weight(1f),
                            onClick  = {
                                showQuickMenuSheet = false
                                showCheatDialog = true
                                // Como en el resto de diálogos que se abren desde el
                                // menú, la partida se queda pausada detrás para que
                                // ningún frame mueva la partida mientras se edita.
                                core.pause()
                            }
                        )
                    }
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
                        "para cambiar su tamaño. Los cambios valen sólo para " +
                        if (isLandscape) "la pantalla completa." else "este modo vertical."
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

    // Diálogo de trucos. Cada cambio recompila la lista y la manda al núcleo al
    // instante: marcar un truco tiene que notarse sin reiniciar la partida.
    if (showCheatDialog) {
        CheatDialog(
            entries  = cheats,
            onSave   = { updated ->
                // El `LaunchedEffect` de arriba empuja la lista nueva al núcleo;
                // aquí sólo se guarda.
                onSaveCheats(updated)
            },
            onDismissRequest = {
                showCheatDialog = false
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
            // El editor está en las dos orientaciones y cada una guarda lo suyo.
            onEditTouchLayout = {
                // El diálogo se cierra antes de abrir el editor. Si no, esta
                // ventana se quedaría encima del juego y los botones no se
                // podrían ni ver ni mover.
                showSettingsDialog = false
                draftTouchLayout = settings.touchLayout
                selectedTouchControl = null
                showTouchHelp = true
            }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Trucos
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Convierte una lista de trucos guardados en operaciones para el núcleo.
 *
 * Sólo entran los que están activos y sólo si su código se entiende entero: una
 * lista a medias dejaría escrituras sin la condición que las gobierna, que es
 * peor que no aplicar ese truco. Los que no se entienden se dejan guardados
 * (para que el usuario pueda corregirlos) pero fuera de la lista activa.
 */
private fun compileCheats(entries: List<CheatEntry>): List<CheatOp> =
    entries.asSequence()
        .filter { it.enabled }
        .mapNotNull { CheatParser.parse(it.source).takeIf { result -> result.accepted } }
        .flatMap { it.ops }
        .toList()

/**
 * Resumen para el subtítulo del tile: cuántos trucos hay y cuántos están
 * marcados. Con la lista vacía se dice "Añadir", que es la acción útil.
 */
private fun cheatsSummary(entries: List<CheatEntry>): String = when {
    entries.isEmpty() -> "Añadir"
    else -> {
        val active = entries.count { it.enabled }
        if (active == 0) "${entries.size} guardado${if (entries.size == 1) "" else "s"}"
        else "$active de ${entries.size}"
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

/**
 * Botón del HUD de pantalla completa.
 *
 * Un `IconButton` a secas se leía como un icono pegado al juego. Éste lleva el
 * mismo material que los mandos —vidrio esmerilado con su relieve— y además se
 * hunde al pulsarlo: la píldora es la única superficie que se usa con una sola
 * mano mientras se mira la partida, y un botón que no responde al dedo es media
 * interacción perdida.
 */
/**
 * Estado de la barra de FPS por ritmo, y el color con que se cuenta.
 *
 * El umbral no es la cifra teórica (59,7 Hz) sino lo que el usuario nota: por
 * debajo de la mitad de la cadencia, los scrolls y los saltos ya se ven. Con 30
 * de margen, un móvil que va sobrado sigue en verde en vez de dar la alerta por
 * dos fotogramas de diferencia.
 */
private fun fpsStatusColor(fps: Int): Color = when {
    fps >= 50 -> SteamGreen
    fps >= 30 -> SteamOrange
    else -> SteamPink
}

@Composable
private fun GameHudButton(
    icon    : androidx.compose.ui.graphics.vector.ImageVector,
    desc    : String,
    tint    : Color = SteamTextPrimary,
    onClick : () -> Unit
) {
    // La pulsación se lee del propio `interactionSource` en vez de contar con un
    // detector aparte: así el hundimiento dura exactamente lo que el dedo, con
    // el mismo reloj que el clic que dispara la acción.
    val interaction = remember { MutableInteractionSource() }
    val isPressed by interaction.collectIsPressedAsState()
    val pressScale = rememberPressScale(isPressed)
    val skin = rememberFrostSkin(ControlFamily.SYSTEM, isPressed)

    FrostSurface(
        shape = CircleShape,
        skin = skin,
        modifier = Modifier
            .size(36.dp)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            // Sin `indication`: el propio hundimiento y el cambio de material
            // son la respuesta, y encima vendría el ripple plano de Material.
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            ),
        content = {
            Icon(
                imageVector        = icon,
                contentDescription = desc,
                tint               = tint,
                modifier           = Modifier.size(19.dp)
            )
        }
    )
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
