package com.example.ui.screens

import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.EmulatorSettings
import com.example.data.ROMItem
import com.example.data.ROMManager
import com.example.ui.components.GbaConsole
import com.example.ui.theme.*
import kotlinx.coroutines.launch

/**
 * Biblioteca de ROM, al estilo Big Picture de Steam.
 *
 * Fondo negro OLED con un resplandor azul muy sutil en la esquina superior,
 * cabecera minimalista con logo, título y acciones, y una barra de búsqueda con
 * separadores de categoría. Cada ROM se muestra como una tarjeta con icono
 * grande, información densa y botón de jugar destacado.
 */

private val ROM_MIME_TYPES = arrayOf(
    "application/octet-stream",
    "application/zip",
    "application/x-zip-compressed",
    "application/x-7z-compressed"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LobbyScreen(
    romManager: ROMManager,
    settings: EmulatorSettings,
    onLaunchGame: (ROMItem) -> Unit,
    onUpdateSettings: (EmulatorSettings) -> Unit
) {
    val context        = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val romList   by romManager.romList.collectAsState()
    val isLoading by romManager.isLoading.collectAsState()

    var searchQuery       by remember { mutableStateOf("") }
    var selectedFilterTab by remember { mutableIntStateOf(0) }
    var showAboutDialog   by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    // Carpeta de ROMs
    //
    // Importar una colección entera a mano eran decenas de viajes al selector
    // de archivos. Con una carpeta elegida, el escaneo trae todo de una vez.
    // Se usa `OpenDocumentTree` y no `OpenDocument` porque el segundo devuelve
    // un archivo, y lo que se elige aquí es un directorio.
    var pendingFolderScan by remember { mutableStateOf(false) }

    fun scanFolder(uri: Uri) {
        coroutineScope.launch {
            romManager.importFolder(uri)
                .onSuccess { result ->
                    val message = when {
                        result.imported > 0 ->
                            "Importadas ${result.imported} ROM de la carpeta" +
                                if (result.skipped > 0) " (${result.skipped} ya estaban)" else ""
                        result.found == 0   -> "No hay ROM en esa carpeta"
                        else               -> "No hay ROM nuevas en esa carpeta"
                    }
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
                .onFailure { err ->
                    Toast.makeText(
                        context,
                        err.message ?: "No se pudo leer la carpeta",
                        Toast.LENGTH_LONG
                    ).show()
                }
        }
    }

    // Si el usuario eligió una carpeta con autoescaneo activado, se recorre al
    // abrir la biblioteca: los juegos nuevos que copia a la SD aparecen solos.
    LaunchedEffect(settings.romFolderUri, settings.autoScanRomFolder) {
        if (settings.autoScanRomFolder && settings.romFolderUri.isNotBlank()) {
            scanFolder(Uri.parse(settings.romFolderUri))
        }
    }

    // Nombre legible de la carpeta. Se pregunta al proveedor de documentos, no se
    // recorta la URI: recortar deja `primary%3ADownload%2FPrueba` en pantalla.
    val folderLabel = produceState<String?>(
        initialValue = null,
        key1 = settings.romFolderUri
    ) {
        val configured = settings.romFolderUri
        value = if (configured.isBlank()) {
            null
        } else {
            romManager.folderDisplayName(Uri.parse(configured))
                ?: configured.substringAfterLast("/").substringAfterLast(':')
                    .ifBlank { null }
        }
    }.value

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            val asString = uri.toString()
            // La carpeta sólo sirve si la biblioteca guarda la URI: el diálogo la
            // necesita para mostrarla y para el autoescaneo.
            onUpdateSettings(settings.copy(romFolderUri = asString))
            pendingFolderScan = true
        }
    }

    // El escaneo arranca sólo cuando el diálogo de ajustes ya ha devuelto el
    // control: `onUpdateSettings` es asíncrono para Compose, así que escanear
    // dentro del propio callback abriría un segundo diálogo encima del primero.
    LaunchedEffect(pendingFolderScan) {
        if (pendingFolderScan) {
            pendingFolderScan = false
            val configured = settings.romFolderUri
            if (configured.isNotBlank()) scanFolder(Uri.parse(configured))
        }
    }

    val romPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                val result = romManager.importRomFromUri(uri)
                result.onSuccess { item ->
                    Toast.makeText(context, "Cargado: ${item.displayTitle}", Toast.LENGTH_SHORT).show()
                    onLaunchGame(item)
                }.onFailure { err ->
                    Log.w("LobbyScreen", "Importación rechazada: ${err.message}")
                    Toast.makeText(context, err.message ?: "Ese archivo no es una ROM", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    val filteredList = remember(romList, searchQuery, selectedFilterTab) {
        romList.filter { item ->
            val matchesSearch = item.displayTitle.contains(searchQuery, ignoreCase = true) ||
                    item.gameCode.contains(searchQuery, ignoreCase = true) ||
                    item.fileName.contains(searchQuery, ignoreCase = true)
            val matchesTab = when (selectedFilterTab) {
                1 -> item.isHackRom
                2 -> item.isFavorite
                else -> true
            }
            matchesSearch && matchesTab
        }
    }

    // Fondo con resplandor degradado
    val bgGlowBrush = remember {
        Brush.radialGradient(
            colors = listOf(SteamCyan.copy(alpha = 0.06f), Color.Transparent),
            center = Offset(0f, 0f),
            radius = 900f
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SteamBg)
    ) {
        // Resplandor ambiental en la esquina superior izquierda
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
                .background(bgGlowBrush)
        )

        Scaffold(
            topBar = {
                SteamTopBar(
                    romCount        = romList.size,
                    onAbout         = { showAboutDialog = true },
                    onSettings      = { showSettingsDialog = true },
                    onLoadRom       = { romPickerLauncher.launch(ROM_MIME_TYPES) }
                )
            },
            containerColor = Color.Transparent
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (romList.isNotEmpty()) {
                    // Búsqueda y filtros
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .padding(top = 8.dp, bottom = 4.dp)
                    ) {
                        // Barra de búsqueda
                        OutlinedTextField(
                            value             = searchQuery,
                            onValueChange     = { searchQuery = it },
                            modifier          = Modifier
                                .fillMaxWidth()
                                .testTag("input_search_rom"),
                            placeholder = {
                                Text(
                                    "Buscar en la biblioteca…",
                                    color    = SteamTextMuted,
                                    fontSize = 14.sp
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Search,
                                    contentDescription = null,
                                    tint   = SteamTextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            trailingIcon = {
                                AnimatedVisibility(visible = searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Limpiar",
                                            tint     = SteamTextSecondary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            },
                            singleLine = true,
                            shape      = RoundedCornerShape(12.dp),
                            colors     = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor   = SteamSurface3,
                                unfocusedContainerColor = SteamSurface2,
                                focusedBorderColor      = SteamCyan.copy(alpha = 0.7f),
                                unfocusedBorderColor    = SteamBorder,
                                focusedTextColor        = SteamTextPrimary,
                                unfocusedTextColor      = SteamTextPrimary,
                                cursorColor             = SteamCyan
                            )
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Pestañas de categoría, al estilo de Steam
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            SteamCategoryTab(
                                label      = "Todos",
                                count      = romList.size,
                                isSelected = selectedFilterTab == 0,
                                onClick    = { selectedFilterTab = 0 }
                            )
                            SteamCategoryTab(
                                label      = "Hackroms",
                                count      = romList.count { it.isHackRom },
                                isSelected = selectedFilterTab == 1,
                                onClick    = { selectedFilterTab = 1 }
                            )
                            SteamCategoryTab(
                                label      = "Favoritos",
                                count      = romList.count { it.isFavorite },
                                isSelected = selectedFilterTab == 2,
                                onClick    = { selectedFilterTab = 2 }
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    // Separador fino
                    HorizontalDivider(
                        color     = SteamBorder,
                        thickness = 0.5.dp
                    )
                }

                // Cargando
                AnimatedVisibility(visible = isLoading) {
                    LinearProgressIndicator(
                        modifier  = Modifier.fillMaxWidth(),
                        color     = SteamCyan,
                        trackColor = SteamSurface2
                    )
                }

                // Contenido
                when {
                    romList.isEmpty() -> SteamEmptyState(
                        onLoadRom = { romPickerLauncher.launch(ROM_MIME_TYPES) }
                    )

                    filteredList.isEmpty() -> Box(
                        modifier            = Modifier.fillMaxSize(),
                        contentAlignment    = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = null,
                                tint     = SteamTextMuted,
                                modifier = Modifier.size(40.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                "Sin resultados",
                                style = MaterialTheme.typography.titleMedium,
                                color = SteamTextSecondary
                            )
                            Text(
                                "Prueba con otro término",
                                style = MaterialTheme.typography.bodySmall,
                                color = SteamTextMuted
                            )
                        }
                    }

                    else -> LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(1.dp),
                        contentPadding      = PaddingValues(bottom = 24.dp),
                        modifier            = Modifier.testTag("list_roms")
                    ) {
                        items(filteredList, key = { it.id }) { romItem ->
                            SteamRomRow(
                                item            = romItem,
                                onPlay          = { onLaunchGame(romItem) },
                                onToggleFavorite = { romManager.toggleFavorite(romItem.id) },
                                onDelete        = { romManager.removeRom(romItem.id) }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAboutDialog) {
        AboutDialog(onDismissRequest = { showAboutDialog = false })
    }
    if (showSettingsDialog) {
        SettingsDialog(
            currentSettings   = settings,
            onSaveSettings    = onUpdateSettings,
            onDismissRequest  = { showSettingsDialog = false },
            onPickRomFolder   = { folderPickerLauncher.launch(null) },
            onScanRomFolder   = {
                val configured = settings.romFolderUri
                if (configured.isNotBlank()) scanFolder(Uri.parse(configured))
            },
            folderName        = folderLabel,
            isFolderConfigured = settings.romFolderUri.isNotBlank()
        )
    }
}

// Barra superior

@Composable
private fun SteamTopBar(
    romCount   : Int,
    onAbout    : () -> Unit,
    onSettings : () -> Unit,
    onLoadRom  : () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .background(SteamSurface1)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Marca
            //
            // El `weight` no es decorativo: en vertical la marca y los botones no
            // caben juntos, y sin él Compose mide la marca a su ancho intrínseco,
            // deja a los botones sin sitio y el texto acaba debajo del botón de
            // cargar. Con `weight`, la marca es lo único que cede espacio.
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .border(
                            1.dp,
                            SteamCyan.copy(alpha = 0.4f),
                            RoundedCornerShape(10.dp)
                        )
                ) {
                    Image(
                        painter           = painterResource(id = R.drawable.as_logo_1790779588412),
                        contentDescription = "AS Logo",
                        modifier          = Modifier.fillMaxSize(),
                        contentScale      = ContentScale.Crop
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text       = "AS GBA",
                        style      = MaterialTheme.typography.titleLarge,
                        color      = SteamTextPrimary,
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis
                    )
                    if (romCount > 0) {
                        // "10 juegos en biblioteca" no cabe en vertical junto a los
                        // botones, así que ahí se queda en "10 juegos" en vez de
                        // recortarse a media palabra.
                        BoxWithConstraints {
                            val compact = maxWidth < 150.dp
                            Text(
                                text       = "$romCount juego${if (romCount != 1) "s" else ""}" +
                                        if (compact) "" else " en biblioteca",
                                style      = MaterialTheme.typography.labelSmall,
                                color      = SteamTextMuted,
                                maxLines   = 1,
                                overflow   = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // Acciones
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment     = Alignment.CenterVertically
            ) {
                // Cargar ROM, botón destacado
                FilledTonalButton(
                    onClick   = onLoadRom,
                    shape     = RoundedCornerShape(10.dp),
                    colors    = ButtonDefaults.filledTonalButtonColors(
                        containerColor = SteamCyan,
                        contentColor   = Color(0xFF001626)
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    modifier  = Modifier.testTag("fab_load_rom")
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        "Cargar ROM",
                        style      = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Acerca de
                SteamIconBtn(
                    icon              = Icons.Default.Info,
                    contentDescription = "Información",
                    onClick           = onAbout,
                    testTag           = "btn_lobby_about"
                )

                // Ajustes
                SteamIconBtn(
                    icon              = Icons.Rounded.Settings,
                    contentDescription = "Ajustes",
                    onClick           = onSettings,
                    testTag           = "btn_lobby_settings"
                )
            }
        }

        // Línea de acento del borde inferior
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(SteamCyan.copy(alpha = 0.5f), SteamBorder, Color.Transparent)
                    )
                )
        )
    }
}

@Composable
private fun SteamIconBtn(
    icon              : androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription : String,
    onClick           : () -> Unit,
    testTag           : String = ""
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(SteamSurface3)
            .border(1.dp, SteamBorder, RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .then(if (testTag.isNotEmpty()) Modifier.testTag(testTag) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = contentDescription,
            tint               = SteamTextSecondary,
            modifier           = Modifier.size(18.dp)
        )
    }
}

// Pestaña de categoría

@Composable
private fun SteamCategoryTab(
    label      : String,
    count      : Int,
    isSelected : Boolean,
    onClick    : () -> Unit
) {
    val cyan = SteamCyan
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                text  = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (isSelected) SteamTextPrimary else SteamTextSecondary,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
            )
            if (count > 0) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            if (isSelected) cyan.copy(alpha = 0.2f)
                            else SteamSurface3
                        )
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text  = count.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) cyan else SteamTextMuted,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        // Indicador de subrayado
        Box(
            modifier = Modifier
                .height(2.dp)
                .width(if (isSelected) 28.dp else 0.dp)
                .clip(CircleShape)
                .background(cyan)
        )
    }
}

// Fila de ROM

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SteamRomRow(
    item            : ROMItem,
    onPlay          : () -> Unit,
    onToggleFavorite : () -> Unit,
    onDelete        : () -> Unit
) {
    var showOptions by remember { mutableStateOf(false) }

    val isHack        = item.isHackRom
    val iconGradient  = if (isHack) GradientGameIconHack else GradientGameIconNormal
    val badgeColor    = if (isHack) SteamPink else SteamTextMuted
    val badgeBg       = if (isHack) SteamPinkSoft else SteamSurface3

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(SteamSurface1)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick     = onPlay,
                    onLongClick = { showOptions = true }
                )
                .testTag("rom_card_${item.id.take(15)}")
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Caja del icono
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Brush.linearGradient(iconGradient))
                    .border(
                        1.dp,
                        if (isHack) SteamPink.copy(alpha = 0.25f)
                        else SteamCyan.copy(alpha = 0.12f),
                        RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector        = GbaConsole,
                    contentDescription = null,
                    tint               = if (isHack) SteamPink.copy(alpha = 0.9f)
                                        else SteamCyan.copy(alpha = 0.7f),
                    modifier           = Modifier.size(width = 22.dp, height = 28.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            // Datos del juego
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text     = item.fileTitle,
                    style    = MaterialTheme.typography.titleSmall,
                    color    = SteamTextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Badge: HACKROM o código de juego
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(badgeBg)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text       = if (isHack) "HACKROM" else item.gameCode,
                            style      = MaterialTheme.typography.labelSmall,
                            color      = badgeColor,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                    Text(
                        text  = item.formattedSize,
                        style = MaterialTheme.typography.labelSmall,
                        color = SteamTextMuted
                    )
                }
            }

            // Favorito
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable { onToggleFavorite() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (item.isFavorite) Icons.Default.Favorite
                                  else Icons.Default.FavoriteBorder,
                    contentDescription = "Favorito",
                    tint     = if (item.isFavorite) SteamPink else SteamTextMuted,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            // Botón de jugar
            Box(
                modifier = Modifier
                    .height(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(SteamCyan)
                    .clickable { onPlay() }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Jugar",
                        tint     = Color(0xFF001626),
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        "Jugar",
                        style      = MaterialTheme.typography.labelLarge,
                        color      = Color(0xFF001626),
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }

        // Separador inferior fino
        HorizontalDivider(
            modifier  = Modifier.align(Alignment.BottomCenter),
            color     = SteamBorder,
            thickness = 0.5.dp
        )

        DropdownMenu(
            expanded          = showOptions,
            onDismissRequest  = { showOptions = false },
            modifier          = Modifier
                .background(SteamSurface2)
                .testTag("menu_rom_options")
        ) {
            DropdownMenuItem(
                text = {
                    Text(
                        "Eliminar de la biblioteca",
                        color = SteamTextPrimary
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = null,
                        tint  = SteamPink
                    )
                },
                onClick = {
                    showOptions = false
                    onDelete()
                }
            )
        }
    }
}

// Estado vacío

@Composable
private fun SteamEmptyState(onLoadRom: () -> Unit) {
    Box(
        modifier         = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier            = Modifier.padding(horizontal = 40.dp)
        ) {
            // Icono grande de consola
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(SteamSurface2)
                    .border(1.dp, SteamCyan.copy(alpha = 0.25f), RoundedCornerShape(24.dp)),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter            = painterResource(id = R.drawable.as_logo_1790779588412),
                    contentDescription = "AS Logo",
                    modifier           = Modifier.fillMaxSize(),
                    contentScale       = ContentScale.Crop
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            Text(
                text      = "Biblioteca vacía",
                style     = MaterialTheme.typography.headlineMedium,
                color     = SteamTextPrimary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text      = "Carga tus archivos .gba o .zip desde la memoria interna o tarjeta SD para empezar a jugar.",
                style     = MaterialTheme.typography.bodyMedium,
                color     = SteamTextSecondary,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Botón principal
            Button(
                onClick        = onLoadRom,
                shape          = RoundedCornerShape(12.dp),
                colors         = ButtonDefaults.buttonColors(
                    containerColor = SteamCyan,
                    contentColor   = Color(0xFF001626)
                ),
                contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp),
                modifier       = Modifier.testTag("btn_empty_load_rom")
            ) {
                Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Cargar ROM (.gba / .zip)",
                    style      = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }
    }
}
