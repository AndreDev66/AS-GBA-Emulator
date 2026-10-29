// melonds_jni.cpp — frontera JNI del núcleo de Nintendo DS.
//
// Es deliberadamente *otra* frontera y no una rama dentro de `asgba_jni.cpp`:
// aquel archivo convierte el identificador que le llega directamente en un
// `ravenemu::Core*`, y en una puntuación de funciones ese tipo está asumido.
// Meter aquí una Nintendo DS habríaobligado a representar «cualquier consola» en
// todas ellas, con el riesgo de romper el camino de Game Boy y GBA, que ya
// funciona. Con dos fronteras, Kotlin elige la que le toca según la consola y
// ninguna de las dos cambia.
//
// Contrapartida: hay dos superficies que mantener. Se acepta porque el núcleo de
// melonDS no comparte ni una línea con RavenEmu.
//
// Base: melonDS (https://github.com/melonDS-emu/melonDS), GPL-3.0.
// Interfaz y fachada: AS GBA Emulator, de Andrés Socorro.

#include "melonds_adapter.h"
#include "melonds_platform.h"

#include <jni.h>

#include <cstdint>
#include <string>
#include <vector>

namespace
{

/** Traduce una excepción de C++ a la excepción Java que ya usa el puente de AS. */
void translate(JNIEnv* env, const char* message)
{
    if (env->ExceptionCheck()) return;
    jclass klass = env->FindClass("java/lang/RuntimeException");
    if (klass == nullptr) return;
    env->ThrowNew(klass, message);
    env->DeleteLocalRef(klass);
}

asgba::MelonDsAdapter* adapter_from(JNIEnv* env, jlong handle)
{
    if (handle == 0)
    {
        translate(env, "El núcleo de Nintendo DS está cerrado");
        return nullptr;
    }
    return reinterpret_cast<asgba::MelonDsAdapter*>(static_cast<std::uintptr_t>(handle));
}

jstring to_java(JNIEnv* env, const std::string& text)
{
    return env->NewStringUTF(text.c_str());
}

std::string from_java(JNIEnv* env, jstring text)
{
    if (text == nullptr) return {};
    const char* chars = env->GetStringUTFChars(text, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(text, chars);
    return result;
}

std::vector<std::uint8_t> read_bytes(JNIEnv* env, jbyteArray source)
{
    if (source == nullptr) return {};
    const jsize length = env->GetArrayLength(source);
    std::vector<std::uint8_t> result(static_cast<std::size_t>(length));
    if (length > 0)
    {
        env->GetByteArrayRegion(source, 0, length,
                                reinterpret_cast<jbyte*>(result.data()));
        if (env->ExceptionCheck()) throw std::runtime_error("No se pudo leer el arreglo Java");
    }
    return result;
}

}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_core_MelonDsCoreBridge_create(JNIEnv* env, jclass, jstring data_dir)
{
    try
    {
        asgba::setMelonDsDataDir(from_java(env, data_dir).c_str());
        auto* adapter = new asgba::MelonDsAdapter();
        return static_cast<jlong>(reinterpret_cast<std::uintptr_t>(adapter));
    }
    catch (const std::exception& error)
    {
        translate(env, error.what());
        return 0;
    }
    catch (...)
    {
        translate(env, "No se pudo crear el núcleo de Nintendo DS");
        return 0;
    }
}

JNIEXPORT void JNICALL
Java_com_example_core_MelonDsCoreBridge_destroy(JNIEnv*, jclass, jlong handle)
{
    if (handle == 0) return;
    delete reinterpret_cast<asgba::MelonDsAdapter*>(static_cast<std::uintptr_t>(handle));
}

JNIEXPORT jobject JNICALL
Java_com_example_core_MelonDsCoreBridge_loadRom(JNIEnv* env, jclass, jlong handle,
                                                jbyteArray rom, jbyteArray save)
{
    auto* adapter = adapter_from(env, handle);
    if (adapter == nullptr) return nullptr;

    try
    {
        const std::vector<std::uint8_t> romBytes = read_bytes(env, rom);
        const std::vector<std::uint8_t> saveBytes = read_bytes(env, save);

        std::string error;
        const bool ok = adapter->loadRom(
            romBytes.empty() ? nullptr : romBytes.data(),
            static_cast<int>(romBytes.size()),
            saveBytes.empty() ? nullptr : saveBytes.data(),
            static_cast<int>(saveBytes.size()),
            &error
        );
        if (!ok)
        {
            jclass klass = env->FindClass("java/lang/RuntimeException");
            if (klass != nullptr)
            {
                env->ThrowNew(klass, error.empty() ? "No se pudo cargar la ROM" : error.c_str());
                env->DeleteLocalRef(klass);
            }
            return nullptr;
        }

        jclass info_class = env->FindClass("com/example/core/MelonDsRomInfo");
        if (info_class == nullptr) return nullptr;
        jmethodID constructor = env->GetMethodID(
            info_class, "<init>",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
        if (constructor == nullptr) return nullptr;

        jobject info = env->NewObject(
            info_class, constructor,
            to_java(env, adapter->gameTitle()),
            to_java(env, adapter->gameCode()),
            to_java(env, adapter->region()),
            to_java(env, adapter->unit())
        );
        env->DeleteLocalRef(info_class);
        return info;
    }
    catch (const std::exception& error)
    {
        translate(env, error.what());
        return nullptr;
    }
}

JNIEXPORT jobject JNICALL
Java_com_example_core_MelonDsCoreBridge_loadRomFile(JNIEnv* env, jclass, jlong handle,
                                                    jstring path, jbyteArray save)
{
    auto* adapter = adapter_from(env, handle);
    if (adapter == nullptr) return nullptr;

    try
    {
        const std::string romPath = from_java(env, path);
        const std::vector<std::uint8_t> saveBytes = read_bytes(env, save);
        std::string error;
        if (!adapter->loadRomFile(romPath.c_str(),
                                  saveBytes.empty() ? nullptr : saveBytes.data(),
                                  static_cast<int>(saveBytes.size()), &error))
        {
            jclass klass = env->FindClass("java/lang/RuntimeException");
            if (klass != nullptr)
            {
                env->ThrowNew(klass, error.empty() ? "No se pudo cargar la ROM" : error.c_str());
                env->DeleteLocalRef(klass);
            }
            return nullptr;
        }

        jclass info_class = env->FindClass("com/example/core/MelonDsRomInfo");
        if (info_class == nullptr) return nullptr;
        jmethodID constructor = env->GetMethodID(
            info_class, "<init>",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
        if (constructor == nullptr) return nullptr;

        jobject info = env->NewObject(
            info_class, constructor,
            to_java(env, adapter->gameTitle()),
            to_java(env, adapter->gameCode()),
            to_java(env, adapter->region()),
            to_java(env, adapter->unit())
        );
        env->DeleteLocalRef(info_class);
        return info;
    }
    catch (const std::exception& error)
    {
        translate(env, error.what());
        return nullptr;
    }
}

JNIEXPORT void JNICALL
Java_com_example_core_MelonDsCoreBridge_start(JNIEnv* env, jclass, jlong handle)
{
    auto* adapter = adapter_from(env, handle);
    if (adapter == nullptr) return;
    try { adapter->start(); } catch (...) { translate(env, "No se pudo arrancar la consola"); }
}

JNIEXPORT void JNICALL
Java_com_example_core_MelonDsCoreBridge_reset(JNIEnv* env, jclass, jlong handle)
{
    auto* adapter = adapter_from(env, handle);
    if (adapter == nullptr) return;
    try { adapter->reset(); } catch (...) { translate(env, "No se pudo reiniciar la consola"); }
}

JNIEXPORT jboolean JNICALL
Java_com_example_core_MelonDsCoreBridge_isRunning(JNIEnv* env, jclass, jlong handle)
{
    auto* adapter = adapter_from(env, handle);
    if (adapter == nullptr) return JNI_FALSE;
    try { return adapter->isRunning() ? JNI_TRUE : JNI_FALSE; }
    catch (...) { translate(env, "No se pudo consultar el estado"); return JNI_FALSE; }
}

JNIEXPORT void JNICALL
Java_com_example_core_MelonDsCoreBridge_runFrame(JNIEnv* env, jclass, jlong handle,
                                                 jintArray framebuffer)
{
    auto* adapter = adapter_from(env, handle);
    if (adapter == nullptr) return;
    if (framebuffer == nullptr)
    {
        translate(env, "Falta el búfer de fotograma");
        return;
    }

    try
    {
        const jsize length = env->GetArrayLength(framebuffer);
        if (length < asgba::kMelonDsFramePixels)
        {
            translate(env, "El búfer de fotograma es demasiado pequeño para la Nintendo DS");
            return;
        }

        // El búfer nativo es del hilo y se reserva una sola vez por sesión.
        // Antes se creaba un vector por fotograma; esta versión no reserva en el
        // camino caliente y, a diferencia de un `GetPrimitiveArrayCritical`,
        // tampoco retiene la región crítica de la máquina virtual mientras el
        // hilo de render 3D termina el fotograma.
        thread_local std::vector<std::int32_t> pixels;
        if (pixels.size() < static_cast<std::size_t>(asgba::kMelonDsFramePixels))
            pixels.resize(asgba::kMelonDsFramePixels);

        adapter->runFrame(pixels.data());
        env->SetIntArrayRegion(framebuffer, 0, asgba::kMelonDsFramePixels,
                               pixels.data());
    }
    catch (const std::exception& error)
    {
        translate(env, error.what());
    }
    catch (...)
    {
        translate(env, "Error al emular un fotograma");
    }
}

JNIEXPORT void JNICALL
Java_com_example_core_MelonDsCoreBridge_setButton(JNIEnv* env, jclass, jlong handle,
                                                   jint button, jboolean pressed)
{
    auto* adapter = adapter_from(env, handle);
    if (adapter == nullptr) return;
    try
    {
        adapter->setButton(static_cast<asgba::DsButton>(button), pressed == JNI_TRUE);
    }
    catch (...)
    {
        translate(env, "Botón desconocido");
    }
}

JNIEXPORT void JNICALL
Java_com_example_core_MelonDsCoreBridge_setStylus(JNIEnv* env, jclass, jlong handle,
                                                  jint x, jint y, jboolean pressed)
{
    auto* adapter = adapter_from(env, handle);
    if (adapter == nullptr) return;
    try { adapter->setStylus(x, y, pressed == JNI_TRUE); }
    catch (...) { translate(env, "No se pudo mover el puntero"); }
}

JNIEXPORT jint JNICALL
Java_com_example_core_MelonDsCoreBridge_readAudio(JNIEnv* env, jclass, jlong handle,
                                                   jshortArray destination)
{
    auto* adapter = adapter_from(env, handle);
    if (adapter == nullptr) return 0;
    if (destination == nullptr) return 0;

    try
    {
        const jsize shorts = env->GetArrayLength(destination);
        // El SPU entrega muestras estéreo entrelazadas: dos `s16` por muestra.
        const int samples = static_cast<int>(shorts / 2);
        if (samples <= 0) return 0;

        // Igual que el vídeo: un búfer por hilo, sin reservar ni retener la
        // región crítica en cada fotograma.
        thread_local std::vector<std::int16_t> buffer;
        if (buffer.size() < static_cast<std::size_t>(shorts))
            buffer.resize(shorts);

        const int read = adapter->readAudio(buffer.data(), samples);
        if (read <= 0) return 0;
        env->SetShortArrayRegion(destination, 0, read * 2, buffer.data());
        // El contrato compartido con RavenEmu cuenta valores short, no pares
        // estéreo: devolver sólo `read` hace que el reloj de audio vaya al doble.
        return read * 2;
    }
    catch (...)
    {
        translate(env, "No se pudo leer el audio");
        return 0;
    }
}

JNIEXPORT jbyteArray JNICALL
Java_com_example_core_MelonDsCoreBridge_saveState(JNIEnv* env, jclass, jlong handle)
{
    auto* adapter = adapter_from(env, handle);
    if (adapter == nullptr) return nullptr;
    try
    {
        std::string state;
        if (!adapter->saveState(&state)) return nullptr;
        jbyteArray result = env->NewByteArray(static_cast<jsize>(state.size()));
        if (result == nullptr) return nullptr;
        env->SetByteArrayRegion(result, 0, static_cast<jsize>(state.size()),
                                reinterpret_cast<const jbyte*>(state.data()));
        return result;
    }
    catch (...)
    {
        translate(env, "No se pudo guardar el estado");
        return nullptr;
    }
}

JNIEXPORT jboolean JNICALL
Java_com_example_core_MelonDsCoreBridge_loadState(JNIEnv* env, jclass, jlong handle,
                                                   jbyteArray state)
{
    auto* adapter = adapter_from(env, handle);
    if (adapter == nullptr) return JNI_FALSE;
    try
    {
        const std::vector<std::uint8_t> bytes = read_bytes(env, state);
        const bool ok = adapter->loadState(
            std::string(reinterpret_cast<const char*>(bytes.data()), bytes.size()));
        return ok ? JNI_TRUE : JNI_FALSE;
    }
    catch (...)
    {
        translate(env, "No se pudo cargar el estado");
        return JNI_FALSE;
    }
}

JNIEXPORT jboolean JNICALL
Java_com_example_core_MelonDsCoreBridge_hasBattery(JNIEnv* env, jclass, jlong handle)
{
    auto* adapter = adapter_from(env, handle);
    if (adapter == nullptr) return JNI_FALSE;
    try { return adapter->hasBattery() ? JNI_TRUE : JNI_FALSE; }
    catch (...) { return JNI_FALSE; }
}

JNIEXPORT jbyteArray JNICALL
Java_com_example_core_MelonDsCoreBridge_snapshotBattery(JNIEnv* env, jclass, jlong handle)
{
    auto* adapter = adapter_from(env, handle);
    if (adapter == nullptr) return nullptr;
    try
    {
        int length = 0;
        const std::uint8_t* data = adapter->battery(&length);
        if (data == nullptr || length <= 0) return nullptr;
        jbyteArray result = env->NewByteArray(length);
        if (result == nullptr) return nullptr;
        env->SetByteArrayRegion(result, 0, length, reinterpret_cast<const jbyte*>(data));
        return result;
    }
    catch (...)
    {
        translate(env, "No se pudo volcar la partida guardada");
        return nullptr;
    }
}

JNIEXPORT jboolean JNICALL
Java_com_example_core_MelonDsCoreBridge_batteryDirty(JNIEnv* env, jclass, jlong handle)
{
    auto* adapter = adapter_from(env, handle);
    if (adapter == nullptr) return JNI_FALSE;
    try { return adapter->batteryDirty() ? JNI_TRUE : JNI_FALSE; }
    catch (...) { return JNI_FALSE; }
}

JNIEXPORT void JNICALL
Java_com_example_core_MelonDsCoreBridge_acknowledgeBattery(JNIEnv* env, jclass, jlong handle)
{
    auto* adapter = adapter_from(env, handle);
    if (adapter == nullptr) return;
    try { adapter->acknowledgeBattery(); } catch (...) { }
}

}
