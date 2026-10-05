package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.AspectRatioMode
import com.example.data.EmulatorSettings
import com.example.data.VideoFilter
import com.example.input.GamepadInput
import com.example.ui.theme.*

/**
 * Settings Dialog — Steam Big Picture style.
 *
 * Secciones bien delimitadas con headers de sección, sliders de ancho completo
 * y chips segmentados tipo "filter row" con fondo de carbón.
 *
 * Developed by Andrés Socorro.
 */
@Composable
fun SettingsDialog(
    currentSettings  : EmulatorSettings,
    onSaveSettings   : (EmulatorSettings) -> Unit,
    onDismissRequest : () -> Unit,
    onPickRomFolder  : (() -> Unit)? = null,
    onScanRomFolder  : (() -> Unit)? = null,
    onEditTouchLayout: (() -> Unit)? = null,
    folderName       : String?         = null,
    isFolderConfigured: Boolean        = false
) {
    var opacity     by remember { mutableStateOf(currentSettings.controllerOpacity) }
    var scale       by remember { mutableStateOf(currentSettings.controllerScale) }
    var haptic      by remember { mutableStateOf(currentSettings.hapticFeedback) }
    var audioEnabled by remember { mutableStateOf(currentSettings.audioEnabled) }
    var audioVolume  by remember { mutableStateOf(currentSettings.audioVolume) }
    var videoFilter  by remember { mutableStateOf(currentSettings.videoFilter) }
    var aspectRatio  by remember { mutableStateOf(currentSettings.aspectRatio) }
    var ffSpeed      by remember { mutableStateOf(currentSettings.defaultFastForwardSpeed) }
    var showFps      by remember { mutableStateOf(currentSettings.showFpsCounter) }
    var hideTouch    by remember { mutableStateOf(currentSettings.hideTouchControls) }
    var mapping      by remember { mutableStateOf(currentSettings.gamepadMapping) }
    var autoScan     by remember { mutableStateOf(currentSettings.autoScanRomFolder) }
    var showMapping  by remember { mutableStateOf(false) }

    // La carpeta NO se guarda en estado local. El selector del sistema devuelve
    // la URI por un callback que ya la escribe en los ajustes, así que
    // `currentSettings` es la única fuente de verdad. Con una copia local,
    // guardar pisaría esa URI recién elegida con el valor anterior.
    val romFolder = currentSettings.romFolderUri

    Dialog(onDismissRequest = onDismissRequest) {
        Surface(
            shape    = RoundedCornerShape(20.dp),
            color    = SteamSurface1,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
                .border(1.dp, SteamBorder, RoundedCornerShape(20.dp))
                .testTag("dialog_settings")
        ) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                // Header
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(SteamCyan.copy(alpha = 0.10f), SteamSurface1)
                            )
                        )
                        .padding(horizontal = 24.dp, vertical = 20.dp)
                ) {
                    Text(
                        text  = "Ajustes del Emulador",
                        style = MaterialTheme.typography.titleLarge,
                        color = SteamTextPrimary
                    )
                }

                HorizontalDivider(color = SteamBorder, thickness = 0.5.dp)

                Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {

                    // ── Fast Forward ──────────────────────────────────────────
                    SettingsSectionLabel("VELOCIDAD FAST-FORWARD")
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier              = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(1.5f, 2.0f, 4.0f, 8.0f).forEach { speed ->
                            SteamSegmentedOption(
                                label      = "${speed}x",
                                isSelected = ffSpeed == speed,
                                onClick    = { ffSpeed = speed },
                                modifier   = Modifier
                                    .weight(1f)
                                    .testTag("chip_speed_$speed")
                            )
                        }
                    }

                    SettingsDivider()

                    // ── Controles táctiles ────────────────────────────────────
                    SettingsSectionLabel("CONTROLES TÁCTILES")
                    Spacer(modifier = Modifier.height(12.dp))

                    SettingsSliderRow(
                        label   = "Opacidad",
                        value   = opacity,
                        display = "${(opacity * 100).toInt()}%",
                        range   = 0.2f..1.0f,
                        onValueChange = { opacity = it },
                        testTag = "slider_opacity"
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    SettingsSliderRow(
                        label   = "Tamaño de botones",
                        value   = scale,
                        display = "${(scale * 100).toInt()}%",
                        range   = 0.8f..1.4f,
                        onValueChange = { scale = it },
                        testTag = "slider_scale"
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    SettingsSwitchRow(
                        label   = "Vibración Háptica",
                        checked = haptic,
                        onCheckedChange = { haptic = it },
                        testTag = "switch_haptic"
                    )

                    SettingsSwitchRow(
                        label    = "Ocultar botones en pantalla",
                        subtitle = "Útil si juegas con un mando físico",
                        checked  = hideTouch,
                        onCheckedChange = { hideTouch = it },
                        testTag = "switch_hide_touch"
                    )

                    if (onEditTouchLayout != null) {
                        Spacer(modifier = Modifier.height(8.dp))

                        SettingsActionRow(
                            title    = "Posición y tamaño de los botones",
                            subtitle = "Arrastra cada botón y pellizca para cambiar su tamaño",
                            testTag  = "btn_edit_touch_layout",
                            onClick  = {
                                // Antes de abrir el editor se guardan los
                                // ajustes pendientes: el editor vive fuera de este
                                // Dialog y, si no se cerrara antes, su propia
                                // ventana se quedaría encima.
                                onSaveSettings(
                                    currentSettings.copy(
                                        controllerOpacity   = opacity,
                                        controllerScale     = scale,
                                        hapticFeedback      = haptic,
                                        hideTouchControls   = hideTouch,
                                        audioEnabled        = audioEnabled,
                                        audioVolume         = audioVolume,
                                        videoFilter         = videoFilter,
                                        aspectRatio         = aspectRatio,
                                        defaultFastForwardSpeed = ffSpeed,
                                        showFpsCounter      = showFps,
                                        gamepadMapping      = mapping,
                                        autoScanRomFolder   = autoScan
                                    )
                                )
                                onEditTouchLayout()
                            }
                        )
                    }

                    SettingsDivider()

                    // ── Pantalla ──────────────────────────────────────────────
                    SettingsSectionLabel("PANTALLA Y RENDERIZADO")
                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        "Filtro de vídeo",
                        style = MaterialTheme.typography.bodySmall,
                        color = SteamTextSecondary,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    Row(
                        modifier              = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SteamSegmentedOption(
                            label      = "Píxel Nítido",
                            isSelected = videoFilter == VideoFilter.NEAREST_NEIGHBOR,
                            onClick    = { videoFilter = VideoFilter.NEAREST_NEIGHBOR },
                            modifier   = Modifier.weight(1f)
                        )
                        SteamSegmentedOption(
                            label      = "Suave",
                            isSelected = videoFilter == VideoFilter.BILINEAR,
                            onClick    = { videoFilter = VideoFilter.BILINEAR },
                            modifier   = Modifier.weight(1f)
                        )
                        SteamSegmentedOption(
                            label      = "CRT",
                            isSelected = videoFilter == VideoFilter.SCANLINES,
                            onClick    = { videoFilter = VideoFilter.SCANLINES },
                            modifier   = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        "Relación de aspecto",
                        style = MaterialTheme.typography.bodySmall,
                        color = SteamTextSecondary,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    Row(
                        modifier              = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SteamSegmentedOption(
                            label      = "3:2",
                            isSelected = aspectRatio == AspectRatioMode.ORIGINAL_3_2,
                            onClick    = { aspectRatio = AspectRatioMode.ORIGINAL_3_2 },
                            modifier   = Modifier.weight(1f)
                        )
                        SteamSegmentedOption(
                            label      = "Ajustar",
                            isSelected = aspectRatio == AspectRatioMode.FIT_HEIGHT,
                            onClick    = { aspectRatio = AspectRatioMode.FIT_HEIGHT },
                            modifier   = Modifier.weight(1f)
                        )
                        SteamSegmentedOption(
                            label      = "Estirar",
                            isSelected = aspectRatio == AspectRatioMode.STRETCH_FULL,
                            onClick    = { aspectRatio = AspectRatioMode.STRETCH_FULL },
                            modifier   = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    SettingsSwitchRow(
                        label   = "Mostrar contador FPS",
                        checked = showFps,
                        onCheckedChange = { showFps = it },
                        testTag = "switch_fps"
                    )

                    SettingsDivider()

                    // ── Audio ─────────────────────────────────────────────────
                    SettingsSectionLabel("AUDIO")
                    Spacer(modifier = Modifier.height(4.dp))

                    SettingsSwitchRow(
                        label   = "Sonido activado",
                        checked = audioEnabled,
                        onCheckedChange = { audioEnabled = it },
                        testTag = "switch_audio"
                    )

                    SettingsSliderRow(
                        label         = "Volumen",
                        value         = audioVolume,
                        display       = "${(audioVolume * 100).toInt()}%",
                        range         = 0f..1f,
                        onValueChange = { audioVolume = it },
                        enabled       = audioEnabled,
                        testTag       = "slider_audio_volume"
                    )

                    SettingsDivider()

                    // ── Mando ─────────────────────────────────────────────────
                    SettingsSectionLabel("MANDO")
                    Spacer(modifier = Modifier.height(12.dp))

                    SettingsSwitchRow(
                        label   = "Palanca analógica",
                        checked = mapping.useAnalogStick,
                        onCheckedChange = { mapping = mapping.copy(useAnalogStick = it) },
                        testTag = "switch_settings_stick"
                    )

                    SettingsSwitchRow(
                        label   = "Gatillos analógicos",
                        checked = mapping.useAnalogTriggers,
                        onCheckedChange = { mapping = mapping.copy(useAnalogTriggers = it) },
                        testTag = "switch_settings_triggers"
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick  = { showMapping = true },
                        shape    = RoundedCornerShape(12.dp),
                        border   = androidx.compose.foundation.BorderStroke(1.dp, SteamBorderActive),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("btn_open_mapping")
                    ) {
                        Icon(
                            imageVector = Icons.Default.SportsEsports,
                            contentDescription = null,
                            tint   = SteamCyan,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (mapping.isDefault) {
                                "Reasignar botones"
                            } else {
                                "Reasignar botones  ·  personalizado"
                            },
                            color = if (mapping.isDefault) SteamTextPrimary else SteamCyan,
                            fontSize = 13.sp
                        )
                    }

                    if (onPickRomFolder != null) {
                        SettingsDivider()

                        // ── Carpeta de ROMs ─────────────────────────────────────
                        SettingsSectionLabel("CARPETA DE ROMs")
                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier              = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick  = onPickRomFolder,
                                shape    = RoundedCornerShape(10.dp),
                                border   = androidx.compose.foundation.BorderStroke(1.dp, SteamBorder),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("btn_pick_rom_folder")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FolderOpen,
                                    contentDescription = null,
                                    tint   = SteamTextSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text     = if (isFolderConfigured) "Cambiar" else "Elegir carpeta",
                                    color    = SteamTextPrimary,
                                    fontSize = 13.sp
                                )
                            }

                            OutlinedButton(
                                onClick  = onScanRomFolder ?: {},
                                enabled  = isFolderConfigured,
                                shape    = RoundedCornerShape(10.dp),
                                border   = androidx.compose.foundation.BorderStroke(1.dp, SteamBorder),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("btn_scan_rom_folder")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = null,
                                    tint   = if (isFolderConfigured) SteamCyan else SteamTextMuted,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text     = "Escanear",
                                    color    = if (isFolderConfigured) SteamCyan else SteamTextMuted,
                                    fontSize = 13.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        SettingsSwitchRow(
                            label    = "Escanear al abrir la app",
                            subtitle = folderName?.let { "Carpeta: $it" },
                            checked  = autoScan,
                            onCheckedChange = { autoScan = it },
                            testTag  = "switch_auto_scan"
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Actions
                    Row(
                        modifier              = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = onDismissRequest,
                            shape   = RoundedCornerShape(12.dp),
                            border  = androidx.compose.foundation.BorderStroke(
                                1.dp, SteamBorder
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Cancelar", color = SteamTextSecondary)
                        }
                        Button(
                            onClick = {
                                onSaveSettings(currentSettings.copy(
                                    controllerOpacity     = opacity,
                                    controllerScale       = scale,
                                    hapticFeedback        = haptic,
                                    audioEnabled          = audioEnabled,
                                    audioVolume           = audioVolume,
                                    videoFilter           = videoFilter,
                                    aspectRatio           = aspectRatio,
                                    defaultFastForwardSpeed = ffSpeed,
                                    showFpsCounter        = showFps,
                                    hideTouchControls     = hideTouch,
                                    gamepadMapping        = mapping,
                                    romFolderUri          = romFolder,
                                    autoScanRomFolder     = autoScan
                                ))
                                onDismissRequest()
                            },
                            shape   = RoundedCornerShape(12.dp),
                            colors  = ButtonDefaults.buttonColors(
                                containerColor = SteamCyan,
                                contentColor   = Color(0xFF001626)
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("btn_save_settings")
                        ) {
                            Text(
                                "Guardar",
                                fontWeight = FontWeight.Bold,
                                style      = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }
            }
        }
    }

    // El editor de asignación se monta encima de este diálogo, no en lugar de
    // él: así el usuario ve los cambios de palanca y gatillos junto a los
    // botones que acaba de tocar, y "Guardar" sigue estando a un clic.
    if (showMapping) {
        GamepadMappingDialog(
            initialMapping  = mapping,
            connectedPads   = remember { GamepadInput.connectedGamepads() },
            onSaveMapping   = { mapping = it },
            onDismissRequest = { showMapping = false }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Small composables compartidos
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SettingsSectionLabel(text: String) {
    Text(
        text          = text,
        style         = MaterialTheme.typography.labelSmall,
        color         = SteamCyan,
        fontWeight    = FontWeight.Bold,
        letterSpacing = 1.0.sp
    )
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier  = Modifier.padding(vertical = 16.dp),
        color     = SteamBorder,
        thickness = 0.5.dp
    )
}

@Composable
private fun SettingsSliderRow(
    label         : String,
    value         : Float,
    display       : String,
    range         : ClosedFloatingPointRange<Float>,
    onValueChange : (Float) -> Unit,
    enabled       : Boolean = true,
    testTag       : String  = ""
) {
    Column {
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Text(
                text  = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) SteamTextPrimary else SteamTextMuted
            )
            Text(
                text  = display,
                style = MaterialTheme.typography.labelMedium,
                color = if (enabled) SteamCyan else SteamTextMuted,
                fontWeight = FontWeight.Bold
            )
        }
        Slider(
            value         = value,
            onValueChange = onValueChange,
            valueRange    = range,
            enabled       = enabled,
            colors        = SliderDefaults.colors(
                thumbColor          = SteamCyan,
                activeTrackColor    = SteamCyan,
                inactiveTrackColor  = SteamSurface3
            ),
            modifier = Modifier.then(
                if (testTag.isNotEmpty()) Modifier.testTag(testTag) else Modifier
            )
        )
    }
}

@Composable
private fun SettingsSwitchRow(
    label           : String,
    subtitle        : String? = null,
    checked         : Boolean,
    onCheckedChange : (Boolean) -> Unit,
    testTag         : String = ""
) {
    Row(
        modifier              = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text  = label,
                style = MaterialTheme.typography.bodyMedium,
                color = SteamTextPrimary
            )
            if (subtitle != null) {
                Text(
                    text  = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = SteamTextSecondary
                )
            }
        }
        Switch(
            checked         = checked,
            onCheckedChange = onCheckedChange,
            colors          = SwitchDefaults.colors(
                checkedThumbColor       = Color.White,
                checkedTrackColor       = SteamCyan,
                uncheckedThumbColor     = SteamTextMuted,
                uncheckedTrackColor     = SteamSurface3,
                uncheckedBorderColor    = SteamBorder
            ),
            modifier = Modifier.then(
                if (testTag.isNotEmpty()) Modifier.testTag(testTag) else Modifier
            )
        )
    }
}

@Composable
private fun SettingsActionRow(
    title    : String,
    subtitle : String,
    testTag  : String = "",
    onClick  : () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SteamSurface2)
            .border(1.dp, SteamBorder, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text  = title,
                style = MaterialTheme.typography.bodyMedium,
                color = SteamTextPrimary
            )
            Text(
                text  = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = SteamTextSecondary
            )
        }
        Icon(
            imageVector = Icons.Default.OpenWith,
            contentDescription = null,
            tint = SteamCyan,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun SteamSegmentedOption(
    label      : String,
    isSelected : Boolean,
    onClick    : () -> Unit,
    modifier   : Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) SteamCyan.copy(alpha = 0.15f) else SteamSurface2)
            .border(
                1.dp,
                if (isSelected) SteamCyan.copy(alpha = 0.6f) else SteamBorder,
                RoundedCornerShape(8.dp)
            )
            .then(
                // Clickable sin ripple visible para look más "desktop"
                Modifier.padding(0.dp)
            )
            .padding(0.dp),
        contentAlignment = Alignment.Center
    ) {
        TextButton(
            onClick  = onClick,
            modifier = Modifier.fillMaxWidth(),
            shape    = RoundedCornerShape(8.dp),
            colors   = ButtonDefaults.textButtonColors(
                contentColor = if (isSelected) SteamCyan else SteamTextSecondary
            )
        ) {
            Text(
                text       = label,
                style      = MaterialTheme.typography.labelMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                textAlign  = TextAlign.Center
            )
        }
    }
}
