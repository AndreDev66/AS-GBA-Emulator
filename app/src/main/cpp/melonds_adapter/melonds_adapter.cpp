// melonds_adapter.cpp — el núcleo de Nintendo DS sobre melonDS.
//
// Todo lo que aquí ocurre es melonDS 1.1, una API que no es la que suele
// aparecer en tutoriales: la ROM se pasa desde memoria con
// `NDSCart::ParseROM`, la entrada es una *máscara* de teclas y no una llamada
// por botón, y el estado se serializa a un búfer en memoria en vez de a un
// `FILE*`. Todo eso encaja bien con AS: el puente JNI ya trabaja así con
// RavenEmu (byte[] de entrada, byte[] de salida, sin ficheros).
//
// El fotograma que se entrega a AS mide 512×192: la pantalla táctil a la
// izquierda y la principal a la derecha, como se sostiene la consola. Es una
// decisión de presentación, no del hardware, y evita que el renderizador de AS
// tenga que saber de pantallas.
//
// Base: melonDS (https://github.com/melonDS-emu/melonDS), GPL-3.0.
// Interfaz y fachada: AS GBA Emulator, de Andrés Socorro.

#include "melonds_adapter.h"
#include "melonds_platform.h"

#include "NDS.h"
#include "NDS_Header.h"
#include "NDSCart.h"
#include "GPU3D_Soft.h"
#include "Savestate.h"
#include "SPU.h"

#include <algorithm>
#include <cstring>
#include <fstream>
#include <limits>
#include <string>

using namespace melonDS;

namespace asgba
{
namespace
{

/** Nombre con el que se presenta el cartucho al arranque directo.
 *
 *  melonDS lo usa para el camino del cartucho en la tarjeta SD del homebrew y
 *  para nada más: no se abre ningún fichero con él. */
constexpr const char* kDirectBootName = "asgba.nds";

/** Traduce el ordinal del botón de AS al bit que espera `NDS::SetKeyMask`.
 *
 *  El bit no coincide con el ordinal porque melonDS sigue el orden del
 *  registro KEYINPUT del hardware: A, B, Select, Start y la cruceta, luego
 *  L, R y las dos caras nuevas de la DS, X e Y. */
constexpr u32 dsKeyBit(DsButton button)
{
    switch (button)
    {
        case DsButton::A: return 1u << 0;
        case DsButton::B: return 1u << 1;
        case DsButton::Select: return 1u << 2;
        case DsButton::Start: return 1u << 3;
        case DsButton::Right: return 1u << 4;
        case DsButton::Left: return 1u << 5;
        case DsButton::Up: return 1u << 6;
        case DsButton::Down: return 1u << 7;
        case DsButton::R: return 1u << 8;
        case DsButton::L: return 1u << 9;
        case DsButton::X: return 1u << 10;
        case DsButton::Y: return 1u << 11;
    }
    return 0;
}

/** Los píxeles BGRA de melonDS ya tienen el orden entero ARGB que espera Android. */
inline int32_t toArgb(u32 pixel)
{
    // El byte de alfa de melonDS llega a cero y Android lo leería como
    // transparente. Se rellena aquí, en el mismo recorrido que ya se hace para
    // convertir, y así Kotlin no tiene que repasar el fotograma entero
    // poniendo el mismo byte.
    return static_cast<int32_t>(pixel | 0xFF000000u);
}

std::string trimmed(const char* text, std::size_t maxLength)
{
    std::size_t length = 0;
    while (length < maxLength && text[length] != '\0') length++;
    std::size_t end = length;
    while (end > 0 && (text[end - 1] == ' ' || text[end - 1] == '\0')) end--;
    std::size_t begin = 0;
    while (begin < end && text[begin] == ' ') begin++;
    return std::string(text + begin, end - begin);
}

/** Etiqueta de la región a partir del byte que trae la cabecera.
 *
 *  El campo es un byte suelto, no el enum `RegionMask` de melonDS: aquel
 *  describe lo que el cartucho *permite* y hay que construirlo a mano desde
 *  los bits. Para lo que AS muestra —una etiqueta corta— el byte vale. */
const char* regionName(u8 code)
{
    switch (code)
    {
        case 0x00: return "J";
        case 0x01: return "E";
        case 0x02: return "P";
        case 0x03: return "O";
        case 0x04: return "I";
        case 0x05: return "K";
        case 0x0A: return "libre";
        default: return "?";
    }
}

const char* unitName(u8 unitCode)
{
    switch (unitCode)
    {
        case 0x03: return "DS Phat";
        case 0x10: return "DS Lite";
        case 0x20:
        case 0x21:
        case 0x22: return "DSi";
        default: return "DS";
    }
}

}
}

struct asgba::MelonDsAdapter::Impl
{
    std::unique_ptr<NDS> nds;
    std::string title;
    std::string gameCode;
    std::string region;
    std::string unit;
    u32 keyMask = 0xFFF;
    int stylusX = 0;
    int stylusY = 0;
    bool stylusDown = false;
    // melonDS remuestrea internamente a esta tasa, y hay que fijarla al construir
    // el núcleo porque su SPU no la admite después. AS abre la pista de audio con
    // la misma cifra, que es justo para lo que sirve este campo.
    double sampleRate = 48000.0;
};

asgba::MelonDsAdapter::MelonDsAdapter()
    : impl_(new Impl)
{
    platformRegister(this);

    // Los valores por omisión de `NDSArgs` son justo los que queremos: FreeBIOS
    // en vez de una BIOS volcada, firmware generado y arranque directo. El JIT
    // viene activado, y es lo que hace que un ARM9 sea aceptable en un móvil.
    NDSArgs args;
    args.OutputSampleRate = impl_->sampleRate;
    impl_->nds = std::make_unique<NDS>(std::move(args), this);
    impl_->nds->Reset();

    // El renderizador 3D por software puede dibujar en su propio hilo. Sin esto,
    // rasterizar los polígonos y emular los dos ARM se pelean por el mismo
    // núcleo, y en un móvil de ocho núcleos eso es dejar siete sin usar justo
    // en la parte más cara del fotograma. melonDS lo trae como opción de
    // frontend; aquí se activa para toda sesión.
    if (auto* soft = dynamic_cast<SoftRenderer*>(&impl_->nds->GPU.GPU3D.GetCurrentRenderer()))
        soft->SetThreaded(true, impl_->nds->GPU);
}

asgba::MelonDsAdapter::~MelonDsAdapter()
{
    if (impl_->nds)
    {
        impl_->nds->Stop();
        impl_->nds.reset();
    }
    // Se desregistra *después* de destruir el núcleo: mientras el núcleo viva
    // puede seguir llamando a la capa `Platform` con esta clave.
    platformUnregister(this);
    delete impl_;
}

bool asgba::MelonDsAdapter::loadRom(const uint8_t* rom, int romLength, const uint8_t* save,
                             int saveLength, std::string* error)
{
    if (rom == nullptr || romLength <= 0)
    {
        if (error) *error = "La ROM de Nintendo DS está vacía.";
        return false;
    }

    auto buffer = std::make_unique<u8[]>(static_cast<std::size_t>(romLength));
    std::memcpy(buffer.get(), rom, static_cast<std::size_t>(romLength));
    return loadRomBuffer(std::move(buffer), static_cast<u32>(romLength), save, saveLength, error);
}

bool asgba::MelonDsAdapter::loadRomFile(const char* path, const uint8_t* save, int saveLength,
                                        std::string* error)
{
    auto fail = [error](const char* message) {
        if (error) *error = message;
        return false;
    };
    if (path == nullptr || path[0] == '\0') return fail("La ruta de la ROM está vacía.");

    std::ifstream input(path, std::ios::binary | std::ios::ate);
    if (!input) return fail("No se pudo abrir el archivo de la ROM.");
    const std::streamoff length = input.tellg();
    if (length <= 0 || static_cast<std::uint64_t>(length) > std::numeric_limits<u32>::max())
        return fail("El tamaño de la ROM no es válido.");

    auto buffer = std::make_unique<u8[]>(static_cast<std::size_t>(length));
    input.seekg(0, std::ios::beg);
    if (!input.read(reinterpret_cast<char*>(buffer.get()), static_cast<std::streamsize>(length)))
        return fail("No se pudo leer el archivo completo de la ROM.");

    return loadRomBuffer(std::move(buffer), static_cast<u32>(length), save, saveLength, error);
}

bool asgba::MelonDsAdapter::loadRomBuffer(std::unique_ptr<uint8_t[]> buffer, uint32_t romLength,
                                          const uint8_t* save, int saveLength,
                                          std::string* error)
{
    auto fail = [error](const char* message) {
        if (error) *error = message;
        return false;
    };

    std::optional<NDSCart::NDSCartArgs> cartArgs;
    if (save != nullptr && saveLength > 0)
    {
        cartArgs.emplace();
        cartArgs->SRAM = std::make_unique<u8[]>(static_cast<std::size_t>(saveLength));
        std::memcpy(cartArgs->SRAM.get(), save, static_cast<std::size_t>(saveLength));
        cartArgs->SRAMLength = static_cast<u32>(saveLength);
    }

    auto cart = NDSCart::ParseROM(std::move(buffer), static_cast<u32>(romLength), this,
                                  std::move(cartArgs));
    if (!cart) return fail("melonDS no ha reconocido el cartucho.");

    const NDSHeader& header = cart->GetHeader();
    impl_->title = trimmed(header.GameTitle, sizeof(header.GameTitle));
    impl_->gameCode = trimmed(header.GameCode, sizeof(header.GameCode));
    impl_->region = regionName(header.NDSRegion);
    impl_->unit = unitName(header.UnitCode);
    if (impl_->title.empty()) impl_->title = "Juego de Nintendo DS";

    impl_->nds->SetNDSCart(std::move(cart));
    // La máquina se reinicia con el cartucho ya dentro. Sin este `Reset`, al
    // reabrir un juego sin cerrar la aplicación la consola seguía con la CPU
    // donde la dejó la sesión anterior, y el arranque directo le escribía los
    // parámetros en la RAM principal por debajo: el juego acababa colgado en
    // una pantalla blanca. El `Reset` del constructor no basta porque entonces
    // todavía no hay cartucho.
    impl_->nds->Reset();
    // El arranque directo va después del `Reset` a propósito: es quien escribe
    // los parámetros de arranque en la RAM principal, y el `Reset` la vacía.
    impl_->nds->SetupDirectBoot(kDirectBootName);
    return true;
}

void asgba::MelonDsAdapter::start()
{
    if (impl_->nds) impl_->nds->Start();
}

bool asgba::MelonDsAdapter::reset()
{
    if (!impl_->nds) return false;
    impl_->nds->Reset();
    // El `Reset` vacía la RAM principal, y ahí es donde el arranque directo
    // deja la cabecera del cartucho. Sin volver a escribirla, reiniciar dejaba
    // las dos pantallas de la DS en blanco.
    if (impl_->nds->CartInserted()) impl_->nds->SetupDirectBoot(kDirectBootName);
    impl_->nds->Start();
    return true;
}

bool asgba::MelonDsAdapter::isRunning() const
{
    return impl_->nds && impl_->nds->IsRunning();
}

void asgba::MelonDsAdapter::runFrame(int32_t* framebuffer)
{
    if (!impl_->nds || framebuffer == nullptr) return;

    // KEYINPUT es activo en bajo: un bit a uno significa que la tecla está suelta.
    impl_->nds->SetKeyMask(impl_->keyMask);
    if (impl_->stylusDown)
        impl_->nds->TouchScreen(static_cast<u16>(impl_->stylusX), static_cast<u16>(impl_->stylusY));
    else
        impl_->nds->ReleaseScreen();

    impl_->nds->RunFrame();

    // Tras el fotograma, `FrontBuffer` es el que quedó completo. El índice 0 es
    // la pantalla principal y el 1 la táctil, porque `AssignFramebuffers` los
    // intercambia según cuál de los dos motores esté en pantalla de arriba.
    const int front = impl_->nds->GPU.FrontBuffer;
    const u32* main = impl_->nds->GPU.Framebuffer[front][0].get();
    const u32* touch = impl_->nds->GPU.Framebuffer[front][1].get();
    if (main == nullptr || touch == nullptr) return;

    for (int row = 0; row < kMelonDsScreenHeight; row++)
    {
        const u32* touchRow = touch + row * kMelonDsScreenWidth;
        const u32* mainRow = main + row * kMelonDsScreenWidth;
        int32_t* out = framebuffer + row * kMelonDsFrameWidth;
        for (int column = 0; column < kMelonDsScreenWidth; column++)
        {
            out[column] = toArgb(touchRow[column]);
            out[kMelonDsScreenWidth + column] = toArgb(mainRow[column]);
        }
    }
}

void asgba::MelonDsAdapter::setButton(DsButton button, bool pressed)
{
    const u32 bit = dsKeyBit(button);
    if (bit == 0) return;
    if (pressed) impl_->keyMask &= ~bit;
    else impl_->keyMask |= bit;
}

void asgba::MelonDsAdapter::setStylus(int x, int y, bool pressed)
{
    impl_->stylusX = std::clamp(x, 0, kMelonDsScreenWidth - 1);
    impl_->stylusY = std::clamp(y, 0, kMelonDsScreenHeight - 1);
    impl_->stylusDown = pressed;
}

int asgba::MelonDsAdapter::readAudio(int16_t* destination, int samples)
{
    if (!impl_->nds || destination == nullptr || samples <= 0) return 0;
    return impl_->nds->SPU.ReadOutput(destination, samples);
}

bool asgba::MelonDsAdapter::saveState(std::string* out)
{
    if (!impl_->nds || out == nullptr) return false;
    Savestate state;
    impl_->nds->DoSavestate(&state);
    if (state.Error) return false;
    out->assign(static_cast<const char*>(state.Buffer()), state.Length());
    return true;
}

bool asgba::MelonDsAdapter::loadState(const std::string& data)
{
    if (!impl_->nds || data.empty()) return false;
    Savestate state(const_cast<char*>(data.data()), static_cast<u32>(data.size()), false);
    impl_->nds->DoSavestate(&state);
    return !state.Error;
}

bool asgba::MelonDsAdapter::hasBattery() const
{
    // `GetNDSSaveLength` no copia nada, y por eso es la forma de contestar a
    // esto. Preguntarlo con `battery()` volcaría la partida entera —hasta un
    // megabyte— sólo para comprobar si el puntero era nulo.
    return impl_->nds != nullptr && impl_->nds->GetNDSSaveLength() > 0;
}

const uint8_t* asgba::MelonDsAdapter::battery(int* length) const
{
    if (!impl_->nds) return nullptr;
    const u32 size = impl_->nds->GetNDSSaveLength();
    if (size == 0) return nullptr;
    if (length) *length = static_cast<int>(size);
    return impl_->nds->GetNDSSave();
}

bool asgba::MelonDsAdapter::batteryDirty()
{
    bool nds = false;
    bool gba = false;
    bool firmware = false;
    platformTakeDirty(this, &nds, &gba, &firmware);
    return nds;
}

void asgba::MelonDsAdapter::acknowledgeBattery()
{
    bool nds = false;
    bool gba = false;
    bool firmware = false;
    platformTakeDirty(this, &nds, &gba, &firmware);
}

std::string asgba::MelonDsAdapter::gameTitle() const { return impl_->title; }
std::string asgba::MelonDsAdapter::gameCode() const { return impl_->gameCode; }
std::string asgba::MelonDsAdapter::region() const { return impl_->region; }
std::string asgba::MelonDsAdapter::unit() const { return impl_->unit; }

void asgba::setMelonDsDataDir(const char* path)
{
    asgba::setPlatformDataDir(path);
}
