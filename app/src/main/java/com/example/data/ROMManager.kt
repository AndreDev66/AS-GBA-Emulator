package com.example.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import com.example.core.GBAHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Una ROM de la biblioteca, tal y como se muestra en la lista. */
data class ROMItem(
    val id: String,
    val title: String,
    val gameCode: String,
    val uriString: String,
    val fileName: String,
    val sizeBytes: Long,
    val isHackRom: Boolean,
    val hackRomName: String?,
    val localPath: String? = null,
    val lastPlayedTimestamp: Long = 0L,
    val isFavorite: Boolean = false
) {
    /**
     * Nombre con el que se muestra el juego en la biblioteca.
     *
     * El cartucho sólo guarda doce caracteres del título, así que "Pokémon
     * Emerald" llega como "POKEMON EMER". El nombre del archivo es lo único que
     * separa una ROM de un hack del mismo juego base.
     */
    val displayTitle: String
        get() {
            // El orden importa: primero el archivo, luego el nombre del hack y al
            // final el título del cartucho, que viene truncado.
            val fromFile = fileName.substringBeforeLast(".").trim()
            return fromFile.ifBlank { hackRomName?.trim().orEmpty() }
                .ifBlank { title.trim() }
                .ifBlank { "Juego GBA" }
        }

    val formattedSize: String
        get() {
            val mb = sizeBytes.toDouble() / (1024.0 * 1024.0)
            return String.format("%.1f MB", mb)
        }

    /** Nombre del archivo sin ruta ni extensión, con los guiones bajos como espacios. */
    val fileTitle: String
        get() {
            val base = fileName.substringAfterLast('/').substringAfterLast('\\').trim()
            val extension = ROM_EXTENSIONS.firstOrNull { base.endsWith(it, ignoreCase = true) }
            val withoutExtension = if (extension != null) base.dropLast(extension.length) else base
            return withoutExtension.replace('_', ' ').trim().ifBlank { "Juego GBA" }
        }
}

/**
 * Resultado de recorrer una carpeta de ROM: [found] lo que se encontró, [imported]
 * lo que se copió a la biblioteca, [skipped] lo que ya estaba y [failed] lo que no
 * era una ROM legible.
 */
data class FolderScanResult(
    val found: Int,
    val imported: Int,
    val skipped: Int,
    val failed: Int
)

private val ROM_EXTENSIONS = listOf(".gba", ".zip", ".7z", ".rar", ".gb", ".gbc")

/**
 * ¿El nombre apunta a una ROM o a un archivo comprimido?
 *
 * El selector de Android ofrece cualquier archivo si se le pasa el comodín
 * universal de MIME, y el tamaño no ayuda: un `.apk` o un `.mp4` pasan el
 * mínimo de cabecera igual que un `.gba`. Sin esta comprobación la app
 * importaba su propio APK y el emulador se comía un `.dex`.
 */
internal fun hasRomExtension(fileName: String): Boolean {
    val base = fileName.substringAfterLast('/').substringAfterLast('\\').trim()
    return ROM_EXTENSIONS.any { base.endsWith(it, ignoreCase = true) }
}

/**
 * Biblioteca de ROM y de partidas.
 *
 * Trabaja sobre SAF: al importar se guarda una copia en el almacenamiento privado
 * para no depender de la concesión de URI, y las ROM grandes se leen con
 * `DirectByteBuffer` para no copiarlas dos veces.
 */
class ROMManager(private val context: Context) {
    companion object {
        private const val TAG = "ROMManager"
        const val MAX_SUPPORTED_ROM_SIZE = 64 * 1024 * 1024 // 64 MB
        private const val PREFS_NAME = "as_gba_rom_library"
        private const val KEY_ROMS = "imported_roms"
        private const val ROMS_DIR = "roms"
        private const val BATTERIES_DIR = "batteries"
private const val BATTERY_TEMP_SUFFIX = ".tmp"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Copia interna de cada ROM.
     *
     * El permiso de un URI sólo vive mientras el proveedor lo mantenga; en cuanto
     * lo revoca, la ROM deja de leerse. Al importar se guarda una copia aquí para
     * que la partida no dependa de esa concesión.
     */
    private val romsDir = File(context.filesDir, ROMS_DIR).apply { mkdirs() }

/**
     * RAM de guardado de cada cartucho, un archivo por juego.
     *
     * El Check de Pokémon o la libreta de un RPG viven en la SRAM, el Flash o la
     * EEPROM del cartucho, no en el estado de la máquina. Sin volcar esa memoria
     * al salir se pierde todo lo jugado desde la última sesión.
     */
    private val batteriesDir = File(context.filesDir, BATTERIES_DIR).apply { mkdirs() }

    private val _romList = MutableStateFlow<List<ROMItem>>(emptyList())
    val romList = _romList.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    init {
        loadPersistedLibrary()
    }

    private fun loadPersistedLibrary() {
        val jsonStr = prefs.getString(KEY_ROMS, null) ?: return
        try {
            val arr = JSONArray(jsonStr)
            val list = mutableListOf<ROMItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    ROMItem(
                        id = obj.getString("id"),
                        title = obj.getString("title"),
                        gameCode = obj.getString("gameCode"),
                        uriString = obj.getString("uriString"),
                        fileName = obj.getString("fileName"),
                        sizeBytes = obj.getLong("sizeBytes"),
                        isHackRom = obj.getBoolean("isHackRom"),
                        hackRomName = if (obj.has("hackRomName") && !obj.isNull("hackRomName")) obj.getString("hackRomName") else null,
                        localPath = if (obj.has("localPath") && !obj.isNull("localPath")) obj.getString("localPath") else null,
                        lastPlayedTimestamp = obj.optLong("lastPlayedTimestamp", 0L),
                        isFavorite = obj.optBoolean("isFavorite", false)
                    )
                )
            }
            _romList.value = list
        } catch (e: Exception) {
            Log.e(TAG, "Error loading persisted library: ${e.message}")
        }
    }

    private fun savePersistedLibrary() {
        try {
            val arr = JSONArray()
            for (item in _romList.value) {
                val obj = JSONObject()
                obj.put("id", item.id)
                obj.put("title", item.title)
                obj.put("gameCode", item.gameCode)
                obj.put("uriString", item.uriString)
                obj.put("fileName", item.fileName)
                obj.put("sizeBytes", item.sizeBytes)
                obj.put("isHackRom", item.isHackRom)
                obj.put("hackRomName", item.hackRomName)
                obj.put("localPath", item.localPath)
                obj.put("lastPlayedTimestamp", item.lastPlayedTimestamp)
                obj.put("isFavorite", item.isFavorite)
                arr.put(obj)
            }
            prefs.edit().putString(KEY_ROMS, arr.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving persisted library: ${e.message}")
        }
    }

    /** Importa una ROM por SAF y la copia al almacenamiento privado de la app. */
    suspend fun importRomFromUri(uri: Uri): Result<ROMItem> = withContext(Dispatchers.IO) {
        _isLoading.value = true
        try {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo persistir el permiso del URI: ${e.message}")
            }

            val fileName = queryFileName(uri) ?: "Juego_GBA.gba"
            Log.i(TAG, "Importando ROM: $uri ($fileName)")

            if (!hasRomExtension(fileName)) {
                Log.w(TAG, "Se rechaza $fileName: no es una ROM ni un archivo comprimido")
                return@withContext Result.failure(IOException("\"$fileName\" no es una ROM de GBA"))
            }

            val bytes = extractRomBytes(uri, fileName)
                ?: return@withContext Result.failure(IOException("No se pudo leer el archivo seleccionado"))
            if (bytes.size < 0xC0) {
                return@withContext Result.failure(IOException("El archivo no es una ROM de GBA válida"))
            }

            val header = GBAHeader.parse(bytes)
            val localPath = storeLocalCopy(uri.toString(), bytes)

            val newItem = ROMItem(
                id = uri.toString(),
                title = header?.title ?: fileName.substringBeforeLast("."),
                gameCode = header?.gameCode ?: "GBA",
                uriString = uri.toString(),
                fileName = fileName,
                sizeBytes = bytes.size.toLong(),
                isHackRom = header?.isHackRom == true || bytes.size > 16 * 1024 * 1024,
                hackRomName = header?.hackRomName,
                localPath = localPath,
                lastPlayedTimestamp = System.currentTimeMillis()
            )

            val updated = _romList.value.filter { it.id != newItem.id }.toMutableList()
            updated.add(0, newItem)
            _romList.value = updated
            savePersistedLibrary()

            _isLoading.value = false
            Result.success(newItem)
        } catch (e: Exception) {
            Log.e(TAG, "Fallo al importar la ROM: ${e.message}", e)
            _isLoading.value = false
            Result.failure(e)
        }
    }

/**
     * Bytes de la ROM, listos para el núcleo.
     *
     * Se lee la copia interna; si no hay, se intenta el URI original y se guarda
     * copia para la próxima. Un fallo devuelve `null` y nunca un cartucho vacío,
     * para que la interfaz pueda avisar en vez de arrancar un juego inexistente.
     */
    suspend fun loadRomBytes(item: ROMItem): ByteArray? = withContext(Dispatchers.IO) {
        val local = item.localPath?.let { File(it) }
        if (local != null && local.exists() && local.length() > 0) {
            return@withContext try {
                local.readBytes()
            } catch (e: Exception) {
                Log.e(TAG, "No se pudo leer la copia interna: ${e.message}")
                null
            }
        }

        val bytes = extractRomBytes(Uri.parse(item.uriString), item.fileName)
        if (bytes != null) {
            val path = storeLocalCopy(item.id, bytes)
            if (path != null) {
                _romList.value = _romList.value.map {
                    if (it.id == item.id) it.copy(localPath = path) else it
                }
                savePersistedLibrary()
            }
        }
        bytes
    }

    /** Copia la ROM al almacenamiento privado y devuelve la ruta de la copia. */
    private fun storeLocalCopy(id: String, bytes: ByteArray): String? = try {
        val target = File(romsDir, "${id.hashCode().toUInt().toString(16)}.gba")
        target.writeBytes(bytes)
        target.absolutePath
    } catch (e: Exception) {
        Log.e(TAG, "No se pudo guardar la copia interna: ${e.message}")
        null
    }

    /** Lee el URI y descomprime si es un ZIP con una entrada `.gba` o `.bin`. */
    private fun extractRomBytes(uri: Uri, fileName: String): ByteArray? {
        val raw = try {
            context.contentResolver.openInputStream(uri)?.use { readAll(it) }
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo abrir la ROM: ${e.message}")
            null
        } ?: return null

        if (!fileName.endsWith(".zip", ignoreCase = true)) return raw

        return try {
            ZipInputStream(raw.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory &&
                        (entry.name.endsWith(".gba", ignoreCase = true) ||
                            entry.name.endsWith(".bin", ignoreCase = true))
                    ) {
                        return readAll(zip)
                    }
                    entry = zip.nextEntry
                }
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo descomprimir la ROM: ${e.message}")
            null
        }
    }

    private fun readAll(input: java.io.InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var read: Int
        while (input.read(buffer).also { read = it } != -1) {
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    fun removeRom(romId: String) {
        _romList.value = _romList.value.filter { it.id != romId }
        savePersistedLibrary()
    }

    fun toggleFavorite(romId: String) {
        _romList.value = _romList.value.map {
            if (it.id == romId) it.copy(isFavorite = !it.isFavorite) else it
        }
        savePersistedLibrary()
    }

    /**
     * Vuelca a disco la RAM de cartucho del juego.
     *
     * `true` sólo si el archivo ha quedado escrito: el núcleo mantiene la memoria
     * marcada como modificada hasta que se le confirma, y un `false` provoca un
     * reintento en vez de perder la partida.
     */
    fun saveBattery(romId: String, data: ByteArray): Boolean {
        val target = batteryFile(romId)
        val temp = File(target.parentFile, "${target.name}.${data.size}$BATTERY_TEMP_SUFFIX")
        // Un temporal a medio escribir no sirve; uno completo sí, y es lo único
        // que puede recuperar la carga si el proceso muere antes del renombrado.
        var written = false
        return try {
            FileOutputStream(temp).use { out ->
                out.write(data)
                out.flush()
                out.fd.sync()
            }
            written = true
            // `rename` sustituye el destino de forma atómica: no hay ningún
            // instante sin archivo. Borrar antes abría ese hueco, y si el
            // proceso moría guardando, la partida se perdía entera.
            if (temp.renameTo(target)) {
                // El nombre antiguo era común a varios juegos: dejarlo en
                // disco haría que una partida acabara reasignada al juego siguiente.
                legacyBatteryFile(romId).takeIf { it != target && it.exists() }?.delete()
                discardBatteryTemps(target, keep = null)
                true
            } else if (!target.delete() || !temp.renameTo(target)) {
                // Sólo si `rename` falla se recurre a borrar antes: peor que
                // renombrar, pero mejor que quedarse sin escribir. El temporal se
                // deja a propósito, porque [loadBattery] lo recupera como partida.
                Log.e(TAG, "No se pudo renombrar la partida de $romId")
                false
            } else {
                true
            }
        } catch (e: Exception) {
            if (!written) temp.delete()
            Log.e(TAG, "No se pudo guardar la partida de $romId: ${e.message}")
            false
        }
    }

    /** RAM de cartucho guardada anteriormente para ese juego, o `null` si no hay. */
    fun loadBattery(romId: String): ByteArray? {
        var source = batteryFile(romId)
        if (!source.exists()) {
            // Temporal completo: el proceso murió entre escribir los datos y
            // renombrarlos, así que la partida sigue entera ahí dentro.
            recoverFromBatteryTemp(source)?.let { recovered ->
                Log.w(TAG, "Partida de $romId recuperada de un guardado interrumpido (${recovered.size} bytes)")
                return recovered
            }
            // Partidas de antes de que el nombre incluyera el resumen: se recuperan
            // y se archivan con el nombre correcto.
            val legacy = legacyBatteryFile(romId)
            if (!legacy.exists()) return null
            val migrated = try {
                FileInputStream(legacy).use { readAll(it) }
            } catch (e: Exception) {
                Log.e(TAG, "No se pudo migrar la partida de $romId: ${e.message}")
                return null
            }
            if (saveBattery(romId, migrated) && legacy.delete()) {
                Log.i(TAG, "Partida de $romId migrada al nombre ${source.name}")
            }
            source = batteryFile(romId)
            if (!source.exists()) return migrated
        }
        return try {
            FileInputStream(source).use { stream ->
                readAll(stream).also {
                    Log.i(TAG, "Partida de $romId recuperada (${it.size} bytes)")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo leer la partida de $romId: ${e.message}")
            null
        }
    }

    private fun batteryFile(romId: String): File = File(batteriesDir, "${romStorageKey(romId)}.sav")

    /** Temporales a medio escribir. Cada uno lleva su tamaño en el nombre. */
    private fun batteryTemps(target: File): List<File> {
        val dir = target.parentFile ?: return emptyList()
        return dir.listFiles { file ->
            file.isFile &&
                file.name.startsWith("${target.name}.") &&
                file.name.endsWith(BATTERY_TEMP_SUFFIX)
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    /**
     * Partida de un temporal, sólo si el tamaño real coincide con el que declara
     * su nombre.
     *
     * Un guardado a medias se adoptaría como partida buena y el juego leería
     * basura. Peor que no recuperar nada.
     */
    private fun recoverFromBatteryTemp(target: File): ByteArray? {
        for (temp in batteryTemps(target)) {
            val declared = temp.name
                .removePrefix("${target.name}.")
                .removeSuffix(BATTERY_TEMP_SUFFIX)
                .toIntOrNull()
            if (declared == null || declared <= 0 || temp.length() != declared.toLong()) {
                Log.w(TAG, "Descartando temporal truncado ${temp.name} (${temp.length()} bytes)")
                temp.delete()
                continue
            }
            val data = try {
                FileInputStream(temp).use { readAll(it) }
            } catch (e: Exception) {
                Log.e(TAG, "No se pudo leer el temporal ${temp.name}: ${e.message}")
                null
            }
            if (data == null || data.size != declared) continue
            val restored = if (temp.renameTo(target)) {
                true
            } else {
                target.writeBytes(data)
                temp.delete()
            }
            if (restored) return data
        }
        return null
    }

    /** Limpia temporales que ya no sirven, sin tocar el que se está recuperando. */
    private fun discardBatteryTemps(target: File, keep: File?) {
        batteryTemps(target).forEach { if (it != keep) it.delete() }
    }

    /** Nombre anterior, truncado: colisionaba entre juegos con el mismo prefijo. */
    private fun legacyBatteryFile(romId: String): File = File(
        batteriesDir,
        "${romId.replace("[^a-zA-Z0-9_-]".toRegex(), "_").take(48)}.sav"
    )

    /**
     * Importa de golpe todas las ROM de una carpeta.
     *
     * Entra en subcarpetas porque nadie guarda los juegos en una sola; el límite
     * de profundidad corta cualquier ciclo de enlaces simbólicos. Lo ya
     * importado se salta por URI, así que reescanear no duplica nada.
     */
    suspend fun importFolder(
        treeUri: Uri,
        maxDepth: Int = 4
    ): Result<FolderScanResult> = withContext(Dispatchers.IO) {
        _isLoading.value = true
        try {
            try {
                // `OpenDocumentTree` concede permiso sobre todo lo que hay dentro;
                // sin persistirlo, el escaneo funciona una vez y el sistema lo revoca.
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo persistir el permiso de la carpeta: ${e.message}")
            }

            val candidates = collectRomUris(treeUri, maxDepth)
            if (candidates.isEmpty()) {
                _isLoading.value = false
                return@withContext Result.success(FolderScanResult(found = 0, imported = 0, skipped = 0, failed = 0))
            }

            val known = _romList.value.map { it.uriString }.toMutableSet()
            var imported = 0
            var skipped = 0
            var failed = 0

            for ((uri, fileName) in candidates) {
                if (uri.toString() in known) {
                    skipped++
                    continue
                }
                val result = importSingleRom(uri, fileName)
                result.onSuccess { imported++ }.onFailure {
                    Log.w(TAG, "Se omite $fileName: ${it.message}")
                    failed++
                }
            }

            // Una sola escritura al final: serializar la biblioteca entera
            // por cada ROM es trabajo tirado.
            savePersistedLibrary()
            _isLoading.value = false
            Result.success(
                FolderScanResult(
                    found = candidates.size,
                    imported = imported,
                    skipped = skipped,
                    failed = failed
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Fallo al importar la carpeta: ${e.message}", e)
            _isLoading.value = false
            Result.failure(e)
        }
    }

    /**
     * Nombre real de la carpeta elegida, para mostrarlo en ajustes.
     *
     * La URI es `content://…/tree/primary%3ADownload%2FX`, y recortarla a mano
     * deja `primary%3ADownload%2FX`, que no es un nombre de carpeta. Se pregunta
     * al proveedor y, si no responde, se recurre al recorte.
     */
    suspend fun folderDisplayName(treeUri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            context.contentResolver.query(
                docUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                val idx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo leer el nombre de la carpeta: ${e.message}")
            null
        }
    }

    /**
     * Recorre la carpeta y devuelve los archivos con extensión de ROM.
     *
     * `DocumentsContract` es la única forma de listar un `content://`: no hay
     * `File.listFiles()`. El proveedor entrega un cursor de hijos y cada uno se
     * reconstruye con `buildChildDocumentsUriUsingTree`.
     */
    private fun collectRomUris(treeUri: Uri, maxDepth: Int): List<Pair<Uri, String>> {
        val found = mutableListOf<Pair<Uri, String>>()
        val docId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (e: Exception) {
            Log.e(TAG, "El URI no es una carpeta válida: ${e.message}")
            return found
        }
        walkTree(treeUri, docId, 0, maxDepth, found)
        return found
    }

    private fun walkTree(
        treeUri: Uri,
        parentDocId: String,
        depth: Int,
        maxDepth: Int,
        out: MutableList<Pair<Uri, String>>
    ) {
        if (depth > maxDepth) return
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        // Cada carpeta lleva su propio `buildDocumentUriUsingTree` sobre el mismo
        // árbol: las URIs hijas siguen valiendo con el permiso tomado en la raíz.
        val children = try {
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null, null, null
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val rows = mutableListOf<Triple<String, String, String>>()
                while (cursor.moveToNext()) {
                    if (idIndex < 0 || nameIndex < 0) break
                    val id = cursor.getString(idIndex) ?: continue
                    val name = cursor.getString(nameIndex) ?: continue
                    val mime = if (mimeIndex >= 0) cursor.getString(mimeIndex).orEmpty() else ""
                    rows.add(Triple(id, name, mime))
                }
                rows
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo listar la carpeta $parentDocId: ${e.message}")
            null
        } ?: return

        for ((id, name, mime) in children) {
            val isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR
            if (isDirectory) {
                walkTree(treeUri, id, depth + 1, maxDepth, out)
            } else if (hasRomExtension(name)) {
                out.add(
                    DocumentsContract.buildDocumentUriUsingTree(treeUri, id) to name
                )
            }
        }
    }

    /**
     * Importa una ROM cuyo nombre ya conozco.
     *
     * Separado de [importRomFromUri] porque la importación por carpeta ya trae
     * el nombre del cursor y no debe volver a preguntarlo al proveedor.
     */
    private suspend fun importSingleRom(uri: Uri, fileName: String): Result<ROMItem> {
        if (!hasRomExtension(fileName)) {
            return Result.failure(IOException("\"$fileName\" no es una ROM de GBA"))
        }
        val bytes = extractRomBytes(uri, fileName)
            ?: return Result.failure(IOException("No se pudo leer el archivo"))
        if (bytes.size < 0xC0) {
            return Result.failure(IOException("El archivo no es una ROM de GBA válida"))
        }
        val header = GBAHeader.parse(bytes)
        val localPath = storeLocalCopy(uri.toString(), bytes)
        val newItem = ROMItem(
            id = uri.toString(),
            title = header?.title ?: fileName.substringBeforeLast("."),
            gameCode = header?.gameCode ?: "GBA",
            uriString = uri.toString(),
            fileName = fileName,
            sizeBytes = bytes.size.toLong(),
            isHackRom = header?.isHackRom == true || bytes.size > 16 * 1024 * 1024,
            hackRomName = header?.hackRomName,
            localPath = localPath,
            lastPlayedTimestamp = System.currentTimeMillis()
        )
        val updated = _romList.value.filter { it.id != newItem.id }.toMutableList()
        updated.add(0, newItem)
        _romList.value = updated
        return Result.success(newItem)
    }

    private fun queryFileName(uri: Uri): String? {
        var name: String? = null
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    name = cursor.getString(nameIndex)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error querying file name: ${e.message}")
        }
        return name
    }
}

/**
 * Clave de archivo única por ROM, para la partida y las ranuras de estado.
 *
 * El identificador es la URI completa y dos juegos distintos comparten todo el
 * prefijo (`content://com.android.providers.downloads.documents/`), así que
 * truncarlo hacía que todos apuntaran al mismo archivo y jugar uno machacaba la
 * partida del otro. Aquí el prefijo legible se acompaña de un resumen del
 * identificador entero, que no colisiona.
 */
internal fun romStorageKey(romId: String): String {
    val readable = romId.replace("[^a-zA-Z0-9_-]".toRegex(), "_").trim('_').take(40)
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(romId.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(12)
    return "${readable}_$digest"
}
