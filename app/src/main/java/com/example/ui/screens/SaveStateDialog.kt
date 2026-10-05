package com.example.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.SaveStateSlot
import com.example.ui.components.GbaConsole
import com.example.ui.theme.*

/**
 * Save State Slot Selector — Steam Big Picture style.
 * Developed by Andrés Socorro.
 */
@Composable
fun SaveStateDialog(
    slots            : List<SaveStateSlot>,
    onSaveSlot       : (Int) -> Unit,
    onLoadSlot       : (Int) -> Unit,
    onDismissRequest : () -> Unit
) {
    Dialog(onDismissRequest = onDismissRequest) {
        Surface(
            shape    = RoundedCornerShape(20.dp),
            color    = SteamSurface1,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
                .border(1.dp, SteamBorder, RoundedCornerShape(20.dp))
                .testTag("dialog_save_states")
        ) {
            Column {
                // Header
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(SteamCyan.copy(alpha = 0.10f), SteamSurface1)
                            )
                        )
                        .padding(horizontal = 24.dp, vertical = 18.dp)
                ) {
                    Column {
                        Text(
                            text  = "Ranuras de Guardado",
                            style = MaterialTheme.typography.titleLarge,
                            color = SteamTextPrimary
                        )
                        Text(
                            text  = "Guarda o restaura partidas en cualquier momento",
                            style = MaterialTheme.typography.bodySmall,
                            color = SteamTextSecondary
                        )
                    }
                }

                HorizontalDivider(color = SteamBorder, thickness = 0.5.dp)

                LazyColumn(
                    modifier            = Modifier
                        .weight(1f, fill = false)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(slots) { slot ->
                        SteamSaveSlotCard(
                            slot   = slot,
                            onSave = { onSaveSlot(slot.slotIndex) },
                            onLoad = { onLoadSlot(slot.slotIndex) }
                        )
                    }
                }

                HorizontalDivider(color = SteamBorder, thickness = 0.5.dp)

                Box(modifier = Modifier.padding(16.dp)) {
                    Button(
                        onClick  = onDismissRequest,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("btn_close_save_states"),
                        shape    = RoundedCornerShape(12.dp),
                        colors   = ButtonDefaults.buttonColors(
                            containerColor = SteamCyan,
                            contentColor   = Color(0xFF001626)
                        )
                    ) {
                        Text(
                            "Cerrar",
                            style      = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SteamSaveSlotCard(
    slot   : SaveStateSlot,
    onSave : () -> Unit,
    onLoad : () -> Unit
) {
    val hasData = slot.exists

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SteamSurface2)
            .border(
                1.dp,
                if (hasData) SteamCyan.copy(alpha = 0.2f) else SteamBorder,
                RoundedCornerShape(12.dp)
            )
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Screenshot thumbnail
        Box(
            modifier = Modifier
                .size(width = 80.dp, height = 54.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(SteamSurface1)
                .border(
                    1.dp,
                    if (hasData) SteamCyan.copy(alpha = 0.25f) else SteamBorder,
                    RoundedCornerShape(8.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (slot.thumbnailBitmap != null) {
                Image(
                    bitmap             = slot.thumbnailBitmap.asImageBitmap(),
                    contentDescription = "Captura de guardado",
                    contentScale       = ContentScale.Fit,
                    modifier           = Modifier.fillMaxSize()
                )
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector        = GbaConsole,
                        contentDescription = null,
                        tint               = SteamTextMuted,
                        modifier           = Modifier.size(width = 22.dp, height = 28.dp)
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text("Vacía", fontSize = 9.sp, color = SteamTextMuted)
                }
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Slot info
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text       = "Ranura ${slot.slotIndex}",
                style      = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color      = SteamTextPrimary
            )
            Text(
                text  = if (hasData) slot.formattedDate else "Sin datos",
                style = MaterialTheme.typography.bodySmall,
                color = if (hasData) SteamCyan else SteamTextMuted
            )
        }

        // Save button
        IconButton(
            onClick  = onSave,
            modifier = Modifier.testTag("btn_save_slot_${slot.slotIndex}")
        ) {
            Icon(
                imageVector        = Icons.Default.Save,
                contentDescription = "Guardar",
                tint               = SteamTextSecondary
            )
        }

        // Load button
        IconButton(
            onClick  = onLoad,
            enabled  = hasData,
            modifier = Modifier.testTag("btn_load_slot_${slot.slotIndex}")
        ) {
            Icon(
                imageVector        = Icons.Default.Download,
                contentDescription = "Cargar",
                tint               = if (hasData) SteamCyan else SteamTextMuted
            )
        }
    }
}
