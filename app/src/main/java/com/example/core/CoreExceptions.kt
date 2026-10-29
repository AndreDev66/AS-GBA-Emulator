package com.example.core

/**
 * Fallo al cargar o interpretar una ROM de Game Boy Advance.
 *
 * Lanzada por el puente JNI cuando el núcleo rechaza el cartucho. Conserva el
 * mensaje del núcleo: describe la causa concreta y es lo único que el usuario
 * tiene para entender el rechazo.
 */
class RomLoadException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * Fallo al guardar o restaurar un estado de emulación.
 *
 * Lanzada por el puente JNI cuando el estado no pertenece a la partida cargada o
 * está dañado. El mensaje del núcleo se conserva para el diagnóstico.
 */
class SaveStateException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
