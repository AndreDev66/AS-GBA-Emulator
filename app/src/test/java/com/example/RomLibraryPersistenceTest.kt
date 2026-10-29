package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.ROMManager
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Persistencia del nombre propio y la portada elegida.
 *
 * Lo que se guarda en la biblioteca sobrevive a que la app se cierre: si el
 * nombre o la portada se perdieran al reiniciar, el diálogo de edición
 * sería una trampa. Y al revés también: una biblioteca guardada antes de
 * que existieran estos campos tiene que seguir cargando, porque el `getString`
 * sobre una clave ausente lanza en vez de devolver nada.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RomLibraryPersistenceTest {

    private val prefsName = "as_gba_rom_library"
    private val romsKey = "imported_roms"

    /** Biblioteca tal y como la dejó una versión anterior de la app. */
    private val legacyEntry = """
        {"id":"abc123","title":"POKEMON EMER","gameCode":"BPES",
         "uriString":"content://roms/1","fileName":"Pokemon Esmeralda.gba",
         "sizeBytes":16777216,"isHackRom":false}
    """.trimIndent()

    private fun seed(vararg entries: String) {
        context().getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit()
            .putString(romsKey, "[${entries.joinToString(",")}]")
            .commit()
    }

    private fun stored(): JSONArray =
        JSONArray(
            context().getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                .getString(romsKey, "[]")!!
        )

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `una biblioteca sin los campos nuevos carga igual`() {
        seed(legacyEntry)

        val library = ROMManager(context()).romList.value

        assertEquals(1, library.size)
        // Sin nombre propio ni portada, la tarjeta se sigue hoy con el
        // nombre del archivo y con la carátula automática del CDN.
        assertEquals("Pokemon Esmeralda", library[0].displayTitle)
        assertNull(library[0].displayName)
        assertNull(library[0].customCoverPath)
    }

    @Test
    fun `el nombre propio sobrevive a cerrar y volver a abrir`() {
        seed(legacyEntry)
        ROMManager(context()).renameRom("abc123", "Mi Esmeralda")

        // Instancia nueva: es como si la app se hubiera reiniciado.
        val reloaded = ROMManager(context()).romList.value

        assertEquals("Mi Esmeralda", reloaded[0].displayTitle)
    }

    @Test
    fun `renombrar guarda el nombre y no toca el archivo`() {
        seed(legacyEntry)

        val manager = ROMManager(context())
        manager.renameRom("abc123", "Mi Esmeralda")

        val storedEntry = stored().getJSONObject(0)
        assertEquals("Mi Esmeralda", storedEntry.getString("displayName"))
        // Lo que depende del archivo real tiene que seguir igual: de aquí
        // salen la carga de la ROM y la partida guardada.
        assertEquals("Pokemon Esmeralda.gba", storedEntry.getString("fileName"))
        assertEquals("content://roms/1", storedEntry.getString("uriString"))
        assertEquals(16777216L, storedEntry.getLong("sizeBytes"))
    }

    @Test
    fun `un nombre en blanco se guarda como ausencia y no como vacio`() {
        seed(legacyEntry)

        val manager = ROMManager(context())
        manager.renameRom("abc123", "   ")

        // Vaciar el campo devuelve la ficha a su nombre de archivo; guardar
        // "" o "   " como nombre propio dejaría una tarjeta sin título.
        assertEquals("Pokemon Esmeralda", manager.romList.value[0].displayTitle)
        assertTrue(stored().getJSONObject(0).isNull("displayName"))
    }

    @Test
    fun `la portada propia se guarda como ruta`() {
        seed(legacyEntry)
        val cover = "/data/user/0/com.andressocorro.asgba/files/covers/abc123.png"

        val manager = ROMManager(context())
        // Se escribe la ruta a mano en vez de pasar por el selector de
        // archivos: lo que se comprueba aquí es que sobreviva al guardado.
        val withCover = manager.romList.value.first().copy(customCoverPath = cover)
        seedLegacyWithCover(withCover)

        val reloaded = ROMManager(context()).romList.value[0]

        assertEquals(cover, reloaded.customCoverPath)
        assertEquals("Pokemon Esmeralda", reloaded.displayTitle)
    }

    /** Vuelve a sembrar la biblioteca pasando ya la ruta de portada. */
    private fun seedLegacyWithCover(item: com.example.data.ROMItem) {
        val entry = JSONObject()
            .put("id", item.id)
            .put("title", item.title)
            .put("gameCode", item.gameCode)
            .put("uriString", item.uriString)
            .put("fileName", item.fileName)
            .put("sizeBytes", item.sizeBytes)
            .put("isHackRom", item.isHackRom)
            .put("customCoverPath", item.customCoverPath)
        seed(entry.toString())
    }
}