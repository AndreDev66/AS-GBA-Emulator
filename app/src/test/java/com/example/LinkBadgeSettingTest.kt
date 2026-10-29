package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.EmulatorSettings
import com.example.data.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * El aviso de enlace en pantalla completa.
 *
 * Es un ajuste que se nota en caliente: si al guardar se pierde, el usuario
 * toca el interruptor, sale del diálogo y el icono sigue sin aparecer (o
 * viceversa). Y el valor por defecto importa igual, porque decide si la
 * partida empieza con algo flotando sobre el juego o no.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LinkBadgeSettingTest {

    private fun repository() =
        SettingsRepository(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun `apagado por defecto`() {
        // En horizontal el distintivo cae sobre el botón L de los controles
        // táctiles: por defecto la partida se ve limpia.
        assertFalse(EmulatorSettings().showLinkBadgeInFullscreen)
    }

    @Test
    fun `encenderlo se guarda y se vuelve a leer`() {
        val repo = repository()

        repo.updateSettings(EmulatorSettings(showLinkBadgeInFullscreen = true))

        assertTrue(repository().settings.value.showLinkBadgeInFullscreen)
    }

    @Test
    fun `apagarlo se guarda y se vuelve a leer`() {
        val repo = repository()
        repo.updateSettings(EmulatorSettings(showLinkBadgeInFullscreen = true))

        repo.updateSettings(EmulatorSettings(showLinkBadgeInFullscreen = false))

        assertFalse(repository().settings.value.showLinkBadgeInFullscreen)
    }

    @Test
    fun `encender el aviso no toca el resto de ajustes`() {
        // El diálogo de ajustes guarda el objeto entero: un campo mal copiado
        // se llevaría por delante el volumen, el acento o la posición de los
        // botones.
        val before = EmulatorSettings(
            audioVolume = 0.4f,
            accentTheme = com.example.data.AccentTheme.GBA_FLAME,
            showFpsCounter = false
        )
        val repo = repository()

        repo.updateSettings(before.copy(showLinkBadgeInFullscreen = true))

        val after = repository().settings.value
        assertEquals(0.4f, after.audioVolume, 0.001f)
        assertEquals(com.example.data.AccentTheme.GBA_FLAME, after.accentTheme)
        assertFalse(after.showFpsCounter)
        assertTrue(after.showLinkBadgeInFullscreen)
    }

    @Test
    fun `una instalacion anterior sin la clave se queda sin el aviso`() {
        // La clave no existe hasta que alguien la guarda: leerla con `false`
        // por defecto evita que una biblioteca o unos ajustes viejos enciendan
        // un interruptor que el usuario nunca tocó.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("as_gba_settings", Context.MODE_PRIVATE)
        prefs.edit().remove("show_link_badge_fullscreen").commit()

        assertFalse(SettingsRepository(context).settings.value.showLinkBadgeInFullscreen)
    }
}