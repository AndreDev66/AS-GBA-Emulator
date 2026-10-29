package com.example.core.cheats

/**
 * Un truco guardado: un nombre, las líneas de código y si está activo.
 *
 * Se guardan las líneas **crudas**, no las operaciones ya decodificadas. Es
 * deliberado: el decodificador mejora con el tiempo (más formatos, menos
 * falsos positivos) y, teniendo el texto, una mejora se aplica sola la próxima
 * vez que se abre la lista. El coste es recompilar al leer, instantáneo para
 * una lista de unos pocos códigos.
 */
data class CheatEntry(
    /** Identificador estable; la UI lo usa como clave de las casillas. */
    val id: String,
    val name: String,
    val enabled: Boolean,
    val lines: List<String>,
) {
    /** Texto de código tal cual se pegó, listo para [CheatParser]. */
    val source: String get() = lines.joinToString("\n")
}

/**
 * Convierte una lista de [CheatEntry] a texto plano y de vuelta, para meterla
 * en unas preferencias compartidas sin montar un `JSONArray` a mano.
 *
 * ## Formato
 *
 * Un truco por bloque; los bloques se separan con una línea en blanco:
 *
 * ```
 * 1
 * Vida infinita
 * 82003ABC 0064
 * 72001000 0002
 *
 * 0
 * Munición
 * 22004000 0100
 * ```
 *
 * La primera línea es la marca de activo (1/0), la segunda el nombre y el resto
 * líneas de código sin interpretar. Texto plano y no JSON porque esta lista es
 * cosa del usuario: cuando algo falla tiene que poder leerla y editarla a
 * mano, y porque así el formato se lee de un vistazo.
 *
 * Como el separador de bloques es una línea en blanco, [encode] las descarta de
 * dentro de los bloques; el pegado de listas de la wiki las trae entre códigos y
 * si se guardaran tal cual partirían el bloque al releer.
 *
 * ## Por qué es robusto al leer
 *
 * Un archivo de preferencias se puede corromper (edición manual, cierre a
 * medias, migración). [decode] nunca lanza: los bloques mal formados se saltan
 * y se devuelven los que sí se entienden. Perder un truco guardado molesta mucho
 * menos que no poder abrir la lista.
 */
object CheatStorageCodec {

    /** Separador de bloques: línea en blanco. */
    private const val BLOCK_SEPARATOR = "\n\n"

    private const val FLAG_ACTIVE = "1"
    private const val FLAG_INACTIVE = "0"

    /** Prefijo de los identificadores generados para trucos nuevos. */
    const val NEW_ID_PREFIX = "new"

    /** Prefijo de los identificadores reconstruidos al leer del almacenamiento. */
    private const val SAVED_ID_PREFIX = "saved"

    fun encode(entries: List<CheatEntry>): String =
        entries.joinToString(BLOCK_SEPARATOR) { entry ->
            buildString {
                append(if (entry.enabled) FLAG_ACTIVE else FLAG_INACTIVE)
                append('\n')
                append(entry.name.replace('\n', ' '))
                // Las líneas en blanco se descartan: el separador de bloques es
                // una línea en blanco, así que una dentro de un bloque lo
                // partiría al releer.
                for (line in entry.lines) {
                    if (line.isBlank()) continue
                    append('\n')
                    append(line.trim())
                }
            }
        }

    fun decode(text: String?): List<CheatEntry> {
        if (text.isNullOrBlank()) return emptyList()
        // `trim` recorta el separador final para que el último bloque no
        // produzca un bloque vacío espurio.
        val blocks = text.trim().split(BLOCK_SEPARATOR)
        val entries = mutableListOf<CheatEntry>()
        blocks.forEachIndexed { index, block ->
            val entry = decodeBlock(block, index)
            if (entry != null) entries += entry
        }
        return entries
    }

    /**
     * Lee un bloque. Devuelve `null` si le falta la marca o el nombre, que es
     * lo único que impide saber qué es; las líneas de código que falten las
     * tolera [CheatParser], que es quien decide si la lista sirve.
     */
    private fun decodeBlock(block: String, index: Int): CheatEntry? {
        val lines = block.lines()
        if (lines.size < 2) return null
        val enabled = when (lines[0].trim()) {
            FLAG_ACTIVE -> true
            FLAG_INACTIVE -> false
            else -> return null
        }
        val name = lines[1].trim()
        if (name.isEmpty()) return null
        return CheatEntry(
            // El índice da identidad estable al truco tal y como se guardó, de
            // forma que recargar la lista no mueva las casillas marcadas. El
            // prefijo es distinto del de los trucos nuevos para que [newId] no
            // los cuente al numerar.
            id = SAVED_ID_PREFIX + index,
            name = name,
            enabled = enabled,
            lines = lines.drop(2).map { it.trim() }.filter { it.isNotEmpty() },
        )
    }

    /**
     * Identificador para un truco recién creado, único dentro de [existing].
     *
     * Se numeran correlativamente en vez de usar un UUID para que el
     * almacenamiento siga siendo legible al abrirlo con un editor de texto.
     */
    fun newId(existing: List<CheatEntry>): String {
        val taken = existing.map { it.id }
            .filter { it.startsWith(NEW_ID_PREFIX) }
            .mapNotNull { it.removePrefix(NEW_ID_PREFIX).toIntOrNull() }
        val next = (taken.maxOrNull() ?: -1) + 1
        return NEW_ID_PREFIX + next
    }
}
