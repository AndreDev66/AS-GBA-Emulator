package com.example.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import com.example.core.GBAHeader
import com.example.core.GameBoyHeader
import com.example.core.NdsHeader
import com.example.core.emulation.ConsoleKind
import com.github.junrar.Archive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
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
    val isFavorite: Boolean = false,
    /**
     * Nombre que escribió el usuario para esta ROM, o `null` si no lo cambió.
     *
     * Es sólo presentación: el archivo, su ruta y su CRC no se tocan, así que
     * renombrar aquí no puede romper nada de lo que depende de la ROM real.
     */
    val displayName: String? = null,
    /**
     * Ruta de la carátula elegida por el usuario, dentro del almacenamiento
     * privado de la app, o `null` si no eligió ninguna.
     */
    val customCoverPath: String? = null
) {
    /**
     * Nombre con el que se muestra el juego en la biblioteca.
     *
     * Lo que el usuario escribió gana siempre: es su biblioteca y su criterio.
     * Sin nombre propio, el cartucho sólo guarda doce caracteres del título, así
     * que "Pokémon Emerald" llega como "POKEMON EMER", y el nombre del archivo es
     * lo único que separa una ROM de un hack del mismo juego base.
     */
    val displayTitle: String
        get() {
            displayName?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
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

// internal (y no private): CoverArt reutiliza esta lista para quitar la
// extensión antes de buscar la carátula, así que "es una ROM" se decide en
// un solo sitio y ambas lecturas del archivo no pueden divergir.
internal val ROM_EXTENSIONS =
    listOf(".gba", ".zip", ".7z", ".rar", ".gb", ".gbc", ".nds", ".dsi")

/**
 * Cadena opcional de un JSONObject que puede no estar.
 *
 * `optString` devuelve "" tanto para una clave ausente como para una vacía, y un
 * nombre en blanco es exactamente lo que significa "el usuario no puso nombre".
 * Un JSON `null` tampoco cuenta como valor.
 */
private fun JSONObject.optStringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

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
 * Extensiones que identifican una ROM dentro de un archivo comprimido.
 *
 * No coincide con [ROM_EXTENSIONS]: ahí sólo se lista cómo se llama el archivo
 * que el usuario elige (`.zip`, `.7z`, `.rar`), mientras que éstas son las que
 * puede llevar la entrada del cartucho dentro del comprimido. Incluye tanto las
 * de GBA (`.gba`, `.agb`, `.bin`, `.rom`) como las de Game Boy (`.gb`, `.gbc`) y
 * las de Nintendo DS (`.nds`, `.dsi`).
 */
private val ROM_ENTRY_EXTENSIONS =
    listOf(".gba", ".agb", ".bin", ".rom", ".gb", ".gbc", ".nds", ".dsi")

/**
 * ¿Esta entrada de un ZIP, 7z o RAR es la ROM, y no un readme o una carátula?
 *
 * Se compara sin distinguir mayúsculas porque los archivadores de Windows
 * escriben `JUEGO.GBA` y los de Linux `juego.gba`: una comparación sensible a
 * mayúsculas dejaría sin importar la mitad de las ROM. El nombre se reduce
 * además a su último segmento para que una carpeta llamada como una ROM
 * (`carpeta.gba/`) no pase por cartucho.
 */
internal fun isRomEntryName(entryName: String): Boolean {
    val base = entryName.substringAfterLast('/').substringAfterLast('\\')
    return ROM_ENTRY_EXTENSIONS.any { base.endsWith(it, ignoreCase = true) }
}

/**
 * Biblioteca de ROM y de partidas.
 *
 * Trabaja sobre SAF: al importar se guarda una copia en el almacenamiento privado
 * para no depender de la concesión de URI. Los comprimidos se extraen a disco
 * por bloques y las ROM de Nintendo DS se entregan al núcleo desde su archivo,
 * sin alojar cartuchos de cientos de MiB en el heap de Java.
 */
class ROMManager(private val context: Context) {
    companion object {
        private const val TAG = "ROMManager"
        const val MAX_SUPPORTED_ROM_SIZE = 512 * 1024 * 1024 // 512 MB
        private const val MAX_IN_MEMORY_ROM_SIZE = 64L * 1024 * 1024
        /**
         * Tope de la ROM ya sacada de un comprimido: 512 MiB.
         *
         * Antes eran 32 MiB, el cartucho más grande que la GBA pudo direccionar.
         * La Nintendo DS addressed hasta 256 MiB en los cartuchos originales y
         * muchísimos más en las flashcards pirateadas, de modo que con el tope
         * antiguo una ROM de DS legítima dentro de un ZIP se rechazaba por
         * grande. El límite protege el almacenamiento y acota entradas anómalas;
         * la extracción se hace por bloques y no consume el heap según el tamaño.
         */
        private const val MAX_EXTRACTED_ROM_SIZE = 512 * 1024 * 1024

        /** Cabecera de GBA completa: 0xC0 bytes bastan para identificarla. */
        private const val MIN_GBA_ROM_BYTES = 0xC0

        /**
         * Cartucho de Game Boy por debajo del mínimo: un banco son 32 KiB, que
         * es el tamaño más pequeño que el hardware —y el núcleo— aceptan.
         */
        private const val MIN_GAME_BOY_ROM_BYTES = 32 * 1024

        /**
         * Cartucho de Nintendo DS por debajo del mínimo: su cabecera son 4096
         * bytes y el ejecutable principal arranca detrás, así que menos que eso
         * no es un cartucho que el hardware pueda arrancar.
         */
        private const val MIN_NDS_ROM_BYTES = 0x2000

        private const val PREFS_NAME = "as_gba_rom_library"
        private const val KEY_ROMS = "imported_roms"
        private const val ROMS_DIR = "roms"
        private const val BATTERIES_DIR = "batteries"
        private const val COVERS_DIR = "covers"
        private const val BATTERY_TEMP_SUFFIX = ".tmp"

        /**
         * Extensión de las portadas propias.
         *
         * No se conserva la del archivo elegido: el proveedor de contenido no
         * siempre la dice, y una portada de 400 KB con extensión de Photoshop no
         * la carga ningún decodificador de imagen.
         */
        private const val COVER_SUFFIX = ".png"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val contentResolver = context.contentResolver

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

    /**
     * Carátulas elegidas por el usuario, una por juego.
     *
     * Se copian aquí dentro y no se guarda el Uri del selector: un permiso de
     * lectura sobre un archivo ajeno caduca, y la portada de un juego es algo que
     * el usuario eligió para verlo siempre. Así elStorage sobrevive a la sesión y
     * no hace falta ningún permiso de medios.
     */
    private val coversDir = File(context.filesDir, COVERS_DIR).apply { mkdirs() }

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
                        isFavorite = obj.optBoolean("isFavorite", false),
                        // optString con null explícito: en un JSON anterior a esta
                        // versión las claves no existen, y getString sobre una clave
                        // ausente lanza en vez de devolver nada.
                        displayName = obj.optStringOrNull("displayName"),
                        customCoverPath = obj.optStringOrNull("customCoverPath")
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
                obj.put("displayName", item.displayName)
                obj.put("customCoverPath", item.customCoverPath)
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
                return@withContext Result.failure(IOException("\"$fileName\" no es una ROM de GBA, Game Boy ni Nintendo DS"))
            }

            val extractedFile = extractRomFile(uri, fileName)
                ?: return@withContext Result.failure(IOException("No se pudo leer el archivo seleccionado"))

            val newItem = try {
                buildRomItem(uri, fileName, extractedFile)
            } finally {
                extractedFile.delete()
            } ?: return@withContext Result.failure(
                IOException("El archivo no es una ROM de ${ConsoleKind.detect(fileName, null).shortName} válida")
            )

            val updated = _romList.value.filter { it.id != newItem.id }.toMutableList()
            updated.add(0, newItem)
            _romList.value = updated
            savePersistedLibrary()

            Result.success(newItem)
        } catch (e: Exception) {
            Log.e(TAG, "Fallo al importar la ROM: ${e.message}", e)
            Result.failure(e)
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * Registro de biblioteca a partir de los bytes ya leídos, o `null` si no
     * forman un cartucho de ninguna de las consolas conocidas.
     *
     * La consola se decide aquí y es la que gobierna el resto: cada una tiene su
     * cabecera, su código de juego y su mínimo de tamaño (0xC0 para la cabecera
     * de GBA, 32 KiB —un banco entero— para un cartucho de Game Boy, 8 KiB para
     * el de Nintendo DS, que es también lo que exigen los núcleos al cargarlos).
     */
    private fun buildRomItem(uri: Uri, fileName: String, file: File): ROMItem? {
        val romSize = file.length()
        if (romSize <= 0L || romSize > MAX_SUPPORTED_ROM_SIZE.toLong()) {
            Log.w(TAG, "Se rechaza $fileName: tamaño de ROM no admitido ($romSize bytes)")
            return null
        }
        val headerBytes = readRomHeader(file)
        val console = ConsoleKind.detect(fileName, headerBytes, romSize)
        val minimumSize = when (console) {
            ConsoleKind.GAME_BOY -> MIN_GAME_BOY_ROM_BYTES
            ConsoleKind.GAME_BOY_ADVANCE -> MIN_GBA_ROM_BYTES
            ConsoleKind.NINTENDO_DS -> MIN_NDS_ROM_BYTES
        }
        if (romSize < minimumSize) {
            Log.w(TAG, "Se rechaza $fileName: $romSize bytes no llegan al mínimo de ${console.shortName}")
            return null
        }

        val gbaHeader = if (console == ConsoleKind.GAME_BOY_ADVANCE) GBAHeader.parse(headerBytes) else null
        val gbHeader = if (console == ConsoleKind.GAME_BOY) GameBoyHeader.parse(headerBytes) else null
        val ndsHeader = if (console == ConsoleKind.NINTENDO_DS) NdsHeader.parse(headerBytes, romSize) else null
        val localPath = storeLocalCopy(uri.toString(), file)

        return ROMItem(
            id = uri.toString(),
            title = ndsHeader?.displayTitle
                ?: gbaHeader?.title
                ?: gbHeader?.title?.takeIf { it.isNotBlank() }
                ?: fileName.substringBeforeLast("."),
            // La Game Boy no trae código de juego en su cabecera: lo que se
            // guarda es la consola, que es lo que la biblioteca puede mostrar.
            gameCode = when {
                gbHeader != null -> if (gbHeader.supportsColor) "GBC" else "GB"
                // La Nintendo DS sí lo trae, y es de cuatro letras.
                ndsHeader != null -> ndsHeader.gameCode.takeIf { it.isNotBlank() } ?: "NDS"
                else -> gbaHeader?.gameCode ?: "GBA"
            },
            uriString = uri.toString(),
            fileName = fileName,
            sizeBytes = romSize,
            // El umbral de hackrom venía del tamaño de la GBA. Una ROM de DS
            // normal ya lo supera de sobra, así que aquí la condición es la
            // que declara la cabecera de GBA y nada más.
            isHackRom = gbaHeader?.isHackRom == true,
            hackRomName = gbaHeader?.hackRomName,
            localPath = localPath,
            lastPlayedTimestamp = System.currentTimeMillis()
        )
    }

/**
     * Bytes de la ROM, listos para el núcleo.
     *
     * Se lee la copia interna; si no hay, se intenta el URI original y se guarda
     * copia para la próxima. Un fallo devuelve `null` y nunca un cartucho vacío,
     * para que la interfaz pueda avisar en vez de arrancar un juego inexistente.
     */
    suspend fun loadRomFile(item: ROMItem): File? = withContext(Dispatchers.IO) {
        val local = item.localPath?.let { File(it) }
        if (local != null && local.exists() && local.length() > 0) {
            return@withContext local
        }

        val extractedFile = extractRomFile(Uri.parse(item.uriString), item.fileName) ?: return@withContext null
        val path = try {
            storeLocalCopy(item.id, extractedFile)
        } finally {
            extractedFile.delete()
        }
        if (path == null) return@withContext null
        _romList.value = _romList.value.map {
            if (it.id == item.id) it.copy(localPath = path) else it
        }
        savePersistedLibrary()
        File(path)
    }

    fun detectConsole(fileName: String, file: File): ConsoleKind =
        ConsoleKind.detect(fileName, readRomHeader(file), file.length())

    /**
     * API de compatibilidad para ROMs pequeñas. Los cartuchos grandes se cargan
     * desde archivo para no duplicar cientos de MiB en el heap de Java.
     */
    suspend fun loadRomBytes(item: ROMItem): ByteArray? = withContext(Dispatchers.IO) {
        val file = loadRomFile(item) ?: return@withContext null
        if (file.length() > MAX_IN_MEMORY_ROM_SIZE) {
            Log.e(TAG, "La ROM es demasiado grande para cargarse como ByteArray: ${file.length()} bytes")
            return@withContext null
        }
        try {
            file.readBytes()
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo leer la copia interna: ${e.message}")
            null
        }
    }

    /** Copia la ROM al almacenamiento privado y devuelve la ruta de la copia. */
    private fun storeLocalCopy(id: String, source: File): String? = try {
        val target = romFile(id)
        val temporary = File(romsDir, "${target.name}.tmp")
        FileInputStream(source).use { input ->
            FileOutputStream(temporary).use { output -> copyBounded(input, output) }
        }
        if (target.exists() && !target.delete()) {
            temporary.delete()
            throw IOException("No se pudo sustituir la copia anterior")
        }
        if (!temporary.renameTo(target)) {
            temporary.delete()
            throw IOException("No se pudo guardar la copia interna")
        }
        target.absolutePath
    } catch (e: Exception) {
        Log.e(TAG, "No se pudo guardar la copia interna: ${e.message}")
        null
    }

    private fun romFile(id: String): File =
        File(romsDir, "${id.hashCode().toUInt().toString(16)}.rom")

    /**
     * Lee el URI y, si es un comprimido, extrae la ROM a un archivo temporal.
     *
     * De cada comprimido se toma la primera entrada con apariencia de ROM
     * ([isRomEntryName]). Si el formato no se puede leer (cifrado sin
     * contraseña, cabecera corrupta, formatos que la biblioteca no implementa)
     * o no hay ninguna ROM dentro, devuelve `null`. El archivo comprimido y la
     * ROM extraída nunca se cargan completos en el heap de Java.
     */
    private fun extractRomFile(uri: Uri, fileName: String): File? {
        val output = File.createTempFile("rom-", ".tmp", romsDir)
        var keepOutput = false
        val archive = if (fileName.endsWith(".zip", true) ||
            fileName.endsWith(".7z", true) || fileName.endsWith(".rar", true)
        ) {
            File.createTempFile("archive-", ".tmp", context.cacheDir)
        } else {
            null
        }
        try {
            val input = contentResolver.openInputStream(uri)
                ?: throw IOException("El proveedor no pudo abrir el archivo")
            input.use { source ->
                if (archive == null) {
                    FileOutputStream(output).use { destination ->
                        copyBounded(source, destination, MAX_SUPPORTED_ROM_SIZE.toLong())
                    }
                } else {
                    FileOutputStream(archive).use { destination ->
                        copyBounded(source, destination, MAX_SUPPORTED_ROM_SIZE.toLong())
                    }
                }
            }
            if (archive != null) {
                val extracted = when {
                    fileName.endsWith(".zip", true) -> extractFromZip(archive, output)
                    fileName.endsWith(".7z", true) -> extractFromSevenZip(archive, output)
                    else -> extractFromRar(archive, output)
                }
                if (!extracted) return null
            }
            if (output.length() <= 0L) return null
            keepOutput = true
            return output
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo leer o descomprimir la ROM: ${e.message}", e)
            return null
        } finally {
            archive?.delete()
            if (!keepOutput) output.delete()
        }
    }

    /** Extrae la primera entrada ROM del ZIP al archivo temporal. */
    private fun extractFromZip(archive: File, output: File): Boolean = try {
        ZipInputStream(FileInputStream(archive)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && isRomEntryName(entry.name)) {
                    FileOutputStream(output).use { destination ->
                        copyBounded(zip, destination, MAX_EXTRACTED_ROM_SIZE.toLong())
                    }
                    return@use true
                }
                entry = zip.nextEntry
            }
            Log.w(TAG, "El ZIP no contiene ninguna ROM compatible")
            false
        }
    } catch (e: Exception) {
        Log.e(TAG, "No se pudo descomprimir el ZIP: ${e.message}")
        false
    }

    /**
     * Extrae la primera entrada ROM del 7z al archivo temporal.
     *
     * `SevenZFile` necesita una fuente seekable, por eso se le pasa el archivo
     * temporal del comprimido en vez de construir un `ByteArray` en memoria.
     */
    private fun extractFromSevenZip(archive: File, output: File): Boolean = try {
        SevenZFile.builder().setPath(archive.toPath()).get().use { sevenZ ->
            var entry = sevenZ.getNextEntry()
            while (entry != null) {
                val name = entry.name
                if (!entry.isDirectory && name != null && isRomEntryName(name)) {
                    sevenZ.getInputStream(entry).use { input ->
                        FileOutputStream(output).use { destination ->
                            copyBounded(input, destination, MAX_EXTRACTED_ROM_SIZE.toLong())
                        }
                    }
                    return@use true
                }
                entry = sevenZ.getNextEntry()
            }
            Log.w(TAG, "El 7z no contiene ninguna ROM compatible")
            false
        }
    } catch (e: Exception) {
        Log.e(TAG, "No se pudo descomprimir el 7z: ${e.message}")
        false
    }

    /**
     * Extrae la primera entrada ROM del RAR al archivo temporal.
     *
     * junrar sí extrae RAR5: la issue histórica que lo negaba
     * (junrar/junrar#23, "RAR5 is not supported") está cerrada, y la release
     * v8.0.0 retiró `UnsupportedRarV5Exception` porque la extracción RAR5 ya
     * estaba hecha ("RAR5 extraction landed"). Lo que junrar sí rechaza —
     * archivos cifrados sin contraseña o versiones muy antiguas— lanza
     * `RarException`, que aquí se convierte en `null` con log: nunca se
     * devuelven bytes rotos a la interfaz.
     */
    private fun extractFromRar(source: File, output: File): Boolean = try {
        Archive(source).use { archive ->
            var header = archive.nextFileHeader()
            while (header != null) {
                val name = header.fileName
                if (!header.isDirectory && name != null && isRomEntryName(name)) {
                    archive.getInputStream(header).use { input ->
                        FileOutputStream(output).use { destination ->
                            copyBounded(input, destination, MAX_EXTRACTED_ROM_SIZE.toLong())
                        }
                    }
                    return@use true
                }
                header = archive.nextFileHeader()
            }
            Log.w(TAG, "El RAR no contiene ninguna ROM compatible")
            false
        }
    } catch (e: Exception) {
        Log.e(TAG, "No se pudo descomprimir el RAR: ${e.message}")
        false
    }

    private fun copyBounded(
        input: InputStream,
        output: java.io.OutputStream,
        maximumBytes: Long = MAX_EXTRACTED_ROM_SIZE.toLong(),
    ) {
        val buffer = ByteArray(64 * 1024)
        var copied = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            copied += read
            if (copied > maximumBytes) throw IOException("La ROM supera el límite de tamaño admitido")
            output.write(buffer, 0, read)
        }
    }

    private fun readRomHeader(file: File): ByteArray {
        val header = ByteArray(minOf(file.length(), 0x1000L).toInt())
        FileInputStream(file).use { input ->
            var offset = 0
            while (offset < header.size) {
                val read = input.read(header, offset, header.size - offset)
                if (read < 0) break
                offset += read
            }
            return if (offset == header.size) header else header.copyOf(offset)
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
        // La portada elegida es un archivo que esta app creó para esta ROM: si no
        // se borra aquí, se queda en el almacenamiento sin dueño para siempre.
        _romList.value.firstOrNull { it.id == romId }?.customCoverPath
            ?.let(::File)
            ?.takeIf { it.exists() }
            ?.delete()
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
     * Fija el nombre con el que el usuario quiere ver esta ROM.
     *
     * Un nombre en blanco devuelve la ROM a su nombre de archivo: guardar "" como
     * nombre propio daría una fila sin título, que es justo lo que el usuario
     * quería quitar al renombrarla.
     */
    fun renameRom(romId: String, displayName: String) {
        val trimmed = displayName.trim()
        _romList.value = _romList.value.map {
            if (it.id == romId) it.copy(displayName = trimmed.ifBlank { null }) else it
        }
        savePersistedLibrary()
    }

    /**
     * Copia la imagen elegida como portada de esta ROM.
     *
     * El archivo se copia antes de tocar la biblioteca: si la copia falla (imagen
     * ilegible, almacenamiento lleno), la portada anterior sigue puesta en vez de
     * dejar la ficha apuntando a un archivo que no existe.
     */
    suspend fun setCustomCover(romId: String, source: Uri): Boolean =
        withContext(Dispatchers.IO) {
            val key = romStorageKey(romId)
            val target = File(coversDir, "$key$COVER_SUFFIX")
            val temporary = File(coversDir, "$key$COVER_SUFFIX.tmp")
            try {
                val previous = _romList.value.firstOrNull { it.id == romId }
                    ?.customCoverPath
                    ?.let(::File)
                    ?.takeIf { it.exists() }

                contentResolver.openInputStream(source)?.use { input ->
                    FileOutputStream(temporary).use { output -> input.copyTo(output) }
                } ?: return@withContext false

                // Se renombra sólo con la copia completa: a medio escribir, la
                // portada anterior quedaría sustituida por un archivo truncado.
                if (!temporary.renameTo(target)) {
                    temporary.delete()
                    return@withContext false
                }
                previous?.delete()
                _romList.value = _romList.value.map {
                    if (it.id == romId) it.copy(customCoverPath = target.absolutePath) else it
                }
                savePersistedLibrary()
                true
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo usar la portada elegida: ${e.message}")
                temporary.delete()
                false
            }
        }

    /** Devuelve la ROM a la portada automática del CDN. */
    fun clearCustomCover(romId: String) {
        _romList.value.firstOrNull { it.id == romId }?.customCoverPath
            ?.let(::File)
            ?.takeIf { it.exists() }
            ?.delete()
        _romList.value = _romList.value.map {
            if (it.id == romId) it.copy(customCoverPath = null) else it
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
            return Result.failure(IOException("\"$fileName\" no es una ROM de GBA, Game Boy ni Nintendo DS"))
        }
        val extractedFile = extractRomFile(uri, fileName)
            ?: return Result.failure(IOException("No se pudo leer el archivo"))
        val newItem = try {
            buildRomItem(uri, fileName, extractedFile)
        } finally {
            extractedFile.delete()
        }
            ?: return Result.failure(
                IOException("El archivo no es una ROM válida")
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
