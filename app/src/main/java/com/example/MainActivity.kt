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
import com.example.data.EmulatorSettings
import com.example.data.ROMItem
import com.example.data.ROMManager
import com.example.data.SaveStateManager
import com.example.data.SettingsRepository
import com.example.input.GamepadInput
import com.example.ui.screens.GameScreen
import com.example.ui.screens.LobbyScreen
import com.example.ui.theme.ASGbaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Main Activity for AS GBA Emulator.
 * Provides native hardware Gamepad plug & play support (InputManager API)
 * for GameSir, Xbox, DualSense, 8BitDo controllers, and manages emulation lifecycle.
 *
 * Developed by Andrés Socorro.
 */
class MainActivity : ComponentActivity() {

    private lateinit var gbaCore: GBACore
    private lateinit var romManager: ROMManager
    private lateinit var saveStateManager: SaveStateManager
    private lateinit var settingsRepository: SettingsRepository

    private var activeRom by mutableStateOf<ROMItem?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        gbaCore = GBACore(applicationContext)
        romManager = ROMManager(applicationContext)
        saveStateManager = SaveStateManager(applicationContext)
        settingsRepository = SettingsRepository(applicationContext)

        setContent {
            val settings by settingsRepository.settings.collectAsState()

            // Cambiar la asignación con un botón ya pulsado lo dejaría clavado en
            // el núcleo: la tecla recién desasignada nunca vuelve a enviar un
            // evento de "soltar", porque ya nadie la escucha. Se sueltan todos.
            LaunchedEffect(settings.gamepadMapping) {
                GamepadInput.releaseAll(gbaCore)
            }

            ASGbaTheme {
                AnimatedContent(
                    targetState = activeRom,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "ScreenTransition",
                    modifier = Modifier.fillMaxSize()
                ) { targetRom ->
                    if (targetRom == null) {
                        LobbyScreen(
                            romManager = romManager,
                            settings = settings,
                            onLaunchGame = { item ->
                                launchGame(item)
                            },
                            onUpdateSettings = { updated ->
                                settingsRepository.updateSettings(updated)
                            }
                        )
                    } else {
                        GameScreen(
                            core = gbaCore,
                            currentRom = targetRom,
                            settings = settings,
                            saveStateManager = saveStateManager,
                            onBackToLobby = {
                                // Un botón mantenido al salir de la partida
                                // seguiría pulsado al volver a entrar.
                                GamepadInput.releaseAll(gbaCore)
                                activeRom = null
                            },
                            onUpdateSettings = { updated ->
                                settingsRepository.updateSettings(updated)
                            }
                        )
                    }
                }
            }
        }
    }

    private fun launchGame(item: ROMItem) {
        lifecycleScope.launch {
            // Leer una ROM de hasta 32/64 MB y prepararla en el núcleo ocupa su
            // tiempo: hacerlo en el hilo principal congelaba la interfaz y podía
            // disparar un ANR. Se hace fuera y se vuelve al hilo principal sólo
            // para publicar el estado que lee Compose.
            val success = withContext(Dispatchers.IO) {
                // Antes de cargar: si la carga o el primer fotograma dejaran la
                // RAM de cartucho modificada, ya hay quién la recoja.
                gbaCore.onBatterySave = { data -> romManager.saveBattery(item.id, data) }
                val bytes = romManager.loadRomBytes(item)
                if (bytes == null) {
                    false
                } else {
                    // La partida guardada se inyecta con la propia carga, que es
                    // lo que el juego relee por su cuenta al arrancar.
                    gbaCore.loadRomBytes(bytes, null, romManager.loadBattery(item.id))
                }
            }
            if (success) {
                activeRom = item
            } else {
                Toast.makeText(
                    this@MainActivity,
                    "No se pudo cargar la ROM. Vuelve a añadirla desde la biblioteca.",
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
