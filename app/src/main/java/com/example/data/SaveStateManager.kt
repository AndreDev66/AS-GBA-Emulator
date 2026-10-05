package com.example.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SaveStateSlot(
    val slotIndex: Int,
    val romId: String,
    val timestamp: Long,
    val thumbnailBitmap: Bitmap?,
    val exists: Boolean
) {
    val formattedDate: String
        get() {
            if (timestamp == 0L) return "Empty Slot"
            val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
            return sdf.format(Date(timestamp))
        }
}

/**
 * Manages Quick Save / Quick Load and multi-slot save states with screenshot previews.
 * Developed by Andrés Socorro.
 */
class SaveStateManager(private val context: Context) {
    companion object {
        private const val TAG = "SaveStateManager"
        const val MAX_SLOTS = 5
    }

    private val saveDir = File(context.filesDir, "savestates").apply { mkdirs() }
    private val thumbDir = File(context.filesDir, "thumbnails").apply { mkdirs() }

    private val _slots = MutableStateFlow<List<SaveStateSlot>>(emptyList())
    val slots = _slots.asStateFlow()

    suspend fun refreshSlots(romId: String) = withContext(Dispatchers.IO) {
        val list = mutableListOf<SaveStateSlot>()
        for (i in 1..MAX_SLOTS) {
            val stateFile = resolveSlotFile(romId, i)
            // La miniatura se busca con ambas claves: las ranuras heredadas
            // siguen mostrando su captura aunque se guarden con la clave actual.
            val thumbFile = listOf(
                File(thumbDir, "${romStorageKey(romId)}_$i.png"),
                File(thumbDir, "${legacyRomKey(romId)}_$i.png")
            ).firstOrNull { it.exists() }
                ?: File(thumbDir, "${romStorageKey(romId)}_$i.png")

            var bmp: Bitmap? = null
            if (thumbFile.exists()) {
                try {
                    bmp = BitmapFactory.decodeFile(thumbFile.absolutePath)
                } catch (e: Exception) {
                    Log.w(TAG, "Error decoding thumbnail: ${e.message}")
                }
            }

            val exists = stateFile.exists()
            list.add(
                SaveStateSlot(
                    slotIndex = i,
                    romId = romId,
                    timestamp = if (exists) stateFile.lastModified() else 0L,
                    thumbnailBitmap = bmp,
                    exists = exists
                )
            )
        }
        _slots.value = list
    }

    suspend fun saveSlot(
        romId: String,
        slotIndex: Int,
        stateData: ByteArray,
        screenshot: Bitmap
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // Se guarda siempre con la clave actual. Reutilizar la clave antigua
            // cuando ya existe su archivo hacía que la ranura quedara atada al
            // nombre legacy, que no distingue dos juegos con el mismo prefijo de
            // identificador: uno pisaba el estado del otro.
            val sanitized = romStorageKey(romId)
            val stateFile = File(saveDir, "${sanitized}_slot_$slotIndex.sav")
            val stateTemp = File(saveDir, "${sanitized}_slot_$slotIndex.sav.tmp")
            // El estado son ~160 KB y se serializa en un hilo aparte al de
            // emulación: escribirlo en un temporal evita que una interrupción a
            // mitad deje la ranura corrupta y, con ella, la partida.
            FileOutputStream(stateTemp).use { out ->
                out.write(stateData)
                out.flush()
                out.fd.sync()
            }
            if (!stateTemp.renameTo(stateFile)) {
                stateTemp.delete()
                Log.e(TAG, "No se pudo renombrar el estado de la ranura $slotIndex")
                return@withContext false
            }

            val thumbFile = File(thumbDir, "${sanitized}_$slotIndex.png")
            FileOutputStream(thumbFile).use { out ->
                screenshot.compress(Bitmap.CompressFormat.PNG, 90, out)
            }

            refreshSlots(romId)
            Log.i(TAG, "Slot $slotIndex saved for $romId (${stateData.size} bytes)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error saving slot $slotIndex: ${e.message}", e)
            false
        }
    }

    suspend fun loadSlot(romId: String, slotIndex: Int): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val stateFile = File(saveDir, "${romStorageKey(romId)}_slot_$slotIndex.sav")
            val file = if (stateFile.exists()) stateFile else legacySlotFile(romId, slotIndex)
            if (file.exists()) file.readBytes() else null
        } catch (e: Exception) {
            Log.e(TAG, "Error loading slot $slotIndex: ${e.message}", e)
            null
        }
    }

    /**
     * Ranuras guardadas con el nombre antiguo, que no distinguía juegos con el
     * mismo prefijo de identificador.
     */
    private fun legacySlotFile(romId: String, slotIndex: Int): File =
        File(saveDir, "${legacyRomKey(romId)}_slot_$slotIndex.sav")

    private fun legacyRomKey(romId: String): String =
        romId.replace("[^a-zA-Z0-9_-]".toRegex(), "_").take(40)

    private fun resolveSlotFile(romId: String, slotIndex: Int): File {
        val current = File(saveDir, "${romStorageKey(romId)}_slot_$slotIndex.sav")
        return if (current.exists()) current else legacySlotFile(romId, slotIndex)
    }
}
