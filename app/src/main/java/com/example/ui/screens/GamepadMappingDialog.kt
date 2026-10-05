package com.example.ui.screens

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.GbaButton
import com.example.data.GamepadMapping
import com.example.data.gbaKeyLabel
import com.example.ui.theme.*
import kotlinx.coroutines.delay

/**
 * Margen para considerar dos pulsaciones parte del mismo acorde.
 *
 * Ni tan corto que separe lo que un dedo hace a la vez, ni tan largo que una
 * pulsación suelta se coma la tecla siguiente: 150 ms es más o menos lo que
 * tarda un dedo en caer sobre el segundo botón cuando se pulsan a dos manos.
 */
private const val CHORD_WINDOW_MS = 150L

/**
 * Editor de asignación de teclas del mando.
 *
 * Cada fila es un botón de la GBA, y a su derecha la tecla que lo dispara ahora
 * mismo. Al tocar la fila, la interfaz se queda esperando: el siguiente `KeyEvent`
 * de un dispositivo externo es el que queda asignado.
 *
 * Se captura la tecla cruda, el `keyCode`, y no un nombre, porque lo que importa es
 * qué número llega al proceso. Al probarlo sin mando se puede usar `adb shell input
 * keyevent` con un código de botón, que llega por el mismo camino.
 *
 * Se pueden pulsar varias teclas a la vez y todas valen para el botón editado:
 * quien quiere que el hombro L responda a L1 y a L2 (que es como viene de fábrica,
 * pero también como llega en los mandos que los declaran por separado) pulsa las
 * dos mientras la fila está esperando y se guardan las dos, no sólo la primera.
 * Ver [CHORD_WINDOW_MS] para el margen.
 *
 * Misma superficie, borde y acento que el resto de diálogos, sin icono propio para
 * no romper la paleta.
 */
@Composable
fun GamepadMappingDialog(
    initialMapping   : GamepadMapping,
    connectedPads   : List<String>,
    onSaveMapping   : (GamepadMapping) -> Unit,
    onDismissRequest : () -> Unit
) {
    var mapping by remember { mutableStateOf(initialMapping) }

    // Botón de la GBA esperando tecla, o `null` si no hay ninguno esperando.
    var listeningFor by remember { mutableStateOf<GbaButton?>(null) }

    // Lo que el usuario tiene pulsado ahora mismo, y por separado lo que ha
    // pulsado dentro de la ventana de acorde.
    //
    // Son dos listas y no una a propósito. Con una sola, soltar la primera de
    // las dos teclas —que es justo lo que hace un dedo al pulsar dos botones a
    // la vez y levanta uno antes que el otro— borraba esa tecla del conjunto y el
    // acorde acababa guardándose con una sola, o no guardándose.
    var pressedKeys by remember { mutableStateOf(listOf<Int>()) }

    // Teclas capturadas en esta asignación, en orden de llegada porque ése es el
    // orden en que se las enseñas al usuario. No se ven alteradas al soltar.
    var chordKeys by remember { mutableStateOf(listOf<Int>()) }

    val commitChord: () -> Unit = {
        val target = listeningFor
        val captured = chordKeys
        if (target != null && captured.isNotEmpty()) {
            mapping = mapping.withAssignment(target, captured)
        }
        listeningFor = null
        pressedKeys = emptyList()
        chordKeys = emptyList()
    }

    // El acorde se da por cerrado cuando pasa el tiempo sin que caiga otra tecla.
    // Como `chordKeys` está en las claves del efecto, cada tecla nueva reinicia
    // la cuenta sola, y no hace falta llevar ningún `Job` a mano.
    //
    // Se cierra también con las teclas aún pulsadas porque quien quiere un acorde
    // aprieta y espera a verlo escrito; si no se cerrara aquí, sólo aparecería al
    // soltar, que es más tarde y más fácil de confundir con que no ha cogido.
    LaunchedEffect(listeningFor, chordKeys) {
        if (listeningFor != null && chordKeys.isNotEmpty()) {
            delay(CHORD_WINDOW_MS)
            commitChord()
        }
    }

    // Sin foco, el `onPreviewKeyEvent` de abajo no llega a ver nada: el diálogo
    // arranca sin ningún nodo que lo tenga, porque es una ventana nueva.
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Dialog(onDismissRequest = onDismissRequest) {
        Surface(
            shape    = RoundedCornerShape(20.dp),
            color    = SteamSurface1,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
                .border(1.dp, SteamBorder, RoundedCornerShape(20.dp))
                .testTag("dialog_gamepad_mapping")
        ) {
            Column(
                modifier = Modifier
                    .heightIn(max = 640.dp)
                    .focusRequester(focusRequester)
                    .focusable()
                    // El `Dialog` de Compose abre su propia ventana, así que las
                    // teclas van a esa ventana y nunca llegan a
                    // `dispatchKeyEvent` de la Activity. Se capturan aquí, en el
                    // nodo que tiene el foco.
                    .onPreviewKeyEvent { keyEvent ->
                        val target = listeningFor
                        // Se lee el `KeyEvent` de Android y no el de Compose: el
                        // código de tecla es lo que se guarda en el mapeo, y
                        // `KeyEvent.type`/`KeyEvent.key` quedarían tapados por las
                        // funciones homónimas de `compose.runtime` y `ui.input.key`.
                        val native = keyEvent.nativeKeyEvent
                        when {
                            target == null -> false
                            native.action == KeyEvent.ACTION_DOWN -> {
                                if (!pressedKeys.contains(native.keyCode)) {
                                    pressedKeys = pressedKeys + native.keyCode
                                }
                                if (!chordKeys.contains(native.keyCode)) {
                                    chordKeys = chordKeys + native.keyCode
                                }
                                true
                            }
                            native.action == KeyEvent.ACTION_UP -> {
                                val remaining = pressedKeys - native.keyCode
                                pressedKeys = remaining
                                // Al soltar la última no se espera a que expire
                                // la ventana: si el usuario ya levantó el dedo,
                                // seguir esperando sólo lo haría notar. Se
                                // confirma `chordKeys`, no lo que queda pulsado, que
                                // es lo que de verdad han pulsado los dos dedos.
                                if (remaining.isEmpty()) commitChord()
                                true
                            }
                            else -> false
                        }
                    }
                    .verticalScroll(rememberScrollState())
            ) {
                // Cabecera
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
                    Column {
                        Text(
                            text  = "Controles del Mando",
                            style = MaterialTheme.typography.titleLarge,
                            color = SteamTextPrimary
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = if (connectedPads.isEmpty()) {
                                "Sin mando detectado — se puede asignar igualmente"
                            } else {
                                connectedPads.joinToString(", ")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (connectedPads.isEmpty()) SteamTextMuted else SteamGreen
                        )
                    }
                }

                HorizontalDivider(color = SteamBorder, thickness = 0.5.dp)

                Column(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
                ) {
                    // Instrucciones
                    MappingInstruction(
                        when {
                            listeningFor == null ->
                                "Toca un botón de la GBA y luego la tecla que quieras usar en el mando."
                            chordKeys.isEmpty() ->
                                "Pulsando ahora…  «${listeningFor?.label}»  —  puedes pulsar dos a la vez"
                            else ->
                                "«${chordKeys.joinToString(" + ") { gbaKeyLabel(it) }}»  para  «${listeningFor?.label}»"
                        },
                        highlight = listeningFor != null,
                        testTag   = "text_mapping_status"
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Botones de la GBA
                    for (button in GbaButton.entries) {
                        GbaButtonRow(
                            button           = button,
                            mapping          = mapping,
                            isListening      = listeningFor == button,
                            onClick          = {
                                // Cambiar de fila descarta el acorde a medio hacer:
                                // lo pulsado hasta ahora era para el botón anterior.
                                pressedKeys = emptyList()
                                chordKeys = emptyList()
                                listeningFor = if (listeningFor == button) null else button
                            }
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Ejes analógicos
                    MappingSwitchRow(
                        label   = "Palanca analógica",
                        checked = mapping.useAnalogStick,
                        onCheckedChange = { mapping = mapping.copy(useAnalogStick = it) },
                        testTag = "switch_mapping_stick"
                    )
                    MappingSwitchRow(
                        label   = "Gatillos analógicos",
                        checked = mapping.useAnalogTriggers,
                        onCheckedChange = { mapping = mapping.copy(useAnalogTriggers = it) },
                        testTag = "switch_mapping_triggers"
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    // Acciones
                    Row(
                        modifier              = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                mapping = mapping.withDefaults()
                                listeningFor = null
                                pressedKeys = emptyList()
                                chordKeys = emptyList()
                            },
                            shape   = RoundedCornerShape(12.dp),
                            border  = androidx.compose.foundation.BorderStroke(1.dp, SteamBorder),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("btn_mapping_reset")
                        ) {
                            Text(
                                "Restablecer",
                                color    = SteamTextSecondary,
                                fontSize = 13.sp
                            )
                        }
                        Button(
                            onClick = {
                                onSaveMapping(mapping)
                                onDismissRequest()
                            },
                            shape   = RoundedCornerShape(12.dp),
                            colors  = ButtonDefaults.buttonColors(
                                containerColor = SteamCyan,
                                contentColor   = Color(0xFF001626)
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("btn_mapping_save")
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

    // Pulsar atrás mientras se espera una tecla debe cancelar la espera, no
    // cerrar el diálogo entero: si no, se pierde el diálogo entero de un tirón.
    BackHandler(enabled = listeningFor != null) {
        listeningFor = null
        pressedKeys = emptyList()
        chordKeys = emptyList()
    }
}

// Filas

@Composable
private fun MappingInstruction(text: String, highlight: Boolean, testTag: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (highlight) SteamCyan.copy(alpha = 0.12f) else SteamSurface2
            )
            .border(
                1.dp,
                if (highlight) SteamCyan.copy(alpha = 0.6f) else SteamBorder,
                RoundedCornerShape(10.dp)
            )
            .padding(horizontal = 14.dp, vertical = 11.dp)
            .testTag(testTag)
    ) {
        Text(
            text  = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (highlight) SteamCyan else SteamTextSecondary,
            lineHeight = 17.sp
        )
    }
}

@Composable
private fun GbaButtonRow(
    button      : GbaButton,
    mapping     : GamepadMapping,
    isListening : Boolean,
    onClick     : () -> Unit
) {
    val assigned = mapping.labelFor(button)
    val unbound  = assigned == "Sin asignar"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    isListening -> SteamCyan.copy(alpha = 0.12f)
                    unbound     -> SteamSurface2
                    else        -> SteamSurface2
                }
            )
            .border(
                1.dp,
                when {
                    isListening -> SteamCyan.copy(alpha = 0.7f)
                    unbound     -> SteamBorder
                    else        -> SteamBorder
                },
                RoundedCornerShape(10.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("row_mapping_${button.name.lowercase()}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Glifo del botón de la GBA: distingue a primera vista las direcciones de las acciones
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(
                    if (unbound) SteamSurface3
                    else SteamCyan.copy(alpha = 0.16f)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text       = button.glyph,
                fontSize   = if (button.glyph.length > 1) 9.sp else 13.sp,
                fontWeight = FontWeight.Bold,
                color      = if (unbound) SteamTextMuted else SteamCyan
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text  = button.label,
            style = MaterialTheme.typography.bodyMedium,
            color = SteamTextPrimary
        )

        Spacer(modifier = Modifier.weight(1f))

        Text(
            text = when {
                isListening -> "Pulsando…"
                unbound     -> assigned
                else        -> assigned
            },
            style = MaterialTheme.typography.labelMedium,
            color = when {
                isListening -> SteamCyan
                unbound     -> SteamTextMuted
                else        -> SteamTextSecondary
            },
            fontWeight = if (isListening) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
private fun MappingSwitchRow(
    label           : String,
    checked         : Boolean,
    onCheckedChange : (Boolean) -> Unit,
    testTag         : String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Text(
            text  = label,
            style = MaterialTheme.typography.bodyMedium,
            color = SteamTextPrimary
        )
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
            modifier = Modifier.testTag(testTag)
        )
    }
}

