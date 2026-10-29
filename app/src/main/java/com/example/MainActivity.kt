package com.example

import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.example.core.GBACore
import com.example.core.link.LinkManager
import com.example.data.CheatRepository
import com.example.data.EmulatorSettings
import com.example.data.ROMItem
import com.example.data.ROMManager
import com.example.data.SaveStateManager
import com.example.data.SettingsRepository
import com.example.input.GamepadInput
import com.example.ui.screens.GameScreen
import com.example.ui.screens.LinkScreen
import com.example.ui.screens.LobbyScreen
import com.example.ui.theme.ASGbaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Destino de la navegación principal.
 *
 * Son datos y no sólo banderas sueltas porque la transición de Compose compara
 * por igualdad: con `ROMItem` y `Boolean` por separado, abrir el enlace y
 * volver a la biblioteca darían destinos idénticos y Compose se saltaría la
 * animación.
 */
private sealed interface Screen {
    data object Lobby : Screen
    data object Link : Screen
    data class Game(val rom: ROMItem) : Screen
}

/**
 * Actividad principal de AS GBA Emulator.
 *
 * Da soporte nativo a mandos por hardware (API InputManager) para GameSir,
 * Xbox, DualSense y 8BitDo, y gobierna el ciclo de vida de la emulación.
 *
 * Interfaz y fachada: AS GBA Emulator, de Andrés Socorro.
 */
class MainActivity : ComponentActivity() {

    private lateinit var gbaCore: GBACore
    private lateinit var romManager: ROMManager
    private lateinit var saveStateManager: SaveStateManager
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var cheatRepository: CheatRepository
    private lateinit var linkManager: LinkManager

    private var activeRom by mutableStateOf<ROMItem?>(null)

    /**
     * Pantalla de enlace en primer plano.
     *
     * El enlace se abre aquí, en la biblioteca, y no con la partida en marcha:
     * así el jugador elige a quién espera antes de decidir qué juego carga, y
     * el otro tiene tiempo de sentarse. Por eso es una bandera aparte de
     * [activeRom] y no una tercera versión del mismo destino.
     */
    private var showLinkScreen by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        gbaCore = GBACore(applicationContext)
        romManager = ROMManager(applicationContext)
        saveStateManager = SaveStateManager(applicationContext)
        settingsRepository = SettingsRepository(applicationContext)
        cheatRepository = CheatRepository(applicationContext)
        linkManager = LinkManager(gbaCore)

        setContent {
            val settings by settingsRepository.settings.collectAsState()
            // La lista de trucos se observa como estado y no se lee suelta al
            // abrir la partida: si se leyera una vez, marcar una casilla no
            // llegaría a la pantalla hasta el siguiente reinicio.
            val cheatsByRom by cheatRepository.cheats.collectAsState()

            // El estado del enlace también se observa una vez y se pasa ya
            // resuelto a las pantallas: el distintivo de la biblioteca y el de
            // la partida leen el mismo valor, sin que cada una tenga que
            // suscribirse al gestor por su cuenta.
            val linkState by linkManager.state.collectAsState()

            // Cambiar la asignación con un botón ya pulsado lo dejaría clavado en
            // el núcleo: la tecla recién desasignada nunca vuelve a enviar un
            // evento de "soltar", porque ya nadie la escucha. Se sueltan todos.
            LaunchedEffect(settings.gamepadMapping) {
                GamepadInput.releaseAll(gbaCore)
            }

            ASGbaTheme(accent = settings.accentTheme) {
                // El destino de la transición son tres pantallas y no dos. Se
                // decide antes de componer para que el enlace se pueda abrir
                // desde la biblioteca sin que la pantalla de enlace se monte y
                // se desmonte en el mismo fotograma.
                val destination: Screen = when {
                    activeRom != null -> Screen.Game(activeRom!!)
                    showLinkScreen -> Screen.Link
                    else -> Screen.Lobby
                }

                AnimatedContent(
                    targetState = destination,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "ScreenTransition",
                    modifier = Modifier.fillMaxSize()
                ) { target ->
                    when (target) {
                        Screen.Lobby -> LobbyScreen(
                            romManager = romManager,
                            settings = settings,
                            linkState = linkState,
                            onLaunchGame = { item ->
                                launchGame(item)
                            },
                            onUpdateSettings = { updated ->
                                settingsRepository.updateSettings(updated)
                            },
                            onOpenLink = {
                                showLinkScreen = true
                            }
                        )

                        Screen.Link -> LinkScreen(
                            linkManager = linkManager,
                            onBack = {
                                showLinkScreen = false
                            }
                        )

                        is Screen.Game -> {
                            val rom = target.rom
                            GameScreen(
                                core = gbaCore,
                                currentRom = rom,
                                settings = settings,
                                saveStateManager = saveStateManager,
                                cheats = cheatsByRom[rom.id].orEmpty(),
                                linkState = linkState,
                                onBackToLobby = {
                                    // Un botón mantenido al salir de la partida
                                    // seguiría pulsado al volver a entrar.
                                    GamepadInput.releaseAll(gbaCore)
                                    activeRom = null
                                },
                                onUpdateSettings = { updated ->
                                    settingsRepository.updateSettings(updated)
                                },
                                onSaveCheats = { entries ->
                                    cheatRepository.saveForRom(rom.id, entries)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun launchGame(item: ROMItem) {
        // La lista se lee antes de lanzar la carga para que el diálogo de trucos
        // tenga algo que enseñar desde el primer frame.
        cheatRepository.loadForRom(item.id)
        lifecycleScope.launch {
            // Leer o extraer una ROM grande y prepararla en el núcleo ocupa
            // tiempo y memoria nativa: hacerlo en el hilo principal congelaba
            // la interfaz y podía disparar un ANR. Se hace fuera y se vuelve al
            // hilo principal sólo para publicar el estado que lee Compose.
            val success = withContext(Dispatchers.IO) {
                // Antes de cargar: si la carga o el primer fotograma dejaran la
                // RAM de cartucho modificada, ya hay quién la recoja.
                gbaCore.onBatterySave = { data -> romManager.saveBattery(item.id, data) }
                val romFile = romManager.loadRomFile(item)
                if (romFile == null) {
                    false
                } else {
                    // La partida guardada se inyecta con la propia carga, que es
                    // lo que el juego relee por su cuenta al arrancar. La consola
                    // se decide una sola vez aquí y viaja al núcleo: la extensión
                    // del nombre manda y los bytes deciden el resto (una ROM de
                    // Game Boy sin extensión reconocible sigue siendo GB).
                    gbaCore.loadRomFile(
                        romFile,
                        romManager.loadBattery(item.id),
                        romManager.detectConsole(item.fileName, romFile),
                    )
                }
            }
            if (success) {
                activeRom = item
            } else {
                Toast.makeText(
                    this@MainActivity,
                    gbaCore.lastLoadError
                        ?: "No se pudo cargar la ROM. Vuelve a añadirla desde la biblioteca.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        gbaCore.pause()
        // Ir al segundo plano es el último momento fiable: si el proceso muere
        // después, lo único que se pierde es lo escrito desde entonces.
        gbaCore.flushBattery()
    }

    override fun onResume() {
        super.onResume()
        if (activeRom != null) {
            gbaCore.resume()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        gbaCore.release()
    }

    /**
     * Entrada de mandos Bluetooth en la ventana: botones, cruceta física y
     * palanca izquierda llegan por [KeyEvent] y [MotionEvent].
     *
     * Se traduce aquí, y no en el Compose de la pantalla de juego, porque estos
     * eventos se entregan a la ventana antes de que exista la composición.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (activeRom == null) return super.dispatchKeyEvent(event)
        val mapping = settingsRepository.settings.value.gamepadMapping
        return if (GamepadInput.applyKeyEvent(gbaCore, event, mapping)) true
        else super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (activeRom == null) return super.dispatchGenericMotionEvent(event)
        val mapping = settingsRepository.settings.value.gamepadMapping
        return if (GamepadInput.applyAxisEvent(gbaCore, event, mapping)) true
        else super.dispatchGenericMotionEvent(event)
    }
}
