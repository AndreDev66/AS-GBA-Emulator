package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.core.cheats.CheatEntry
import com.example.core.cheats.CheatParseResult
import com.example.core.cheats.CheatParser
import com.example.core.cheats.CheatStorageCodec
import com.example.ui.theme.*

/**
 * Diálogo de trucos del juego en curso.
 *
 * Muestra una lista de trucos por juego, cada uno con su nombre, su casilla y
 * un botón de borrar; al pulsar sobre uno se abre su código para editarlo.
 *
 * ## Por qué la lista guarda texto y no operaciones
 *
 * Se guarda lo que el usuario pegó tal cual ([CheatEntry.lines]) y se compila
 * al activar. Así una mejora futura del decodificador —más formatos, mejores
 * detecciones— se aplica sola la próxima vez, sin pedirle a nadie que vuelva a
 * pegar sus códigos.
 *
 * ## Por qué un error aborta la lista entera
 *
 * Un código de trucos es una secuencia donde las condiciones gobiernan las
 * líneas siguientes. Aplicar la mitad entendida dejaría escrituras sin la
 * condición que las protege, que es justo lo contrario de lo que el usuario
 * pidió. Por eso un error de análisis no activa nada y se explica en pantalla.
 */
@Composable
fun CheatDialog(
    entries       : List<CheatEntry>,
    onSave        : (List<CheatEntry>) -> Unit,
    onDismissRequest : () -> Unit
) {
    // El editor de código se monta encima de este diálogo (como el de asignación
    // de botones en los ajustes) para que la lista siga estando a la vista y
    // perder el contexto al teclear un código largo.
    var editing by remember { mutableStateOf<CheatEntry?>(null) }

    Dialog(onDismissRequest = onDismissRequest) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = SteamSurface1,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
                .border(1.dp, SteamBorder, RoundedCornerShape(20.dp))
                .testTag("dialog_cheats")
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector     = Icons.Default.BugReport,
                        contentDescription = null,
                        tint            = SteamCyan,
                        modifier        = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text  = "Trucos",
                        style = MaterialTheme.typography.titleLarge,
                        color = SteamTextPrimary
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text  = "Códigos de GameShark o CodeBreaker, uno por línea. " +
                        "Se guardan por juego.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SteamTextSecondary
                )

                Spacer(modifier = Modifier.height(16.dp))

                if (entries.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(SteamSurface2)
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text  = "Todavía no hay trucos en este juego.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SteamTextSecondary
                        )
                    }
                } else {
                    entries.forEach { entry ->
                        CheatRow(
                            entry = entry,
                            onToggle = { enabled ->
                                onSave(
                                    entries.map {
                                        if (it.id == entry.id) it.copy(enabled = enabled) else it
                                    }
                                )
                            },
                            onEdit = { editing = entry },
                            onDelete = {
                                onSave(entries.filterNot { it.id == entry.id })
                            }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = {
                        // El truco nace desactivado y sin código: se rellena en el
                        // editor. Activarlo sin código no haría nada y desorienta.
                        editing = CheatEntry(
                            id      = CheatStorageCodec.newId(entries),
                            name    = "",
                            enabled = false,
                            lines   = emptyList()
                        )
                    },
                    shape   = RoundedCornerShape(12.dp),
                    border  = androidx.compose.foundation.BorderStroke(1.dp, SteamBorderActive),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("btn_add_cheat")
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint   = SteamCyan,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text     = "Añadir truco",
                        color    = SteamTextPrimary,
                        fontSize = 13.sp
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick  = onDismissRequest,
                        shape    = RoundedCornerShape(12.dp),
                        border   = androidx.compose.foundation.BorderStroke(1.dp, SteamBorder),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cerrar", color = SteamTextSecondary)
                    }
                }
            }
        }
    }

    editing?.let { entry ->
        CheatEditorDialog(
            entry   = entry,
            onSave  = { updated ->
                // Si ya existía se sustituye en su sitio, y si es nuevo se
                // añade al final: reordenar la lista detrás de la espalda del
                // usuario movería las casillas que ya tenía marcadas.
                val alreadyThere = entries.any { it.id == updated.id }
                onSave(
                    if (alreadyThere) {
                        entries.map { if (it.id == updated.id) updated else it }
                    } else {
                        entries + updated
                    }
                )
                editing = null
            },
            onCancel = { editing = null }
        )
    }
}

/**
 * Editor de un truco: nombre, código y el resultado de analizarlo.
 *
 * El análisis se recalcula en cada cambio de texto para que el usuario vea
 * enseguida si su código se ha entendido, en vez de activar y descubrir que no
 * hace nada.
 */
@Composable
private fun CheatEditorDialog(
    entry       : CheatEntry,
    onSave      : (CheatEntry) -> Unit,
    onCancel    : () -> Unit
) {
    var name  by remember { mutableStateOf(entry.name) }
    var text  by remember { mutableStateOf(entry.lines.joinToString("\n")) }
    var showValidation by remember { mutableStateOf(false) }

    val parsed = remember(text) { CheatParser.parse(text) }

    Dialog(onDismissRequest = onCancel) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = SteamSurface1,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
                .border(1.dp, SteamBorder, RoundedCornerShape(20.dp))
                .testTag("dialog_cheat_editor")
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text  = if (entry.name.isBlank()) "Nuevo truco" else "Editar truco",
                    style = MaterialTheme.typography.titleMedium,
                    color = SteamTextPrimary
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text  = "Nombre",
                    style = MaterialTheme.typography.labelSmall,
                    color = SteamCyan
                )
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value        = name,
                    onValueChange = { name = it },
                    singleLine   = true,
                    placeholder  = { Text("Vida infinita", color = SteamTextMuted) },
                    colors       = steamTextFieldColors(),
                    modifier     = Modifier
                        .fillMaxWidth()
                        .testTag("field_cheat_name")
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text  = "CÓDIGO",
                    style = MaterialTheme.typography.labelSmall,
                    color = SteamCyan
                )
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value        = text,
                    onValueChange = { text = it },
                    placeholder  = {
                        Text("12345678 ABCDEF01\n1234567A 00000064", color = SteamTextMuted)
                    },
                    // Monoespaciada porque un código se lee y se corrige por
                    // columnas: un dígito de más es el error más habitual.
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    colors  = steamTextFieldColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp)
                        .verticalScroll(rememberScrollState())
                        .testTag("field_cheat_code")
                )

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text  = "Un código por línea. Se admiten comentarios con #.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SteamTextMuted
                )

                if (showValidation) {
                    Spacer(modifier = Modifier.height(12.dp))
                    ParseReport(parsed)
                }

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick  = onCancel,
                        shape    = RoundedCornerShape(12.dp),
                        border   = androidx.compose.foundation.BorderStroke(1.dp, SteamBorder),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancelar", color = SteamTextSecondary)
                    }
                    Button(
                        onClick = {
                            // Guardar exige un código entendido: guardar uno
                            // imposible dejaría una casilla que no hace nada.
                            if (!parsed.accepted) {
                                showValidation = true
                                return@Button
                            }
                            val lines = text.lines()
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }
                            onSave(
                                entry.copy(
                                    name = name.trim().ifBlank { "Truco" },
                                    // Se guarda activo: si el usuario ha llegado
                                    // hasta Guardar es que lo quiere usar, y
                                    // dejarlo inactivo sería un paso extra sin
                                    // motivo.
                                    enabled = true,
                                    lines  = lines
                                )
                            )
                        },
                        shape   = RoundedCornerShape(12.dp),
                        colors  = ButtonDefaults.buttonColors(
                            containerColor = SteamCyan,
                            contentColor   = SteamOnAccent
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("btn_save_cheat")
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

/** Resultado del análisis: lo que se aplicó y lo que no se pudo aplicar. */
@Composable
private fun ParseReport(parsed: CheatParseResult) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(SteamSurface2)
            .padding(12.dp)
            .verticalScroll(rememberScrollState())
    ) {
        if (parsed.errors.isNotEmpty()) {
            ReportLine(
                title = "No se puede usar este código",
                lines = parsed.errors,
                color = MaterialTheme.colorScheme.error
            )
        }
        if (parsed.warnings.isNotEmpty()) {
            if (parsed.errors.isEmpty()) {
                ReportLine(
                    title = "Parte del código se ha omitido",
                    lines = parsed.warnings,
                    color = SteamTextSecondary
                )
            } else {
                ReportLine(
                    title = "También se ha omitido",
                    lines = parsed.warnings,
                    color = SteamTextSecondary
                )
            }
        }
        if (parsed.errors.isEmpty() && parsed.warnings.isEmpty()) {
            val count = parsed.ops.size
            Text(
                text  = if (count == 0) {
                    "No hay códigos que aplicar todavía."
                } else {
                    "Listo: se aplicarán $count operación${if (count == 1) "" else "es"}."
                },
                style = MaterialTheme.typography.bodySmall,
                color = SteamCyan
            )
        }
    }
}

@Composable
private fun ReportLine(title: String, lines: List<String>, color: Color) {
    Text(text = title, style = MaterialTheme.typography.labelSmall, color = color)
    Spacer(modifier = Modifier.height(4.dp))
    lines.forEach { line ->
        Text(
            text  = line,
            style = MaterialTheme.typography.bodySmall,
            color = SteamTextSecondary
        )
    }
    Spacer(modifier = Modifier.height(8.dp))
}

/** Una fila de la lista: casilla, nombre, resumen y acciones. */
@Composable
private fun CheatRow(
    entry    : CheatEntry,
    onToggle : (Boolean) -> Unit,
    onEdit   : () -> Unit,
    onDelete : () -> Unit
) {
    val parsed = remember(entry.lines) { CheatParser.parse(entry.source) }
    val summary = when {
        entry.lines.isEmpty() -> "Sin código"
        !parsed.accepted       -> "No se pudo aplicar"
        else                  -> "${parsed.ops.size} operación${if (parsed.ops.size == 1) "" else "es"}"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SteamSurface2)
            .border(1.dp, SteamBorder, RoundedCornerShape(12.dp))
            .clickable(onClick = onEdit)
            .padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
            .testTag("cheat_row_${entry.id}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Switch(
            checked         = entry.enabled,
            onCheckedChange = onToggle,
            colors          = SwitchDefaults.colors(
                checkedThumbColor   = Color.White,
                checkedTrackColor   = SteamCyan,
                uncheckedThumbColor = SteamTextMuted,
                uncheckedTrackColor = SteamSurface3,
                uncheckedBorderColor = SteamBorder
            ),
            modifier = Modifier.testTag("cheat_switch_${entry.id}")
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text  = entry.name.ifBlank { "Sin nombre" },
                style = MaterialTheme.typography.bodyMedium,
                color = SteamTextPrimary,
                maxLines = 1
            )
            Text(
                text  = summary,
                style = MaterialTheme.typography.bodySmall,
                color = if (entry.enabled && parsed.accepted) SteamCyan else SteamTextSecondary
            )
        }

        IconButton(
            onClick = onDelete,
            modifier = Modifier.testTag("cheat_delete_${entry.id}")
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                // El icono no lleva etiqueta propia: el botón dice "borrar" y
                // la fila ya dice qué se va a borrar.
                contentDescription = null,
                tint     = SteamTextMuted,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** Colores de los campos de texto, para que no aparezcan los verdes por defecto. */
@Composable
private fun steamTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor   = SteamCyan,
    unfocusedBorderColor = SteamBorder,
    focusedTextColor     = SteamTextPrimary,
    unfocusedTextColor   = SteamTextPrimary,
    cursorColor          = SteamCyan,
    focusedContainerColor = SteamSurface2,
    unfocusedContainerColor = SteamSurface2
)
