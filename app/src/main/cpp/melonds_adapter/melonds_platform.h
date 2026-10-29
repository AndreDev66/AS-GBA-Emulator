// melonds_platform.h — lo que el adaptador necesita de la capa `Platform`.
//
// `Platform.h` de melonDS es un contrato de enlace, no una interfaz: AS lo
// satisface con un puñado de símbolos que no devuelven nada a nadie. Estos
// tres punteros de entrada son el único canal de vuelta, y existen para que el
// núcleo no sea global: cada instancia registra su propia señal de "guardado
// sucio" bajo la clave `userdata` que ella misma le pasó al constructor.
//
// Base: melonDS (https://github.com/melonDS-emu/melonDS), GPL-3.0.

#pragma once

namespace asgba
{

/** Directorio privado de la app para firmware y archivos de melonDS. */
void setPlatformDataDir(const char* path);

/** Registra la instancia de `userdata` para recibir avisos de guardado. */
void platformRegister(void* userdata);

/** Libera la instancia. Debe llamarse antes de destruir lo que `userdata` apunta. */
void platformUnregister(void* userdata);

/** Toma los avisos de escritura pendientes y los consume.
 *  Devuelve true si había alguno. */
bool platformTakeDirty(void* userdata, bool* nds, bool* gba, bool* firmware);

}
