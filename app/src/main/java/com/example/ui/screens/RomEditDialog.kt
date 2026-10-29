package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.data.ROMItem
import com.example.ui.theme.SteamBorder
import com.example.ui.theme.SteamCyan
import com.example.ui.theme.SteamOnAccent
import com.example.ui.theme.SteamPink
import com.example.ui.theme.SteamSurface2
import com.example.ui.theme.SteamSurface3
import com.example.ui.theme.SteamTextMuted
import com.example.ui.theme.SteamTextPrimary
import com.example.ui.theme.SteamTextSecondary

/**
 * Edición de una ROM: su nombre en la biblioteca y su portada.
 *
 * Se abre al dejar la pulsada una tarjeta, que ya es el gesto con el que se
 * llegaba antes a "eliminar". Ese gesto abre ahora una ventana con las tres
 * cosas que se hacen con una ROM de la biblioteca sin tocar el archivo: cómo
 * se llama, cómo se ve, y quitarla del todo.
 *
 * Ninguna de las dos primeras toca la ROM. El nombre es sólo presentación y la
 * portada es una copia dentro del almacenamiento de la app, así que un nombre
 * mal escrito o una foto cropped se deshacen sin que quede nada que arreglar.
 */
@Composable
fun RomEditDialog(
    item          : ROMItem,
    onRename      : (String) -> Unit,
    onPickCover   : () -> Unit,
    onClearCover  : () -> Unit,
    onDelete      : () -> Unit,
    onDismiss     : () -> Unit
) {
    // El texto del campo arranca con el nombre actual pero vive en el estado del
    // diálogo: si se leyera del item, cada vez que el usuario escribiera el
    // estado del manager cambiaría y el cursor volvería al principio.
    var name by remember(item.id) { mutableStateOf(item.displayName.orEmpty()) }
    val hasCover = item.customCoverPath != null

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = SteamSurface2,
        titleContentColor = SteamTextPrimary,
        textContentColor  = SteamTextSecondary,
        shape             = RoundedCornerShape(18.dp),
        title = {
            Text(
                "Editar juego",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value         = name,
                    onValueChange = { name = it },
                    label         = { Text("Nombre en la biblioteca") },
                    singleLine    = true,
                    modifier      = Modifier
                        .fillMaxWidth()
                        .testTag("input_rom_name")
                )
                Text(
                    // Se explica aquí y no en la ayuda: es la duda que hace
                    // escribir el nombre de archivo por miedo a romper algo.
                    "El nombre real del archivo no se toca. Ahora se llama «${item.displayTitle}».",
                    style = MaterialTheme.typography.bodySmall,
                    color = SteamTextMuted
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(SteamSurface3)
                            .border(1.dp, SteamBorder, RoundedCornerShape(12.dp))
                            .clickable { onPickCover() },
                        contentAlignment = Alignment.Center
                    ) {
                        if (hasCover) {
                            AsyncImage(
                                model         = item.customCoverPath,
                                contentDescription = null,
                                contentScale  = ContentScale.Crop,
                                modifier      = Modifier.size(72.dp)
                            )
                        } else {
                            Icon(
                                Icons.Default.Image,
                                contentDescription = "Elegir portada",
                                tint   = SteamTextMuted,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Portada",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SteamTextPrimary
                        )
                        Text(
                            if (hasCover) "Estás usando una imagen propia." else "Se usa la carátula automática.",
                            style = MaterialTheme.typography.bodySmall,
                            color = SteamTextMuted,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(
                                onClick = onPickCover,
                                modifier = Modifier.testTag("btn_rom_pick_cover")
                            ) {
                                Icon(
                                    Icons.Default.Image,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (hasCover) "Cambiar" else "Elegir", style = MaterialTheme.typography.labelMedium)
                            }
                            // Sólo aparece si hay algo que deshacer: un botón de
                            // "quitar" que no quita nada confunde más que ayuda.
                            if (hasCover) {
                                TextButton(onClick = onClearCover) {
                                    Icon(
                                        Icons.Default.Restore,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Automática", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }

                // Quitar de la biblioteca.
                //
                // Antes vivía en un menú desplegable al que sólo se llegaba
                // con la pulsación larga; ahora que ese gesto abre esta
                // ventana, quitar el juego es una de las acciones de aquí, en
                // rosa y separada del resto por una línea: se ve que es
                // destructivo sin que se confunda con "Guardar".
                HorizontalDivider(color = SteamBorder, thickness = 0.5.dp)
                TextButton(
                    onClick = {
                        onDelete()
                        onDismiss()
                    },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("btn_rom_delete")
                ) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = null,
                        tint   = SteamPink,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "Quitar de la biblioteca",
                        style = MaterialTheme.typography.labelLarge,
                        color = SteamPink
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onRename(name)
                    onDismiss()
                },
                shape     = RoundedCornerShape(10.dp),
                colors    = ButtonDefaults.buttonColors(
                    containerColor = SteamCyan,
                    contentColor   = SteamOnAccent
                ),
                modifier  = Modifier.testTag("btn_rom_save")
            ) {
                Text("Guardar", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar", color = SteamTextSecondary)
            }
        }
    )
}
