package com.example.ui.screens

import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.example.R
import com.example.core.emulation.ConsoleKind
import com.example.core.link.LinkState
import com.example.data.CoverArt
import com.example.data.EmulatorSettings
import com.example.data.ROMItem
import com.example.data.ROMManager
import com.example.ui.components.GbaConsole
import com.example.ui.theme.*
import kotlinx.coroutines.launch

/**
 * Biblioteca de ROM, al estilo cartelera de consola.
 *
 * Fondo en pizarra oscura con un velo de acento arriba (el color que
 * eligió el usuario), cabecera con marca y acciones, búsqueda flotante
 * en cristal y pestañas con indicador deslizante. Cada ROM es una
 * tarjeta de cristal (glassmorphism) en una cuadrícula adaptable:
 * portada 3:4, título, píldoras de metadatos, favorito y jugar.
 *
 * Movimiento: entrada escalonada de las tarjetas, elevación y zoom de
 * portada al pulsar, resplandor deslizante en el botón de jugar,
 * rebote elástico del corazón y deslizamiento del indicador de
 * pestañas. Todo el brillo sigue el acento del tema.
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
    linkState: LinkState,
    onLaunchGame: (ROMItem) -> Unit,
    onUpdateSettings: (EmulatorSettings) -> Unit,
    onOpenLink: () -> Unit
) {
    val context        = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val romList   by romManager.romList.collectAsState()
    val isLoading by romManager.isLoading.collectAsState()

    var searchQuery       by remember { mutableStateOf("") }
    var selectedFilterTab by remember { mutableIntStateOf(0) }
    var showAboutDialog   by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    // La ROM que se está editando con la pulsación larga. Se guarda el item
    // entero y no su id para que el diálogo abra con el estado actual (nombre y
    // portada) sin tener que ir a buscarlo otra vez por la lista.
    var editingRom by remember { mutableStateOf<ROMItem?>(null) }

    // Id de la ROM cuya portada se está eligiendo en el selector de archivos.
    // Vive aparte de `editingRom` porque el selector de archivos tapa la
    // pantalla: si el diálogo se cerrara al perder el foco, al volver habría que
    // saber a qué ROM aplicar la imagen elegida.
    var pendingCoverRomId by remember { mutableStateOf<String?>(null) }

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

    // Fondo: pizarra oscura profunda en vez de negro plano, con un velo
    // de acento arriba y un resplandor radial en la esquina superior.
    //
    // Clave explícita en los tres: el `remember` sin argumentos se quedaría
    // con el acento del primer dibujado, y el degradado del fondo sería lo
    // último en cambiar al elegir otro tema en Ajustes.
    val bgBrush = remember(SteamCyan) {
        Brush.verticalGradient(
            colors = listOf(Color(0xFF11151F), SteamBg),
            startY = 0f,
            endY = 1200f
        )
    }
    val accentVeil = remember(SteamCyan) {
        Brush.verticalGradient(
            colors = listOf(SteamCyan.copy(alpha = 0.09f), Color.Transparent),
            startY = 0f,
            endY = 640f
        )
    }
    val bgGlowBrush = remember(SteamCyan) {
        Brush.radialGradient(
            colors = listOf(SteamCyan.copy(alpha = 0.06f), Color.Transparent),
            center = Offset(0f, 0f),
            radius = 900f
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgBrush)
    ) {
        // Velo de acento y resplandor ambiental
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(420.dp)
                .background(accentVeil)
        )
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
                    onLoadRom       = { romPickerLauncher.launch(ROM_MIME_TYPES) },
                    onLink          = onOpenLink,
                    linkActive      = linkState !is LinkState.Inactive
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
                        // Barra de búsqueda flotante: superficie semitransparente
                        // con sombra, como cristal sobre el degradado. (El desenfoque
                        // real de fondo necesita Android 12 y RenderEffect; la
                        // translucidez da el mismo efecto en todas las versiones.)
                        OutlinedTextField(
                            value             = searchQuery,
                            onValueChange     = { searchQuery = it },
                            modifier          = Modifier
                                .fillMaxWidth()
                                .shadow(14.dp, RoundedCornerShape(16.dp))
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
                            shape      = RoundedCornerShape(16.dp),
                            colors     = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor   = SteamSurface2.copy(alpha = 0.94f),
                                unfocusedContainerColor = SteamSurface2.copy(alpha = 0.72f),
                                focusedBorderColor      = SteamCyan.copy(alpha = 0.65f),
                                unfocusedBorderColor    = SteamBorder,
                                focusedTextColor        = SteamTextPrimary,
                                unfocusedTextColor      = SteamTextPrimary,
                                cursorColor             = SteamCyan
                            )
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Pestañas de categoría con indicador deslizante
                        SteamCategoryTabs(
                            counts     = listOf(
                                romList.size,
                                romList.count { it.isHackRom },
                                romList.count { it.isFavorite }
                            ),
                            selectedTab = selectedFilterTab,
                            onSelect    = { selectedFilterTab = it }
                        )

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

                    else -> BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        // La cuadrícula se adapta a la forma de la pantalla,
                        // no sólo a su ancho.
                        //
                        // En vertical manda el cartel: portada grande arriba y
                        // datos debajo, como en una estantería. En horizontal
                        // (que es como se juega) una portada de 3:4 a cinco
                        // columnas deja el botón de jugar bajo el pliegue, así
                        // que ahí la tarjeta se tiende: portada a la izquierda
                        // y datos a la derecha, en menos columnas y más anchas.
                        val wideCards = maxHeight < 480.dp
                        val columns = if (wideCards) {
                            GridCells.Fixed(
                                ((maxWidth / 260.dp).toInt()).coerceIn(2, 4)
                            )
                        } else {
                            GridCells.Adaptive(150.dp)
                        }

                        LazyVerticalGrid(
                            columns = columns,
                            verticalArrangement   = Arrangement.spacedBy(14.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            contentPadding        = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            modifier              = Modifier
                                .fillMaxSize()
                                .testTag("list_roms")
                        ) {
                            itemsIndexed(filteredList, key = { _, item -> item.id }) { index, romItem ->
                                GameCard(
                                    item            = romItem,
                                    index           = index,
                                    wide            = wideCards,
                                    onPlay          = { onLaunchGame(romItem) },
                                    onToggleFavorite = { romManager.toggleFavorite(romItem.id) },
                                    onEdit          = { editingRom = romItem }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Portada propia de una ROM.
    //
    // El launcher guarda el Uri y el diálogo se queda abierto mientras el
    // usuario elige: se guarda el id aparte porque el `editingRom` puede quedar
    // obsoleto mientras el selector de archivos está delante, y el cover elegido
    // se aplicaría sobre una ficha equivocada.
    val coverPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { picked: Uri? ->
        val target = editingRom ?: pendingCoverRomId?.let { id ->
            romList.firstOrNull { it.id == id }
        }
        pendingCoverRomId = null
        val uri = picked ?: return@rememberLauncherForActivityResult
        if (target == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            // La copia va en un hilo aparte: una foto de varios megapíxeles tarda
            // lo suyo y el diálogo se quedaría congelado mientras tanto.
            val ok = romManager.setCustomCover(target.id, uri)
            if (!ok) {
                Toast.makeText(context, "No se pudo usar esa imagen", Toast.LENGTH_SHORT).show()
            }
        }
    }

    editingRom?.let { editing ->
        RomEditDialog(
            item         = editing,
            onRename     = { name -> romManager.renameRom(editing.id, name) },
            onPickCover  = {
                pendingCoverRomId = editing.id
                coverPickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            onClearCover = { romManager.clearCustomCover(editing.id) },
            onDelete     = { romManager.removeRom(editing.id) },
            onDismiss    = { editingRom = null }
        )
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
    onLoadRom  : () -> Unit,
    onLink     : () -> Unit,
    linkActive : Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .background(SteamSurface1.copy(alpha = 0.92f))
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            // En vertical no caben la marca entera y cuatro botones: medidos,
            // los botones se quedaban con ~280 de 353 dp y al nombre de la app
            // le caían quince, de ahí el "AS…" recortado de antes. En cuanto
            // el ancho aprieta, "Cargar ROM" se queda con su icono y los botones
            //_icono_ encogen: la marca recupera el sitio y se lee entera.
            val compact = maxWidth < 520.dp
            val iconSize = if (compact) 34.dp else 38.dp

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = if (compact) 10.dp else 14.dp),
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
                        // recortarse a media palabra. El nombre del ancho de
                        // esta caja es `tight` y no `compact` para no tapar el
                        // de la barra, que decide la forma de los botones.
                        BoxWithConstraints {
                            val tight = maxWidth < 150.dp
                            Text(
                                text       = "$romCount juego${if (romCount != 1) "s" else ""}" +
                                        if (tight) "" else " en biblioteca",
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
                // Cargar ROM: el botón estrella, con gradiente de acento y
                // borde que respira. En vertical se queda con el icono para
                // no empujar la marca fuera de la barra.
                if (compact) {
                    SteamGradientIconButton(
                        icon              = Icons.Default.Add,
                        contentDescription = "Cargar ROM",
                        onClick           = onLoadRom,
                        testTag           = "fab_load_rom",
                        size              = iconSize
                    )
                } else {
                    SteamGradientButton(
                        onClick   = onLoadRom,
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
                            fontWeight = FontWeight.Bold,
                            color      = SteamOnAccent
                        )
                    }
                }

                // Enlace LAN
                //
                // El icono se enciende cuando hay sala o unión viva: la sala se
                // abre desde aquí, pero la partida se carga después, así que el
                // botón también es el sitio donde se vuelve a ver su estado.
                SteamIconBtn(
                    icon              = Icons.Default.Link,
                    contentDescription = "Enlace LAN",
                    onClick           = onLink,
                    testTag           = "btn_lobby_link",
                    tint              = if (linkActive) SteamCyan else SteamTextSecondary,
                    size              = iconSize
                )

                // Acerca de
                SteamIconBtn(
                    icon              = Icons.Default.Info,
                    contentDescription = "Información",
                    onClick           = onAbout,
                    testTag           = "btn_lobby_about",
                    size              = iconSize
                )

                // Ajustes
                SteamIconBtn(
                    icon              = Icons.Rounded.Settings,
                    contentDescription = "Ajustes",
                    onClick           = onSettings,
                    testTag           = "btn_lobby_settings",
                    size              = iconSize
                )
            }
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

/** Botón de acento reducido a su icono, para las barras que no caben. */
@Composable
private fun SteamGradientIconButton(
    icon              : ImageVector,
    contentDescription : String,
    onClick           : () -> Unit,
    testTag           : String = "",
    size              : Dp = 38.dp
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(
                        lerp(SteamCyan, Color.White, 0.25f),
                        SteamCyan,
                        lerp(SteamCyan, Color.Black, 0.12f)
                    )
                )
            )
            .border(1.dp, SteamCyan.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = contentDescription,
            tint               = SteamOnAccent,
            modifier           = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun SteamIconBtn(
    icon              : ImageVector,
    contentDescription : String,
    onClick           : () -> Unit,
    testTag           : String = "",
    tint              : Color = SteamTextSecondary,
    size              : Dp = 38.dp
) {
    Box(
        modifier = Modifier
            .size(size)
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
            tint               = tint,
            modifier           = Modifier.size(18.dp)
        )
    }
}

// Botón de acento: gradiente dinámico con borde iluminado

/**
 * Botón principal de la cabecera y del estado vacío.
 *
 * El fondo es un degradado de acento (más claro a la izquierda, más
 * oscuro a la derecha) y el borde respira: un pulso suave de brillo
 * que guía la vista hacia la acción principal sin distraer. El contenido
 * va en el azul casi negro de siempre, que contrasta con cualquier
 * acento que el usuario elija.
 */
@Composable
private fun SteamGradientButton(
    onClick : () -> Unit,
    modifier: Modifier = Modifier,
    content : @Composable RowScope.() -> Unit
) {
    val glow = rememberInfiniteTransition(label = "ctaGlow")
    val borderAlpha by glow.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.65f,
        animationSpec = infiniteRepeatable(tween(1600), RepeatMode.Reverse),
        label = "ctaBorderAlpha"
    )

    Box(
        modifier = modifier
            .shadow(10.dp, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(
                        lerp(SteamCyan, Color.White, 0.25f),
                        SteamCyan,
                        lerp(SteamCyan, Color.Black, 0.12f)
                    )
                )
            )
            .border(1.dp, SteamCyan.copy(alpha = borderAlpha), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            content = content
        )
    }
}

// Pestañas de categoría

/**
 * Pestañas con indicador deslizante.
 *
 * Las tres pestañas pesan igual, así que el indicador es un tercio de
 * la fila y se desplaza con un resorte al cambiar de selección: el
 * movimiento se siente físico en vez de telegráfico. El ancho se mide
 * sobre la fila ya dibujada, no a ojo, para que el indicador coincida
 * aunque la pantalla gire.
 */
@Composable
private fun SteamCategoryTabs(
    counts     : List<Int>,
    selectedTab: Int,
    onSelect   : (Int) -> Unit
) {
    val labels = listOf("Todos", "Hackroms", "Favoritos")
    val density = LocalDensity.current

    // Se mide en píxeles y se pasa a dp una sola vez. `onGloballyPositioned`
    // entrega píxeles, no dp: tomarlos por dp multiplicaba la fila por la
    // densidad de la pantalla (2,75 aquí), así que el indicador salía casi tan
    // ancho como las tres pestañas y, al pasar a la segunda o la tercera, se
    // marchaba fuera de la fila.
    var rowWidthPx by remember { mutableStateOf(0f) }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { rowWidthPx = it.size.width.toFloat() }
        ) {
            labels.forEachIndexed { index, label ->
                val selected = selectedTab == index
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onSelect(index) }
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text  = label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) SteamTextPrimary else SteamTextSecondary,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                    )
                    val count = counts.getOrNull(index) ?: 0
                    if (count > 0) {
                        Spacer(modifier = Modifier.width(5.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    if (selected) SteamCyan.copy(alpha = 0.22f)
                                    else SteamSurface3
                                )
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text  = count.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) SteamCyan else SteamTextMuted,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(2.dp))

        val segment = with(density) { (rowWidthPx / labels.size).toDp() }
        val indicatorOffset by animateDpAsState(
            targetValue = segment * selectedTab,
            animationSpec = spring(
                dampingRatio = 0.8f,
                stiffness = Spring.StiffnessLow
            ),
            label = "tabIndicatorOffset"
        )
        Box(
            modifier = Modifier
                .offset(x = indicatorOffset)
                .width(segment)
                .padding(horizontal = 18.dp)
                .height(3.dp)
                .clip(CircleShape)
                .background(SteamCyan)
        )
    }
}

// Tarjeta de juego

/**
 * Estado de pulsación de una tarjeta, compartido por sus dos formas.
 *
 * "Hover" no existe en táctil: la pulsación es su equivalente, y es la que
 * dispara elevación, brillo de borde y zoom de portada. Se agrupa en un
 * objeto para que la tarjeta de cartel y la tendida se muevan exactamente
 * igual sin duplicar las cinco animaciones.
 */
@Stable
private class CardTouchState(
    val pressed         : Boolean,
    val scale           : Float,
    val lift            : Dp,
    val elevation       : Dp,
    val borderAlpha     : Float,
    val coverZoom       : Float,
    val interactionSource: MutableInteractionSource
)

@Composable
private fun rememberCardTouchState(): CardTouchState {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessLow),
        label = "cardScale"
    )
    val lift by animateDpAsState(
        targetValue = if (pressed) (-3).dp else 0.dp,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow),
        label = "cardLift"
    )
    val elevation by animateDpAsState(
        targetValue = if (pressed) 12.dp else 3.dp,
        animationSpec = tween(200),
        label = "cardElevation"
    )
    val borderAlpha by animateFloatAsState(
        targetValue = if (pressed) 0.30f else 0.09f,
        animationSpec = tween(180),
        label = "cardBorder"
    )
    val coverZoom by animateFloatAsState(
        targetValue = if (pressed) 1.07f else 1f,
        animationSpec = tween(280, easing = FastOutSlowInEasing),
        label = "coverZoom"
    )

    return CardTouchState(
        pressed = pressed,
        scale = scale,
        lift = lift,
        elevation = elevation,
        borderAlpha = borderAlpha,
        coverZoom = coverZoom,
        interactionSource = interactionSource
    )
}

/**
 * Tarjeta de ROM en la cuadrícula.
 *
 * Cristal: superficie semitransparente, borde blanco tenue y sombra suave
 * sobre el degradado de fondo. Al pulsar se eleva, el borde se ilumina y
 * la portada hace un zoom interno contenido por sus esquinas.
 *
 * Dos formas para la misma tarjeta. [wide] la tiende con la portada a la
 * izquierda, que es lo que pide una pantalla corta: en horizontal una
 * portada de 3:4 repartida en cinco columnas deja el botón de jugar bajo
 * el pliegue. Sin [wide] es un cartel, portada arriba y datos abajo.
 *
 * La pulsación larga abre la edición (nombre y portada); la corta abre el
 * juego.
 *
 * @param index Posición en la lista: fija el retardo de la entrada
 *              escalonada, acotado para que una biblioteca larga no espere
 *              medio segundo en verse.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GameCard(
    item            : ROMItem,
    index           : Int,
    wide            : Boolean,
    onPlay          : () -> Unit,
    onToggleFavorite: () -> Unit,
    onEdit          : () -> Unit
) {
    val touch = rememberCardTouchState()
    val isHack = item.isHackRom
    val entranceDelay = index.coerceAtMost(8) * 40

    // Entrada escalonada: al cargar o cambiar de pestaña las tarjetas suben y
    // se dibujan en cascada, primero las de arriba.
    AnimatedVisibility(
        visible = true,
        enter = fadeIn(animationSpec = tween(260, delayMillis = entranceDelay)) +
                slideInVertically(
                    animationSpec = tween(260, delayMillis = entranceDelay, easing = FastOutSlowInEasing),
                    initialOffsetY = { fullHeight -> fullHeight / 4 }
                ) +
                scaleIn(initialScale = 0.94f)
    ) {
        Surface(
            modifier = Modifier
                .graphicsLayer {
                    scaleX = touch.scale
                    scaleY = touch.scale
                    translationY = touch.lift.toPx()
                }
                .combinedClickable(
                    interactionSource = touch.interactionSource,
                    indication = null,
                    onClick = onPlay,
                    onLongClick = onEdit
                )
                .testTag("rom_card_${item.id.take(15)}"),
            // M3 nombra este parámetro `color`, no `containerColor`: con `color`
            // explícito no se aplica tinte por elevación, así que el cristal
            // queda con el alpha justo en cualquier tema.
            color = SteamSurface2.copy(alpha = 0.88f),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = touch.borderAlpha)),
            shadowElevation = touch.elevation
        ) {
            if (wide) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CardCover(
                        item      = item,
                        isHack    = isHack,
                        coverZoom = touch.coverZoom,
                        modifier  = Modifier.width(76.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    CardInfo(
                        item             = item,
                        isHack           = isHack,
                        onPlay           = onPlay,
                        onToggleFavorite = onToggleFavorite,
                        modifier         = Modifier.weight(1f)
                    )
                }
            } else {
                Column {
                    CardCover(
                        item      = item,
                        isHack    = isHack,
                        coverZoom = touch.coverZoom,
                        modifier  = Modifier.fillMaxWidth()
                    )
                    CardInfo(
                        item             = item,
                        isHack           = isHack,
                        onPlay           = onPlay,
                        onToggleFavorite = onToggleFavorite
                    )
                }
            }
        }
    }
}

/**
 * Portada de una tarjeta, en 3:4 como una caja real de GBA.
 *
 * La consola de reserva va debajo y la imagen encima: si la portada no
 * llega (hack sin nombre en No-Intro, sin red, descarga en curso) se ve el
 * icono de siempre, que ya era el diseño de antes.
 *
 * @param coverZoom Escala interna de la imagen al pulsar la tarjeta. El
 *                  `clip` de las esquinas la mantiene dentro de la caja.
 */
@Composable
private fun CardCover(
    item      : ROMItem,
    isHack    : Boolean,
    coverZoom : Float,
    modifier  : Modifier = Modifier
) {
    Box(
        modifier = modifier
            .aspectRatio(3f / 4f)
            .clip(RoundedCornerShape(14.dp))
            .background(
                Brush.linearGradient(if (isHack) GradientGameIconHack else GradientGameIconNormal)
            )
    ) {
        Icon(
            imageVector = GbaConsole,
            contentDescription = null,
            tint = if (isHack) SteamPink.copy(alpha = 0.9f) else SteamCyan.copy(alpha = 0.7f),
            modifier = Modifier
                .align(Alignment.Center)
                .size(width = 34.dp, height = 46.dp)
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = coverZoom
                    scaleY = coverZoom
                }
        ) {
            // contentDescription nulo a propósito: la portada es decoración
            // porque el nombre del juego ya está en el texto de al lado;
            // anunciarla duplicaría el anuncio para TalkBack.
            //
            // La portada elegida por el usuario va primero y la automática
            // del CDN es el segundo turno.
            AsyncImage(
                model = item.customCoverPath ?: CoverArt.boxArtUrl(
                    item.fileName,
                    ConsoleKind.detect(item.fileName, null)
                ),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        // Velo de luz superior: el toque de cristal de la carátula.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.White.copy(alpha = 0.10f), Color.Transparent),
                        endY = 140f
                    )
                )
        )
        if (isHack) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    // `padding` y no `margin`: aquí sólo hace falta separar
                    // la píldora de la esquina de la portada.
                    .padding(start = 8.dp, top = 8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(SteamPinkSoft)
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            ) {
                Text(
                    "HACKROM",
                    style = MaterialTheme.typography.labelSmall,
                    color = SteamPink,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }
    }
}

/** Título, metadatos en píldoras y acciones de una tarjeta. */
@Composable
private fun CardInfo(
    item            : ROMItem,
    isHack          : Boolean,
    onPlay          : () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier        : Modifier = Modifier
) {
    Column(modifier = modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(
            text = item.displayTitle,
            style = MaterialTheme.typography.titleSmall,
            color = SteamTextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Metadatos en píldoras semitransparentes: el código del juego y
            // su peso acompañan sin competir con el título.
            GlassChip(
                text = if (isHack) "HACK" else item.gameCode,
                tint = if (isHack) SteamPink else SteamCyan
            )
            GlassChip(text = item.formattedSize, tint = SteamTextSecondary)
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FavoriteButton(favorite = item.isFavorite, onClick = onToggleFavorite)
            PlayButton(onPlay = onPlay)
        }
    }
}

/** Píldora de metadatos: fondo y borde blancos muy tenues. */
@Composable
private fun GlassChip(text: String, tint: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(99.dp))
            .background(Color.White.copy(alpha = 0.07f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(99.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// Corazón de favorito

/**
 * Corazón con rebote elástico y estallido de anillo.
 *
 * Al marcar: el corazón crece de golpe y un resorte lo devuelve, mientras
 * un anillo rosa se abre y se apaga. Al desmarcar no hay fiesta: vuelve al
 * contorno sin más, que es la mitad tranquila del gesto.
 */
@Composable
private fun FavoriteButton(
    favorite: Boolean,
    onClick : () -> Unit
) {
    val pop = remember { Animatable(1f) }
    val ring = remember { Animatable(1.45f) }

    LaunchedEffect(favorite) {
        if (favorite) {
            pop.snapTo(1.55f)
            pop.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = 0.45f,
                    stiffness = Spring.StiffnessMedium
                )
            )
            ring.snapTo(0.55f)
            ring.animateTo(
                targetValue = 1.45f,
                animationSpec = tween(420, easing = FastOutSlowInEasing)
            )
        }
    }

    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(5.dp),
        contentAlignment = Alignment.Center
    ) {
        // Anillo en expansión: sólo mientras la animación está viva.
        if (ring.value < 1.45f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = ring.value
                        scaleY = ring.value
                        alpha = ((1.45f - ring.value) / 0.9f) * 0.55f
                    }
                    .border(1.5.dp, SteamPink, CircleShape)
            )
        }
        Icon(
            imageVector = if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
            contentDescription = "Favorito",
            tint = if (favorite) SteamPink else SteamTextMuted,
            modifier = Modifier
                .size(20.dp)
                .graphicsLayer {
                    scaleX = pop.value
                    scaleY = pop.value
                }
        )
    }
}

// Botón de jugar

/**
 * Botón de jugar de la tarjeta.
 *
 * Degradado de acento y compresión al pulsar (escala 0.92 con un
 * resorte muy rígido, para que se sienta táctil). Mientras se mantiene
 * pulsado, un resplandor blanco recorre el botón: llamativo al tacto y
 * en reposo no gasta fotogramas en brillo.
 */
@Composable
private fun RowScope.PlayButton(onPlay: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(
            dampingRatio = 0.5f,
            stiffness = Spring.StiffnessHigh
        ),
        label = "playScale"
    )

    Box(
        modifier = Modifier
            .weight(1f)
            .height(34.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(10.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(
                        lerp(SteamCyan, Color.White, 0.22f),
                        SteamCyan,
                        lerp(SteamCyan, Color.Black, 0.12f)
                    )
                )
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onPlay
            )
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        // Resplandor deslizante: existe sólo mientras se mantiene pulsado.
        if (pressed) {
            val sheenTransition = rememberInfiniteTransition(label = "playSheen")
            val sheen by sheenTransition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    tween(900, easing = LinearEasing),
                    RepeatMode.Restart
                ),
                label = "sheenOffset"
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0f),
                                Color.White.copy(alpha = 0.40f),
                                Color.White.copy(alpha = 0f)
                            ),
                            start = Offset(sheen - 1f, 0f),
                            end = Offset(sheen, 0f)
                        )
                    )
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = "Jugar",
                tint = SteamOnAccent,
                modifier = Modifier.size(15.dp)
            )
            Spacer(modifier = Modifier.width(3.dp))
            Text(
                "Jugar",
                style = MaterialTheme.typography.labelMedium,
                color = SteamOnAccent,
                fontWeight = FontWeight.ExtraBold
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

            // Botón principal, con el mismo gradiente de acento que la
            // cabecera para que la acción se reconozca en cualquier sitio.
            SteamGradientButton(
                onClick = onLoadRom,
                modifier = Modifier.testTag("btn_empty_load_rom")
            ) {
                Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Cargar ROM (.gba / .zip)",
                    style      = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color      = SteamOnAccent
                )
            }
        }
    }
}
