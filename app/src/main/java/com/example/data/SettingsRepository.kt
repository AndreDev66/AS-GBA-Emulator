package com.example.data

import android.content.Context
import com.example.core.emulation.FastForwardAudio
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VideoFilter {
    NEAREST_NEIGHBOR,
    BILINEAR,
    SCANLINES
}

enum class AspectRatioMode {
    ORIGINAL_3_2,
    STRETCH_FULL,
    FIT_HEIGHT
}

/**
 * Tema de acento de la interfaz.
 *
 * El color viaja como ARGB en `Long` para que esta capa no arrastre una
 * dependencia de Compose: quien lo convierte en `Color` es el tema. Los tonos
 * "GBA" reproducen los casquillos originales de la consola; el primero es la
 * paleta de fábrica de AS.
 */
enum class AccentTheme(val displayName: String, val argb: Long) {
    STEAM_CYAN("Cian Steam", 0xFF1A9FFF),
    GBA_INDIGO("Índigo GBA", 0xFF4E54C8),
    GBA_GLACIER("Hielo GBA", 0xFF6EC6E6),
    GBA_LIME("Verde hoja", 0xFF8FB500),
    GBA_FLAME("Fuego GBA", 0xFFE2574C),
    GBA_PURPLE("Fucsia GBA", 0xFFA25BA8),
}

data class EmulatorSettings(
    val controllerOpacity: Float = 0.75f,
    val controllerScale: Float = 1.0f,
    val hapticFeedback: Boolean = true,
    val audioEnabled: Boolean = true,
    val audioVolume: Float = 1.0f,
    /**
     * Sonido durante el avance rápido.
     *
     * A 1x el audio marca la cadencia y no se toca. Acelerado, o se oye el bloque
     * comprimido ([FastForwardAudio.ACCELERATED]) o no se oye nada
     * ([FastForwardAudio.SILENCED]).
     */
    val fastForwardAudio: FastForwardAudio = FastForwardAudio.SILENCED,
    val videoFilter: VideoFilter = VideoFilter.NEAREST_NEIGHBOR,
    val aspectRatio: AspectRatioMode = AspectRatioMode.ORIGINAL_3_2,
    /**
     * Color que guiña botones, títulos y selecciones de toda la interfaz.
     *
     * Se guarda entero y no por componente: el acento es una sola decisión de
     * diseño y cambiarla pieza a pieza dejaría la app a medio pintar.
     */
    val accentTheme: AccentTheme = AccentTheme.STEAM_CYAN,
    val defaultFastForwardSpeed: Float = 2.0f,
    val showFpsCounter: Boolean = true,
    /**
     * Muestra el distintivo del enlace durante la partida a pantalla completa.
     *
     * En horizontal el distintivo se dibuja encima del juego y el botón L de
     * los controles táctiles queda justo debajo: se tapa y, además, quien juega
     * con los botones en su sitio ve un texto flotando sobre la pantalla todo
     * el rato. Por eso el distintivo es un simple icono verde y por esto el
     * usuario decide si lo quiere: el aviso de que la partida va enlazada está
     * una vez al abrir, no hace falta-recordarlo cada segundo.
     */
    val showLinkBadgeInFullscreen: Boolean = false,
    /**
     * Oculta los botones en pantalla.
     *
     * Para quien conecta un mando: los controles táctiles se dibujan encima del
     * juego y tapan la parte de abajo del cartucho. Con esto se apagan del todo
     * sin desactivar el resto de ajustes.
     */
    val hideTouchControls: Boolean = false,
    /**
     * Dónde y de qué tamaño están los botones en pantalla.
     *
     * Los controles se colocan por fracción, no por píxeles, para que la misma
     * distribución valga en un móvil pequeño y en una tableta, y para que al
     * girar no haya que recolocar nada: cada orientación guarda la suya.
     */
    val touchLayout: TouchLayout = TouchLayout(),
    /**
     * Qué tecla del mando alimenta cada botón de la GBA.
     *
     * Android ya entrega los botones con la etiqueta correcta en casi todos los
     * mandos, pero no en todos: hay mandos con la A y la B cruzadas y con los
     * gatillos duplicados. Aquí cada botón acepta la tecla que se pulse.
     */
    val gamepadMapping: GamepadMapping = GamepadMapping(),
    /**
     * Carpeta desde la que se importan todas las ROM de una vez.
     *
     * Con `#` en lugar de URI si no se ha elegido ninguna. Importar una por una
     * es el único modo que había, y con una colección entera son decenas de
     * seleccionones.
     */
    val romFolderUri: String = "",
    /**
     * Reimporta la carpeta cada vez que se abre la biblioteca.
     *
     * Apagado, la carpeta sólo se lee cuando se elige o cuando se pulsa el
     * botón de escanear, para no gastar I/O en cada arranque.
     */
    val autoScanRomFolder: Boolean = false
)

class SettingsRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("as_gba_settings", Context.MODE_PRIVATE)

    private companion object {
        const val KEY_TOUCH_LAYOUT_V2 = "touch_layout_v2"
    }

    private val _settings = MutableStateFlow(loadSettings())
    val settings = _settings.asStateFlow()

    private fun loadSettings(): EmulatorSettings {
        return EmulatorSettings(
            controllerOpacity = prefs.getFloat("ctrl_opacity", 0.75f),
            controllerScale = prefs.getFloat("ctrl_scale", 1.0f),
            hapticFeedback = prefs.getBoolean("haptic", true),
            audioEnabled = prefs.getBoolean("audio_enabled", true),
            audioVolume = prefs.getFloat("audio_vol", 1.0f),
            fastForwardAudio = FastForwardAudio.valueOf(
                prefs.getString("ff_audio", FastForwardAudio.SILENCED.name)
                    ?: FastForwardAudio.SILENCED.name
            ),
            videoFilter = VideoFilter.valueOf(prefs.getString("video_filter", VideoFilter.NEAREST_NEIGHBOR.name) ?: VideoFilter.NEAREST_NEIGHBOR.name),
            aspectRatio = AspectRatioMode.valueOf(prefs.getString("aspect_ratio", AspectRatioMode.ORIGINAL_3_2.name) ?: AspectRatioMode.ORIGINAL_3_2.name),
            accentTheme = AccentTheme.valueOf(prefs.getString("accent_theme", AccentTheme.STEAM_CYAN.name) ?: AccentTheme.STEAM_CYAN.name),
            defaultFastForwardSpeed = prefs.getFloat("ff_speed", 2.0f),
            showFpsCounter = prefs.getBoolean("show_fps", true),
            showLinkBadgeInFullscreen = prefs.getBoolean("show_link_badge_fullscreen", false),
            hideTouchControls = prefs.getBoolean("hide_touch_controls", false),
            // La clave lleva versión a propósito: la primera iteración del editor
            // guardaba posiciones fijas que no reproducían el reparto original, y
            // esas medidas no deben seguir ahí al leer los ajustes.
            touchLayout = TouchLayout.deserialize(prefs.getString(KEY_TOUCH_LAYOUT_V2, null)),
            gamepadMapping = GamepadMapping.deserialize(prefs.getString("gamepad_mapping", null)),
            romFolderUri = prefs.getString("rom_folder_uri", "") ?: "",
            autoScanRomFolder = prefs.getBoolean("auto_scan_rom_folder", false)
        )
    }

    fun updateSettings(newSettings: EmulatorSettings) {
        _settings.value = newSettings
        prefs.edit()
            .putFloat("ctrl_opacity", newSettings.controllerOpacity)
            .putFloat("ctrl_scale", newSettings.controllerScale)
            .putBoolean("haptic", newSettings.hapticFeedback)
            .putBoolean("audio_enabled", newSettings.audioEnabled)
            .putFloat("audio_vol", newSettings.audioVolume)
            .putString("ff_audio", newSettings.fastForwardAudio.name)
            .putString("video_filter", newSettings.videoFilter.name)
            .putString("aspect_ratio", newSettings.aspectRatio.name)
            .putString("accent_theme", newSettings.accentTheme.name)
            .putFloat("ff_speed", newSettings.defaultFastForwardSpeed)
            .putBoolean("show_fps", newSettings.showFpsCounter)
            .putBoolean("show_link_badge_fullscreen", newSettings.showLinkBadgeInFullscreen)
            .putBoolean("hide_touch_controls", newSettings.hideTouchControls)
            .putString(KEY_TOUCH_LAYOUT_V2, newSettings.touchLayout.serialize())
            .putString("gamepad_mapping", newSettings.gamepadMapping.serialize())
            .putString("rom_folder_uri", newSettings.romFolderUri)
            .putBoolean("auto_scan_rom_folder", newSettings.autoScanRomFolder)
            .apply()
    }
}
