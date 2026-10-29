// melonds_platform.cpp — implementación de `melonDS::Platform` para Android.
//
// El núcleo de melonDS no llama a libc directamente para el tiempo, los hilos,
// el log ni el almacenamiento: declara ese contrato en `Platform.h` y espera que
// el *frontend* lo satisfaga. Esta unidad es la cara de Android de ese
// contrato, escrita entera contra la NDK para que AS GBA Emulator pueda pilotar
// el núcleo sin el frontend oficial (que además arrastra Hilt, Room, OpenGL y su
// propio cuadro de botones).
//
// Son deliberadamente *stubs* las interfaces que AS no expone todavía: cámara y
// micrófono de DSi, multijugador local por wifi, red y los addons de rumble y
// movimiento. MelonDS las llama, pero su respuesta honesta es «no hay hardware».
//
// Base: melonDS (https://github.com/melonDS-emu/melonDS), GPL-3.0.
// Interfaz y fachada: AS GBA Emulator, de Andrés Socorro.

#include "melonds_platform.h"

#include "Platform.h"
#include "SPI_Firmware.h"

#include <android/log.h>

#include <cerrno>
#include <condition_variable>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <mutex>
#include <string>
#include <sys/resource.h>
#include <thread>
#include <unordered_map>
#include <vector>

#include <dlfcn.h>
#include <unistd.h>

using namespace melonDS;

namespace asgba
{
// Estado que el núcleo recibe como `userdata` y sobre el que notifica las
// escrituras de batería. El puente JNI de AS pide el volcado del SRAM con
// `MelonDsAdapter::battery()`, así que aquí sólo basta con marcar "sucio".
struct SaveSignals
{
    std::mutex mutex;
    bool ndsDirty = false;
    bool gbaDirty = false;
    bool firmwareDirty = false;
};

std::mutex& registryMutex()
{
    static std::mutex m;
    return m;
}

std::unordered_map<void*, SaveSignals*>& registry()
{
    static std::unordered_map<void*, SaveSignals*> map;
    return map;
}

SaveSignals* signalsFor(void* userdata)
{
    std::lock_guard<std::mutex> lock(registryMutex());
    auto it = registry().find(userdata);
    return it == registry().end() ? nullptr : it->second;
}

void registerInstance(void* userdata, SaveSignals* signals)
{
    std::lock_guard<std::mutex> lock(registryMutex());
    registry()[userdata] = signals;
}

void unregisterInstance(void* userdata)
{
    std::lock_guard<std::mutex> lock(registryMutex());
    registry().erase(userdata);
}

/** Directorio privado de la app, donde melonDS guarda firmware y archivos .ini. */
std::string& dataDir()
{
    static std::string dir = "/data/local/tmp";
    return dir;
}

void setDataDir(const char* path)
{
    std::lock_guard<std::mutex> lock(registryMutex());
    if (path && *path)
    {
        std::string copy = path;
        if (copy.back() != '/') copy += '/';
        dataDir() = copy;
    }
}
}

namespace asgba
{

void setPlatformDataDir(const char* path)
{
    asgba::setDataDir(path);
}

void platformRegister(void* userdata)
{
    if (!userdata) return;
    asgba::registerInstance(userdata, new asgba::SaveSignals);
}

void platformUnregister(void* userdata)
{
    auto* signals = asgba::signalsFor(userdata);
    asgba::unregisterInstance(userdata);
    delete signals;
}

bool platformTakeDirty(void* userdata, bool* nds, bool* gba, bool* firmware)
{
    auto* signals = asgba::signalsFor(userdata);
    if (!signals) return false;
    std::lock_guard<std::mutex> lock(signals->mutex);
    bool any = signals->ndsDirty || signals->gbaDirty || signals->firmwareDirty;
    if (nds) *nds = signals->ndsDirty;
    if (gba) *gba = signals->gbaDirty;
    if (firmware) *firmware = signals->firmwareDirty;
    signals->ndsDirty = false;
    signals->gbaDirty = false;
    signals->firmwareDirty = false;
    return any;
}

}

// ── Estructuras opacas que el núcleo sólo transporta ─────────────────────────

struct melonDS::Platform::FileHandle
{
    FILE* fp = nullptr;
};

struct melonDS::Platform::Thread
{
    std::thread thread;
};

struct melonDS::Platform::Semaphore
{
    std::mutex mutex;
    std::condition_variable ready;
    int count = 0;
};

struct melonDS::Platform::Mutex
{
    std::mutex mutex;
};

struct melonDS::Platform::DynamicLibrary
{
    void* handle = nullptr;
};

struct melonDS::Platform::AACDecoder
{
    // Sin decodificador: el DSP HLE de DSi responderá «no puedo» y el juego
    // degrade solo. Es el mismo compromiso que hace el frontend oficial cuando
    // no incluye faad2.
};

namespace melonDS::Platform
{
// ── Parada ─────────────────────────────────────────────────────────────────

void SignalStop(StopReason reason, void* userdata)
{
    Log(Info, "melonDS se ha detenido (motivo %d)", (int)reason);
}

// ── Archivos ───────────────────────────────────────────────────────────────

std::string GetLocalFilePath(const std::string& filename)
{
    return asgba::dataDir() + filename;
}

namespace
{
/// Traduce los flags de melonDS a la cadena de modo de `fopen`.
const char* modeString(FileMode mode)
{
    bool read = (mode & Read) != 0;
    bool write = (mode & Write) != 0;
    if (read && write) return (mode & NoCreate) ? "r+b" : "w+b";
    if (write) return (mode & NoCreate) ? "rb" : "wb";
    return (mode & Text) ? "rt" : "rb";
}

FileHandle* openWith(const std::string& path, FileMode mode)
{
    FILE* fp = fopen(path.c_str(), modeString(mode));
    if (!fp) return nullptr;
    auto* handle = new FileHandle;
    handle->fp = fp;
    return handle;
}
}

FileHandle* OpenFile(const std::string& path, FileMode mode)
{
    return openWith(path, mode);
}

FileHandle* OpenLocalFile(const std::string& path, FileMode mode)
{
    return openWith(GetLocalFilePath(path), mode);
}

bool FileExists(const std::string& name)
{
    return access(name.c_str(), F_OK) == 0;
}

bool LocalFileExists(const std::string& name)
{
    return FileExists(GetLocalFilePath(name));
}

bool CheckFileWritable(const std::string& filepath)
{
    // `access` no distingue «no existe» de «sin permiso»; para crear el archivo
    // si falta hace falta un `fopen` de escritura, como avisa el propio núcleo.
    if (access(filepath.c_str(), F_OK) == 0)
        return access(filepath.c_str(), W_OK) == 0;
    FILE* fp = fopen(filepath.c_str(), "ab");
    if (!fp) return false;
    fclose(fp);
    return true;
}

bool CheckLocalFileWritable(const std::string& filepath)
{
    return CheckFileWritable(GetLocalFilePath(filepath));
}

bool CloseFile(FileHandle* file)
{
    if (!file) return false;
    bool ok = fclose(file->fp) == 0;
    delete file;
    return ok;
}

bool IsEndOfFile(FileHandle* file)
{
    return file == nullptr || feof(file->fp) != 0;
}

bool FileReadLine(char* str, int count, FileHandle* file)
{
    return file && fgets(str, count, file->fp) != nullptr;
}

u64 FilePosition(FileHandle* file)
{
    if (!file) return 0;
    long pos = ftell(file->fp);
    return pos < 0 ? 0 : (u64)pos;
}

bool FileSeek(FileHandle* file, s64 offset, FileSeekOrigin origin)
{
    if (!file) return false;
    int whence = SEEK_SET;
    if (origin == FileSeekOrigin::Current) whence = SEEK_CUR;
    else if (origin == FileSeekOrigin::End) whence = SEEK_END;
    return fseek(file->fp, (long)offset, whence) == 0;
}

void FileRewind(FileHandle* file)
{
    if (file) rewind(file->fp);
}

u64 FileRead(void* data, u64 size, u64 count, FileHandle* file)
{
    if (!file || size == 0 || count == 0) return 0;
    return fread(data, size, count, file->fp);
}

bool FileFlush(FileHandle* file)
{
    return file && fflush(file->fp) == 0;
}

u64 FileWrite(const void* data, u64 size, u64 count, FileHandle* file)
{
    if (!file || size == 0 || count == 0) return 0;
    return fwrite(data, size, count, file->fp);
}

u64 FileWriteFormatted(FileHandle* file, const char* fmt, ...)
{
    if (!file || !fmt) return 0;
    va_list args;
    va_start(args, fmt);
    int written = vfprintf(file->fp, fmt, args);
    va_end(args);
    return written < 0 ? 0 : (u64)written;
}

u64 FileLength(FileHandle* file)
{
    if (!file) return 0;
    // El contrato exige devolver la longitud *sin* mover la posición.
    long saved = ftell(file->fp);
    if (saved < 0) return 0;
    u64 length = 0;
    if (fseek(file->fp, 0, SEEK_END) == 0)
    {
        long end = ftell(file->fp);
        if (end > 0) length = (u64)end;
    }
    fseek(file->fp, saved, SEEK_SET);
    return length;
}

// ── Log ────────────────────────────────────────────────────────────────────

void Log(LogLevel level, const char* fmt, ...)
{
    if (!fmt) return;

    // melonDS usa el nivel Debug para los accesos a registros de E/S que no
    // implementa. Un juego que sondea mucho puede generar miles por fotograma,
    // y formatearlos para que logcat luego los descarte se paga en el hilo de
    // emulación. Se cortan antes de tocar `vsnprintf`.
    if (level == Debug) return;

    char buffer[1024];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buffer, sizeof(buffer), fmt, args);
    va_end(args);

    int prio = ANDROID_LOG_INFO;
    switch (level)
    {
        case Debug: prio = ANDROID_LOG_DEBUG; break;
        case Info: prio = ANDROID_LOG_INFO; break;
        case Warn: prio = ANDROID_LOG_WARN; break;
        case Error: prio = ANDROID_LOG_ERROR; break;
    }
    __android_log_print(prio, "MelonDS", "%s", buffer);
}

// ── Hilos y sincronización ─────────────────────────────────────────────────

Thread* Thread_Create(std::function<void()> func)
{
    auto* thread = new Thread;
    thread->thread = std::thread([func = std::move(func)]() {
        // Los hilos que crea el núcleo —sobre todo el del render 3D— alimentan
        // al hilo de emulación y esperan turno con él. Con la prioridad de fondo
        // se quedan detrás de otros procesos y el fotograma se alarga; con la de
        // dibujado van a la par. Si el sistema no deja cambiarla, se sigue.
        setpriority(PRIO_PROCESS, 0, -4);
        func();
    });
    return thread;
}

void Thread_Free(Thread* thread)
{
    delete thread;
}

void Thread_Wait(Thread* thread)
{
    if (thread && thread->thread.joinable()) thread->thread.join();
}

Semaphore* Semaphore_Create()
{
    return new Semaphore;
}

void Semaphore_Free(Semaphore* sema)
{
    delete sema;
}

void Semaphore_Reset(Semaphore* sema)
{
    std::lock_guard<std::mutex> lock(sema->mutex);
    sema->count = 0;
}

void Semaphore_Wait(Semaphore* sema)
{
    std::unique_lock<std::mutex> lock(sema->mutex);
    sema->ready.wait(lock, [sema] { return sema->count > 0; });
    sema->count--;
}

bool Semaphore_TryWait(Semaphore* sema, int timeout_ms)
{
    std::unique_lock<std::mutex> lock(sema->mutex);
    if (sema->count > 0)
    {
        sema->count--;
        return true;
    }
    if (timeout_ms <= 0) return false;
    return sema->ready.wait_for(lock, std::chrono::milliseconds(timeout_ms),
                                [sema] { return sema->count > 0; }) &&
           (sema->count--, true);
}

void Semaphore_Post(Semaphore* sema, int count)
{
    {
        std::lock_guard<std::mutex> lock(sema->mutex);
        sema->count += count;
    }
    sema->ready.notify_all();
}

Mutex* Mutex_Create()
{
    return new Mutex;
}

void Mutex_Free(Mutex* mutex)
{
    delete mutex;
}

void Mutex_Lock(Mutex* mutex)
{
    mutex->mutex.lock();
}

void Mutex_Unlock(Mutex* mutex)
{
    mutex->mutex.unlock();
}

bool Mutex_TryLock(Mutex* mutex)
{
    return mutex->mutex.try_lock();
}

void Sleep(u64 usecs)
{
    usleep((useconds_t)usecs);
}

u64 GetUSCount()
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (u64)ts.tv_sec * 1000000ull + (u64)(ts.tv_nsec / 1000);
}

u64 GetMSCount()
{
    return GetUSCount() / 1000ull;
}

// ── Persistencia: aquí sólo se marca «sucio» ───────────────────────────────
//
// El volcado real lo hace AS, que ya tiene su propio gestor de batería por ROM
// (`ROMManager.saveBattery`). El núcleo entrega el búfer entero en cada aviso.

void WriteNDSSave(const u8* savedata, u32 savelen, u32 writeoffset, u32 writelen, void* userdata)
{
    auto* signals = asgba::signalsFor(userdata);
    if (!signals) return;
    std::lock_guard<std::mutex> lock(signals->mutex);
    signals->ndsDirty = true;
}

void WriteGBASave(const u8* savedata, u32 savelen, u32 writeoffset, u32 writelen, void* userdata)
{
    auto* signals = asgba::signalsFor(userdata);
    if (!signals) return;
    std::lock_guard<std::mutex> lock(signals->mutex);
    signals->gbaDirty = true;
}

void WriteFirmware(const Firmware& firmware, u32 writeoffset, u32 writelen, void* userdata)
{
    auto* signals = asgba::signalsFor(userdata);
    if (!signals) return;
    std::lock_guard<std::mutex> lock(signals->mutex);
    signals->firmwareDirty = true;
}

void WriteDateTime(int year, int month, int day, int hour, int minute, int second, void* userdata)
{
    // La DS usa el reloj para el temporizador del juego; melonDS mantiene su
    // propio reloj y esta llamada sólo informa a la interfaz.
}

// ── Multijugador local (wifi) y red: no soportados ──────────────────────────

void MP_Begin(void* userdata) {}
void MP_End(void* userdata) {}

int MP_SendPacket(u8* data, int len, u64 timestamp, void* userdata) { return 0; }
int MP_RecvPacket(u8* data, u64* timestamp, void* userdata) { return -1; }
int MP_SendCmd(u8* data, int len, u64 timestamp, void* userdata) { return 0; }
int MP_SendReply(u8* data, int len, u64 timestamp, u16 aid, void* userdata) { return 0; }
int MP_SendAck(u8* data, int len, u64 timestamp, void* userdata) { return 0; }
int MP_RecvHostPacket(u8* data, u64* timestamp, void* userdata) { return -1; }
u16 MP_RecvReplies(u8* data, u64 timestamp, u16 aidmask, void* userdata) { return 0; }

int Net_SendPacket(u8* data, int len, void* userdata) { return 0; }
int Net_RecvPacket(u8* data, void* userdata) { return -1; }

// ── Cámara y micrófono de DSi ──────────────────────────────────────────────

void Camera_Start(int num, void* userdata) {}
void Camera_Stop(int num, void* userdata) {}
void Camera_CaptureFrame(int num, u32* frame, int width, int height, bool yuv, void* userdata) {}

void Mic_Start(void* userdata) {}
void Mic_Stop(void* userdata) {}
int Mic_ReadInput(s16* data, int maxlength, void* userdata) { return 0; }

// ── AAC (DSP HLE de DSi) ───────────────────────────────────────────────────

AACDecoder* AAC_Init() { return new AACDecoder; }
void AAC_DeInit(AACDecoder* dec) { delete dec; }
bool AAC_Configure(AACDecoder* dec, int frequency, int channels) { return false; }
bool AAC_DecodeFrame(AACDecoder* dec, const void* input, int inputlen, void* output, int outputlen)
{
    return false;
}

// ── Addons ─────────────────────────────────────────────────────────────────

bool Addon_KeyDown(KeyType type, void* userdata) { return false; }
void Addon_RumbleStart(u32 len, void* userdata) {}
void Addon_RumbleStop(void* userdata) {}
float Addon_MotionQuery(MotionQueryType type, void* userdata) { return 0.0f; }

// ── Bibliotecas dinámicas ───────────────────────────────────────────────────
//
// Imprescindible: el «fastmem» del JIT busca `ASharedMemory_create` en
// `libandroid.so` para crear su memoria ejecutable compartida.

DynamicLibrary* DynamicLibrary_Load(const char* lib)
{
    if (!lib) return nullptr;
    void* handle = dlopen(lib, RTLD_NOW | RTLD_LOCAL);
    if (!handle) return nullptr;
    auto* wrapper = new DynamicLibrary;
    wrapper->handle = handle;
    return wrapper;
}

void DynamicLibrary_Unload(DynamicLibrary* lib)
{
    if (!lib) return;
    if (lib->handle) dlclose(lib->handle);
    delete lib;
}

void* DynamicLibrary_LoadFunction(DynamicLibrary* lib, const char* name)
{
    if (!lib || !name || !lib->handle) return nullptr;
    return dlsym(lib->handle, name);
}
}
