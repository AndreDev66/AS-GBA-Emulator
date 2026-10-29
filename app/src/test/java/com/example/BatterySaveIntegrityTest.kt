package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.ROMManager
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Integridad de las partidas guardadas.
 *
 * El aviso que motivó estas pruebas: una partida se corrompió después de que el
 * teléfono se reiniciara sin más. Guardar bien no basta; hay que sobrevivir a
 * que el proceso muera en mitad del volcado.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BatterySaveIntegrityTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun romManager() = ROMManager(context)

    /** Localiza el archivo de un juego sin duplicar su regla de nombres. */
    private fun onlySav(romId: String): File {
        val dir = File(context.filesDir, "batteries")
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".sav") && f.name.contains(romId) }!!
            .single()
    }

    private fun pattern(size: Int): ByteArray = ByteArray(size) { (it % 251).toByte() }

    @Test
    fun `guarda y recupera la partida tal cual`() {
        val rom = romManager()
        val data = pattern(128 * 1024)

        assertTrue(rom.saveBattery("datos-completos", data))
        assertArrayEquals(data, rom.loadBattery("datos-completos"))
    }

    @Test
    fun `guardar no deja temporales sueltos`() {
        val rom = romManager()

        assertTrue(rom.saveBattery("sin-restos", pattern(64 * 1024)))

        val leftovers = File(context.filesDir, "batteries")
            .listFiles { f -> f.isFile && f.name.endsWith(".tmp") }
            .orEmpty()
        // Un temporal vivo tras guardar es basura: ya se renombró al destino.
        assertTrue("Quedaron temporales: $leftovers", leftovers.isEmpty())
    }

    @Test
    fun `una partida a medias se recupera tras morir el proceso`() {
        val rom = romManager()
        val data = pattern(32 * 1024)
        assertTrue(rom.saveBattery("interrumpido", data))

        // Reproduce el reinicio: el destino no llegó a renombrarse, pero los
        // datos estaban escritos y sincronizados en el temporal.
        val saved = onlySav("interrumpido")
        val temp = File(saved.parentFile, "${saved.name}.${data.size}.tmp")
        assertTrue(saved.renameTo(temp))
        assertFalse("el destino debe faltar en este escenario", saved.exists())

        assertArrayEquals(data, rom.loadBattery("interrumpido"))
        assertTrue("la partida debe quedar archivada en su sitio", saved.exists())
    }

    @Test
    fun `un temporal truncado no se admite como partida`() {
        val rom = romManager()

        // Se declara una partida de 128 KB pero sólo hay 40 KB escritos: el
        // proceso murió a mitad del volcado y el destino aún no existe. Cargar
        // esto como partida sería peor que no tener nada.
        val declared = 128 * 1024
        assertTrue(rom.saveBattery("truncado", pattern(declared)))
        val saved = onlySav("truncado")
        val temp = File(saved.parentFile, "${saved.name}.$declared.tmp")
        assertTrue(saved.delete())
        temp.writeBytes(pattern(40 * 1024))

        assertNull("un temporal cortado no es una partida", rom.loadBattery("truncado"))
        assertFalse("el temporal truncado debe descartarse", temp.exists())
    }

    @Test
    fun `sin partida previa la carga devuelve null`() {
        assertNull(romManager().loadBattery("nunca-jugado"))
    }

    @Test
    fun `sobrescribir repetido conserva siempre la ultima partida`() {
        val rom = romManager()
        assertTrue(rom.saveBattery("repetido", pattern(8 * 1024)))
        val ultima = pattern(8 * 1024).reversedArray()

        repeat(6) { assertTrue(rom.saveBattery("repetido", ultima)) }

        assertArrayEquals(ultima, rom.loadBattery("repetido"))
        assertTrue(onlySav("repetido").readBytes().contentEquals(ultima))
    }
}