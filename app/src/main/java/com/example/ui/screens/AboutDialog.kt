package com.example.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.R
import com.example.ui.theme.*

/**
 * About / Information Dialog — Steam Big Picture style.
 * Developed by Andrés Socorro.
 */
@Composable
fun AboutDialog(onDismissRequest: () -> Unit) {
    Dialog(onDismissRequest = onDismissRequest) {
        Surface(
            shape         = RoundedCornerShape(20.dp),
            color         = SteamSurface1,
            modifier      = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
                .border(1.dp, SteamBorder, RoundedCornerShape(20.dp))
                .testTag("dialog_about")
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
            ) {
                // Header con degradado
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    SteamCyan.copy(alpha = 0.15f),
                                    SteamSurface1
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .border(1.dp, SteamCyan.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                        ) {
                            Image(
                                painter           = painterResource(id = R.drawable.as_logo_1790779588412),
                                contentDescription = "AS Logo",
                                modifier          = Modifier.fillMaxSize(),
                                contentScale      = ContentScale.Crop
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text      = "AS GBA Emulator",
                            style     = MaterialTheme.typography.headlineSmall,
                            color     = SteamTextPrimary,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                // Content
                Column(
                    modifier            = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Developer badge
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(SteamCyan.copy(alpha = 0.08f))
                            .border(1.dp, SteamCyan.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text      = stringResource(id = R.string.about_developer),
                            style     = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color     = SteamCyan,
                            textAlign = TextAlign.Center,
                            modifier  = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text      = "Emulador moderno de Game Boy Advance para Android. Biblioteca, ajustes, guardado de partida y controles viven dentro de AS GBA Emulator.",
                        style     = MaterialTheme.typography.bodySmall,
                        color     = SteamTextSecondary,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    // Section title
                    Row(
                        modifier              = Modifier.fillMaxWidth(),
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        HorizontalDivider(
                            modifier  = Modifier.weight(1f),
                            color     = SteamBorder,
                            thickness = 0.5.dp
                        )
                        Text(
                            "ARQUITECTURA",
                            style  = MaterialTheme.typography.labelSmall,
                            color  = SteamTextMuted,
                            letterSpacing = 1.2.sp
                        )
                        HorizontalDivider(
                            modifier  = Modifier.weight(1f),
                            color     = SteamBorder,
                            thickness = 0.5.dp
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    AboutTechItem(
                        icon        = Icons.Default.Memory,
                        title       = "Núcleo C++20 / JNI",
                        description = "ARM7TDMI a 16.78 MHz con PPU, APU, temporizadores, DMA y cartucho."
                    )
                    AboutTechItem(
                        icon        = Icons.Default.Speed,
                        title       = "Hackroms 32 MB / 64 MB",
                        description = "DirectByteBuffer fuera de heap (zero-copy) para Pokémon Unbound y Radical Red."
                    )
                    AboutTechItem(
                        icon        = Icons.Default.Code,
                        title       = "Formatos y Almacenamiento",
                        description = ".gba, .zip y .7z con Scoped Storage (Storage Access Framework)."
                    )
                    AboutTechItem(
                        icon        = Icons.Default.Info,
                        title       = "Audio y Controles",
                        description = "AAudio / OpenSL ES de baja latencia. Mandos Bluetooth/USB plug & play."
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = onDismissRequest,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("btn_close_about"),
                        shape  = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SteamCyan,
                            contentColor   = Color(0xFF001626)
                        )
                    ) {
                        Text(
                            "Entendido",
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
private fun AboutTechItem(
    icon        : ImageVector,
    title       : String,
    description : String
) {
    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(SteamCyan.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector        = icon,
                contentDescription = null,
                tint               = SteamCyan,
                modifier           = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text       = title,
                style      = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color      = SteamTextPrimary
            )
            Text(
                text  = description,
                style = MaterialTheme.typography.bodySmall,
                color = SteamTextSecondary
            )
        }
    }
}
