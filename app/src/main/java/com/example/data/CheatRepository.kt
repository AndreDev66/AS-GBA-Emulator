package com.example.data

import android.content.Context
import com.example.core.cheats.CheatEntry
import com.example.core.cheats.CheatStorageCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Trucos guardados, por ROM.
 *
 * Cada ROM tiene su propia lista, indexada por [ROMItem.id] (el hash del
 * contenido). Va en unas preferencias compartidas y no en archivos sueltos
 * porque el volumen es pequeño (unos pocos códigos por juego) y porque así no
 * hay que Ottmar rutas en el sistema de archivos.
 *
 * El estado se mantiene en un [MutableStateFlow] para que la UI se entere de
 * los cambios sin recargar: marcar un truco se ve al instante.
 */
class CheatRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _cheats = MutableStateFlow<Map<String, List<CheatEntry>>>(emptyMap())
    val cheats = _cheats.asStateFlow()

    /** Trucos de una ROM; lista vacía si esa ROM no tiene ninguno. */
    fun forRom(romId: String): List<CheatEntry> = _cheats.value[romId].orEmpty()

    /**
     * Sustituye la lista de una ROM y la persiste.
     *
     * Se guarda la lista entera en vez de un diff por truco: son unos pocos
     * elementos y así no puede quedar medio guardado.
     */
    fun saveForRom(romId: String, entries: List<CheatEntry>) {
        val updated = _cheats.value.toMutableMap()
        if (entries.isEmpty()) updated.remove(romId) else updated[romId] = entries
        _cheats.value = updated
        prefs.edit().putString(romId, CheatStorageCodec.encode(entries)).apply()
    }

    /**
     * Lee de disco la lista de una ROM y la deja en el estado.
     *
     * Se llama al cargar un juego, no al arrancar: leer todas las listas de la
     * biblioteca para descartar las que no se van a usar sólo costaría I/O.
     */
    fun loadForRom(romId: String) {
        if (_cheats.value.containsKey(romId)) return
        val entries = CheatStorageCodec.decode(prefs.getString(romId, null))
        _cheats.value = _cheats.value + (romId to entries)
    }

    private companion object {
        const val PREFS_NAME = "asgba_cheats"
    }
}
