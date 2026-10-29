package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.emulation.FastForwardAudio
import com.example.data.EmulatorSettings
import com.example.data.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * El sonido durante el avance rápido.
 *
 * Es una elección que se nota en caliente y que no puede perderse al guardar: si
 * el usuario pide que el avance suene y la clave no viaja, el diálogo guarda el
 * objeto entero y le devuelve el silencio sin que se entere.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FastForwardAudioSettingTest {

    private fun repository() =
        SettingsRepository(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun `por defecto el avance rapido va en silencio`() {
        // El silencio es lo que hacía el emulador antes de que esto se pudiera
        // elegir: nadie que actualice se encuentra sonido donde no lo había.
        assertEquals(FastForwardAudio.SILENCED, EmulatorSettings().fastForwardAudio)
    }

    @Test
    fun `pedir que suene se guarda y se vuelve a leer`() {
        val repo = repository()

        repo.updateSettings(EmulatorSettings(fastForwardAudio = FastForwardAudio.ACCELERATED))

        assertEquals(
            FastForwardAudio.ACCELERATED,
            repository().settings.value.fastForwardAudio,
        )
    }

    @Test
    fun `volver a silenciarlo se guarda`() {
        val repo = repository()
        repo.updateSettings(EmulatorSettings(fastForwardAudio = FastForwardAudio.ACCELERATED))

        repo.updateSettings(repo.settings.value.copy(fastForwardAudio = FastForwardAudio.SILENCED))

        assertEquals(FastForwardAudio.SILENCED, repository().settings.value.fastForwardAudio)
    }

    @Test
    fun `elegirlo no toca el resto de ajustes`() {
        val repo = repository()
        val before = EmulatorSettings(audioVolume = 0.3f, defaultFastForwardSpeed = 4.0f)

        repo.updateSettings(before.copy(fastForwardAudio = FastForwardAudio.ACCELERATED))

        val after = repository().settings.value
        assertEquals(0.3f, after.audioVolume, 0.001f)
        assertEquals(4.0f, after.defaultFastForwardSpeed, 0.001f)
        assertEquals(FastForwardAudio.ACCELERATED, after.fastForwardAudio)
    }

    @Test
    fun `una instalacion anterior sin la clave se queda en silencio`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("as_gba_settings", Context.MODE_PRIVATE)
            .edit().remove("ff_audio").commit()

        assertEquals(
            FastForwardAudio.SILENCED,
            SettingsRepository(context).settings.value.fastForwardAudio,
        )
    }
}
