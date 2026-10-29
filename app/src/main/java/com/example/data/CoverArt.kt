package com.example.data

import com.example.core.emulation.ConsoleKind
import java.net.URLEncoder

/**
 * Carátulas de la biblioteca desde libretro-thumbnails.
 *
 * Por qué esta fuente: libretro mantiene, por sistema, un repositorio de
 * imágenes nombrado con los nombres EXACTOS de No-Intro, que es la misma
 * convención con la que suele nombrarse una ROM. El CDN que lo sirve
 * (thumbnails.libretro.com) es el que usa RetroArch: estático, sin API ni
 * clave, y la caché de disco de Coil se ocupa de no volver a bajar una
 * carátula ya vista.
 *
 * Por qué puede fallar (y está bien): el nombre del archivo tiene que
 * coincidir carácter a carácter con el de la base No-Intro, incluidas las
 * mayúsculas; los hacks ("Elite Redux (2.65.3b).gba") casi nunca existen
 * allí. Cuando no hay imagen, la fila se queda con su icono de reserva:
 * no hay estado de error que mostrar.
 */
object CoverArt {

    private const val BASE_URL = "https://thumbnails.libretro.com"

    /**
     * Sistema dentro del CDN: un directorio por consola, con espacios.
     *
     * El directorio lo decide la consola, no el archivo. Con una sola constante
     * toda la biblioteca pedía carátulas de GBA, y las de Nintendo DS viven en
     * su propio directorio del mismo servicio: con el nombre correcto pero en la
     * carpeta equivocada no hay portada, sólo un 404.
     */
    private fun systemFor(console: ConsoleKind): String = when (console) {
        ConsoleKind.GAME_BOY -> "Nintendo - Game Boy"
        ConsoleKind.GAME_BOY_ADVANCE -> "Nintendo - Game Boy Advance"
        ConsoleKind.NINTENDO_DS -> "Nintendo - Nintendo DS"
    }

    private const val BOXARTS_DIR = "Named_Boxarts"

    /**
     * Caracteres que el nombre del thumbnail no puede llevar: es el set del
     * README de libretro-thumbnails —ampersand, asterisco, barra, dos puntos,
     * contrabarra, menor que, mayor que, interrogación, barra invertida,
     * barra vertical y comilla doble (11 en total)—, donde mandan
     * sustituirlos por '_'. Comprobado además contra el índice real del CDN:
     * ninguno de estos caracteres aparece en ninguno de los 5959 nombres de
     * la consola, mientras que # + ' ! ( ) [ ] ~ $ sí.
     *
     * Las dos barras no llegan nunca aquí (el trozo de ruta ya las ha
     * consumido antes), pero se dejan por si el troceado cambia.
     *
     * Ojo con escribir el set literal dentro de un comentario de bloque: la
     * combinación asterisco+barra cierra el comentario y el resto del archivo
     * se lee como código (ya le pasó a este fichero).
     */
    private const val FORBIDDEN = "&*/:`<>?\\|\""

    /**
     * URL de la carátula para un nombre de archivo de ROM, o null si no hay
     * con qué construirla.
     *
     * Se parte del nombre del ARCHIVO y no del título del cartucho: el
     * cartucho sólo guarda doce caracteres ("POKEMON EMER"), y `fileTitle`
     * además sustituye '_' por espacios para mostrarlo; ninguno de los dos
     * reproduce el nombre No-Intro con el que hay que buscar.
     */
    fun boxArtUrl(fileName: String, console: ConsoleKind = ConsoleKind.GAME_BOY_ADVANCE): String? {
        // Primero la ruta (por si el nombre llegara con carpeta), luego la
        // extensión; se usa la misma lista de extensiones que valida ROMManager
        // para que un archivo se acepte como ROM y como búsqueda de portada
        // bajo exactamente el mismo criterio.
        val base = fileName.substringAfterLast('/').substringAfterLast('\\').trim()
        val extension = ROM_EXTENSIONS.firstOrNull { base.endsWith(it, ignoreCase = true) }
        val title = if (extension != null) base.dropLast(extension.length) else base
        if (title.isBlank()) return null

        val safe = buildString(title.length) {
            for (ch in title) append(if (ch in FORBIDDEN) '_' else ch)
        }

        val system = systemFor(console)
        return "$BASE_URL/${encodePath(system)}/$BOXARTS_DIR/${encodePath(safe)}.png"
    }

    /**
     * Codificación de ruta en UTF-8 con %XX.
     *
     * URLEncoder es codificación de FORMULARIO: convierte el espacio en '+',
     * que en una ruta significa literalmente signo más (el nombre real
     * "Card-e+" se vería como "Card-e "). Se revierte ese único caso a %20,
     * que sí es espacio en una ruta. El resto de %XX lo decodifica el servidor
     * igual: comprobado contra el CDN que "%28" (paréntesis) devuelve 200.
     */
    private fun encodePath(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
