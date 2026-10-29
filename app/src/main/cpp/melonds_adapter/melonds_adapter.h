// melonds_adapter.h — frontera de C++17 entre AS GBA Emulator y melonDS.
//
// Este encabezado lo incluye `asgba_jni.cpp`, que compila como C++20 junto al
// resto del puente. Por eso **no** puede incluir cabeceras del núcleo: los tipos de
// melonDS (C++17, `std::optional` por todas partes) se quedan en la unidad de
// implementación y cruzan esta frontera sólo como `void*` y tipos primitivos.
//
// Base: melonDS (https://github.com/melonDS-emu/melonDS), GPL-3.0.
// Interfaz y fachada: AS GBA Emulator, de Andrés Socorro.

#pragma once

#include <cstdint>
#include <memory>
#include <string>


namespace asgba
{

/** Geometría del fotograma que entrega melonDS a AS: dos pantallas de 256×192
 *  en horizontal, con la táctil a la izquierda, que es como se sostiene la
 *  consola. El índice del plano y el de la pantalla táctil coinciden a propósito:
 *  el renderizador de AS no necesita saber nada del hardware. */
constexpr int kMelonDsScreenWidth = 256;
constexpr int kMelonDsScreenHeight = 192;
constexpr int kMelonDsFrameWidth = kMelonDsScreenWidth * 2;
constexpr int kMelonDsFrameHeight = kMelonDsScreenHeight;
constexpr int kMelonDsFramePixels = kMelonDsFrameWidth * kMelonDsFrameHeight;

/** Orden de los botones. Es el mismo `EmulatorButton` de AS, y el ordinal es
 *  un contrato con el puente JNI; aquí sólo se traduce a la máscara que espera
 *  `NDS::SetKeyMask`, cuyos bits sí siguen el orden del hardware. */
enum class DsButton : int
{
    Up = 0,
    Down = 1,
    Left = 2,
    Right = 3,
    A = 4,
    B = 5,
    Start = 6,
    Select = 7,
    L = 8,
    R = 9,
    X = 10,
    Y = 11,
};

/**
 * Núcleo de Nintendo DS sobre melonDS.
 *
 * Igual que los núcleos de RavenEmu, es *pasivo*: no tiene hilo ni reloj. Quien
 * lo posee llama a `runFrame()` con la cadencia que quiera y lee el audio cuando
 * le conviene. La diferencia es que melonDS sí crea hilos propios (el del render
 * 3D y el del fastmem del JIT), que son responsabilidad suya.
 */
class MelonDsAdapter
{
public:
    MelonDsAdapter();
    ~MelonDsAdapter();

    MelonDsAdapter(const MelonDsAdapter&) = delete;
    MelonDsAdapter& operator=(const MelonDsAdapter&) = delete;

    /** Carga la ROM desde memoria y arranca en modo directo (sin BIOS ni
     *  firmware: melonDS trae FreeBIOS y genera un firmware sintético).
     *  Devuelve false y rellena `error` si el cartucho no arranca. */
    bool loadRom(const uint8_t* rom, int romLength, const uint8_t* save, int saveLength,
                 std::string* error);

    /** Carga la ROM desde disco sin crear un arreglo Java del tamaño del cartucho. */
    bool loadRomFile(const char* path, const uint8_t* save, int saveLength,
                     std::string* error);

    /**
     * Arranca la emulación tras [loadRom].
     *
     * MelonDS distingue montar la máquina de ponerla en marcha, y por eso el
     * arranque va aparte de la carga y también se repite en [reset].
     */
    void start();

    /** Reinicia la consola conservando el cartucho. */
    bool reset();

    /**
     * false si el juego ha apagado la consola.
     *
     * Una ROM de DS puede apagar el sistema por su cuenta. A partir de ahí
     * `RunFrame` no vuelve a hacer nada útil y no hay nada que reiniciar solo.
     */
    bool isRunning() const;

    /** Emula un fotograma y pinta las dos pantallas en `framebuffer`, que debe
     *  caber en [kMelonDsFramePixels] enteros. */
    void runFrame(int32_t* framebuffer);

    /** Fija el estado de un botón (un `DsButton` por llamada). */
    void setButton(DsButton button, bool pressed);

    /** Puntero sobre la pantalla táctil, en coordenadas de pantalla (0..255).
     *  El hardware usa 12 bits; la conversión es responsabilidad de aquí. */
    void setStylus(int x, int y, bool pressed);

    /** Copia muestras estéreo entrelazadas y devuelve cuántas se copiaron.
     *
     *  No hace falta vaciar la cola a mano: el SPU es circular y se sobrescribe
     *  solo, de modo que leerlo en cada fotograma lo deja acotado igual. */
    int readAudio(int16_t* destination, int samples);

    /** Serializa el estado completo de la consola.
     *  Sólo debe llamarse desde el hilo de emulación y con la consola en pausa:
     *  melonDS avisa de que guardar mientras su hilo de render 3D trabaja
     *  puede dejar punteros colgando en el estado del GPU. */
    bool saveState(std::string* out);

    bool loadState(const std::string& data);

    /** Memoria de guardado del cartucho (SRAM/EEPROM/FLASH). El puntero es
     *  efímero: vale hasta el siguiente `reset()` o cambio de juego. */
    const uint8_t* battery(int* length) const;

    /** true si el cartucho tiene memoria de guardado. No copia nada, así que
     *  es la forma barata de contestar a esto; `battery()` sí lo vuelca. */
    bool hasBattery() const;

    /** true si el juego ha escrito en la memoria de guardado desde el último
     *  volcado. */
    bool batteryDirty();

    void acknowledgeBattery();

    // ── Metadatos de la cabecera del cartucho ────────────────────────────────

    std::string gameTitle() const;
    std::string gameCode() const;

    /** Región del cartucho: "J", "E", "P",… o "libre". */
    std::string region() const;

    /** Modelo declarado por el cartucho: "DS", "DS Lite" o "DSi". */
    std::string unit() const;

private:
    bool loadRomBuffer(std::unique_ptr<uint8_t[]> rom, uint32_t romLength,
                       const uint8_t* save, int saveLength, std::string* error);

    struct Impl;
    Impl* impl_;
};

/** Fija el directorio privado donde melonDS puede escribir (firmware, `.ini`).
 *  Lo llama el puente JNI con `Context.getFilesDir()`. */
void setMelonDsDataDir(const char* path);

}
