/**
 * AS GBA Emulator — puente JNI sobre el núcleo C++ de RavenEmu.
 *
 * El motor de emulación (CPU ARM7TDMI, PPU, APU, temporizadores, DMA, cartucho,
 * BIOS y memoria de guardado) es el proyecto RavenEmu, tomado como base tal cual:
 * vive íntegro en `ravenemu/` y no se modifica desde aquí. Este archivo sólo
 * traduce ese motor a la frontera Java/Kotlin de AS GBA Emulator.
 *
 * La frontera transporta únicamente arrays primitivos y tres objetos de valor
 * inmutables. Toda la política de emulación —cadencia, audio, persistencia de
 * la RAM de cartucho— vive en Kotlin, del lado de la interfaz.
 *
 * Base: RavenEmu (núcleo GBA y puente JNI `NativeCoreBridge`).
 * Interfaz y fachada: AS GBA Emulator, desarrollado por Andrés Socorro.
 */

// `ravenemu/gba/core.hpp` no se incluye: su ayudante `make_core()` llama a
// `make_gba_core()` sin el argumento de tipo de guardado que la fábrica exige,
// de modo que incluirlo no compila. La fábrica se toma directamente de
// `ravenemu/core.hpp`, que es donde vive su declaración.
#include "ravenemu/core.hpp"

#include <jni.h>

#include <array>
#include <cstdint>
#include <limits>
#include <memory>
#include <optional>
#include <span>
#include <stdexcept>
#include <string>
#include <vector>

namespace {

using ravenemu::Core;

/** Excepción Java de clase y mensaje, con constructor de dos argumentos. */
void throw_kotlin_exception(JNIEnv* env, const char* class_name, const char* message) {
    const auto klass = env->FindClass(class_name);
    if (klass == nullptr) return;
    const auto constructor = env->GetMethodID(
        klass,
        "<init>",
        "(Ljava/lang/String;Ljava/lang/Throwable;)V"
    );
    if (constructor == nullptr) {
        env->DeleteLocalRef(klass);
        return;
    }
    const auto text = env->NewStringUTF(message);
    if (text == nullptr) {
        env->DeleteLocalRef(klass);
        return;
    }
    const auto exception = static_cast<jthrowable>(
        env->NewObject(klass, constructor, text, nullptr)
    );
    if (exception != nullptr) env->Throw(exception);
    env->DeleteLocalRef(exception);
    env->DeleteLocalRef(text);
    env->DeleteLocalRef(klass);
}

/** Excepción Java de clase y mensaje, sin causa. */
void throw_simple_exception(JNIEnv* env, const char* class_name, const char* message) noexcept {
    const auto klass = env->FindClass(class_name);
    if (klass == nullptr) return;
    env->ThrowNew(klass, message);
    env->DeleteLocalRef(klass);
}

void translate_exception(JNIEnv* env) noexcept {
    // Una excepción Java ya pendiente es la causa primera de la excepción C++ que
    // se va a traducir: es ella que porta la información. Sustituirla por un
    // mensaje de repli tiraría el diagnóstico. Además, llamar a JNI con una
    // excepción pendiente es comportamiento indefinido y `FindClass` puede
    // devolver `nullptr`, de modo que traducir igualmente convertiría un error
    // reportable en un fallo nativo.
    if (env->ExceptionCheck()) return;

    try {
        throw;
    } catch (const ravenemu::RomLoadError& error) {
        throw_kotlin_exception(env, "com/example/core/RomLoadException", error.what());
    } catch (const ravenemu::SaveStateError& error) {
        throw_kotlin_exception(env, "com/example/core/SaveStateException", error.what());
    } catch (const std::invalid_argument& error) {
        throw_simple_exception(env, "java/lang/IllegalArgumentException", error.what());
    } catch (const std::logic_error& error) {
        throw_simple_exception(env, "java/lang/IllegalStateException", error.what());
    } catch (const std::exception& error) {
        throw_simple_exception(env, "java/lang/RuntimeException", error.what());
    } catch (...) {
        throw_simple_exception(env, "java/lang/RuntimeException", "Error nativo desconocido");
    }
}

template <typename Result, typename Function>
Result guarded(JNIEnv* env, Result fallback, Function&& function) noexcept {
    try {
        return std::forward<Function>(function)();
    } catch (...) {
        translate_exception(env);
        return fallback;
    }
}

template <typename Function>
void guarded_void(JNIEnv* env, Function&& function) noexcept {
    try {
        std::forward<Function>(function)();
    } catch (...) {
        translate_exception(env);
    }
}

Core& core_from(jlong handle) {
    if (handle == 0) throw std::logic_error("El núcleo nativo RavenEmu está cerrado");
    return *reinterpret_cast<Core*>(static_cast<std::uintptr_t>(handle));
}

std::optional<ravenemu::GbaSaveType> gba_save_type_from(jint value) {
    if (value < 0) return std::nullopt;
    if (value > static_cast<jint>(ravenemu::GbaSaveType::eeprom_8k)) {
        throw std::invalid_argument("Tipo de guardado GBA desconocido");
    }
    return static_cast<ravenemu::GbaSaveType>(value);
}

/**
 * Ajuste tri-estado del reloj de cartucho, transportado como entero al faltar
 * `Optional` en la frontera JNI: negativo devuelve el control a la detección,
 * cero impone la ausencia, positivo impone la presencia.
 */
std::optional<bool> forced_rtc_from(jint value) {
    if (value < 0) return std::nullopt;
    return value != 0;
}

std::vector<std::uint8_t> read_bytes(JNIEnv* env, jbyteArray source, bool nullable) {
    if (source == nullptr) {
        if (nullable) return {};
        throw std::invalid_argument("Falta el arreglo de bytes");
    }
    const auto length = env->GetArrayLength(source);
    std::vector<std::uint8_t> result(static_cast<std::size_t>(length));
    if (length > 0) {
        env->GetByteArrayRegion(
            source,
            0,
            length,
            reinterpret_cast<jbyte*>(result.data())
        );
        // La excepción Java lanzada por la copia se deja pendiente: es ella que
        // llegará a Kotlin. La de C++ sólo interrumpe el trabajo nativo en curso.
        if (env->ExceptionCheck()) throw std::runtime_error("No se pudo leer el arreglo Java");
    }
    return result;
}

/**
 * Convierte un tamaño nativo a longitud de arreglo Java.
 *
 * `jsize` es un entero **con signo** de 32 bits. Una conversión sin control
 * envolvería un tamaño demasiado grande hacia una longitud negativa o
 * truncada, y el arreglo devuelto a Kotlin no contendría lo que el nativo cree
 * haber escrito. La comprobación vive aquí y no duplicada en cada llamador.
 */
jsize checked_length(std::size_t size) {
    if (size > static_cast<std::size_t>(std::numeric_limits<jsize>::max())) {
        throw std::length_error("Arreglo nativo demasiado grande");
    }
    return static_cast<jsize>(size);
}

/**
 * Señala el fallo de una reserva o de una búsqueda del lado de la JVM.
 *
 * Estas llamadas devuelven `nullptr` tras armar una excepción Java. Seguir
 * llamando JNI en ese estado es indefinido, y devolver el `nullptr` al
 * encadenamiento que sigue equivaldría exactamente a eso. Se interrumpe el
 * trabajo nativo y `translate_exception` deja subir la excepción pendiente.
 */
template <typename Handle>
Handle require_jvm(Handle handle, const char* what) {
    if (handle == nullptr) throw std::runtime_error(what);
    return handle;
}

jbyteArray make_byte_array(JNIEnv* env, std::span<const std::uint8_t> source) {
    const auto length = checked_length(source.size());
    const auto result = require_jvm(
        env->NewByteArray(length),
        "No se pudo reservar el arreglo de bytes Java"
    );
    if (length > 0) {
        env->SetByteArrayRegion(
            result,
            0,
            length,
            reinterpret_cast<const jbyte*>(source.data())
        );
    }
    return result;
}

jintArray make_int_array(JNIEnv* env, std::span<const std::int32_t> source) {
    static_assert(sizeof(jint) == sizeof(std::int32_t));
    const auto length = checked_length(source.size());
    const auto result = require_jvm(
        env->NewIntArray(length),
        "No se pudo reservar el arreglo de enteros Java"
    );
    if (length > 0) {
        env->SetIntArrayRegion(
            result,
            0,
            length,
            reinterpret_cast<const jint*>(source.data())
        );
    }
    return result;
}

/** Clase Java esperada por el puente: su ausencia es un error de compilación. */
jclass require_class(JNIEnv* env, const char* name) {
    return require_jvm(env->FindClass(name), name);
}

} // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) {
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_core_NativeCoreBridge_create(
    JNIEnv* env,
    jclass,
    jint console_storage_id,
    jint forced_save_type
) {
    return guarded<jlong>(env, 0, [&] {
        if (console_storage_id != static_cast<jint>(ravenemu::Console::game_boy_advance)) {
            throw std::invalid_argument("Consola desconocida para este puente");
        }
        std::unique_ptr<Core> core = ravenemu::make_gba_core(
            gba_save_type_from(forced_save_type)
        );
        return static_cast<jlong>(reinterpret_cast<std::uintptr_t>(core.release()));
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_core_NativeCoreBridge_destroy(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<Core*>(static_cast<std::uintptr_t>(handle));
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_core_NativeCoreBridge_loadRom(
    JNIEnv* env,
    jclass,
    jlong handle,
    jbyteArray rom,
    jbyteArray battery
) {
    guarded_void(env, [&] {
        const auto rom_bytes = read_bytes(env, rom, false);
        const auto battery_bytes = read_bytes(env, battery, true);
        core_from(handle).load_rom(rom_bytes, battery_bytes);
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_core_NativeCoreBridge_reset(JNIEnv* env, jclass, jlong handle) {
    guarded_void(env, [&] { core_from(handle).reset(); });
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_core_NativeCoreBridge_runFrame(
    JNIEnv* env,
    jclass,
    jlong handle,
    jintArray framebuffer,
    jboolean render_video
) {
    guarded_void(env, [&] {
        if (framebuffer == nullptr) throw std::invalid_argument("Falta el framebuffer");
        const auto length = env->GetArrayLength(framebuffer);
        auto* elements = env->GetIntArrayElements(framebuffer, nullptr);
        if (elements == nullptr) throw std::runtime_error("Framebuffer Java inaccesible");
        try {
            core_from(handle).run_frame(
                std::span<std::int32_t>{
                    reinterpret_cast<std::int32_t*>(elements),
                    static_cast<std::size_t>(length)
                },
                render_video != JNI_FALSE
            );
        } catch (...) {
            env->ReleaseIntArrayElements(framebuffer, elements, 0);
            throw;
        }
        env->ReleaseIntArrayElements(framebuffer, elements, 0);
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_core_NativeCoreBridge_setButton(
    JNIEnv* env,
    jclass,
    jlong handle,
    jint button,
    jboolean pressed
) {
    guarded_void(env, [&] {
        if (button < 0 || button > static_cast<jint>(ravenemu::Button::r)) {
            throw std::invalid_argument("Botón desconocido");
        }
        core_from(handle).set_button(
            static_cast<ravenemu::Button>(button),
            pressed != JNI_FALSE
        );
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_core_NativeCoreBridge_readAudio(
    JNIEnv* env,
    jclass,
    jlong handle,
    jshortArray destination
) {
    return guarded<jint>(env, 0, [&] {
        if (destination == nullptr) throw std::invalid_argument("Falta el búfer de audio");
        const auto length = env->GetArrayLength(destination);
        auto* elements = env->GetShortArrayElements(destination, nullptr);
        if (elements == nullptr) throw std::runtime_error("Búfer de audio Java inaccesible");
        std::size_t count{};
        try {
            count = core_from(handle).read_audio(
                std::span<std::int16_t>{
                    reinterpret_cast<std::int16_t*>(elements),
                    static_cast<std::size_t>(length)
                }
            );
        } catch (...) {
            env->ReleaseShortArrayElements(destination, elements, 0);
            throw;
        }
        env->ReleaseShortArrayElements(destination, elements, 0);
        return static_cast<jint>(count);
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_core_NativeCoreBridge_framebufferFormat(
    JNIEnv* env,
    jclass,
    jlong handle
) {
    return guarded<jint>(env, 0, [&] {
        return static_cast<jint>(core_from(handle).framebuffer_format());
    });
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_core_NativeCoreBridge_hasBatteryRam(JNIEnv* env, jclass, jlong handle) {
    return guarded<jboolean>(env, JNI_FALSE, [&] {
        return static_cast<jboolean>(
            core_from(handle).has_battery_ram() ? JNI_TRUE : JNI_FALSE
        );
    });
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_core_NativeCoreBridge_batteryRamDirty(JNIEnv* env, jclass, jlong handle) {
    return guarded<jboolean>(env, JNI_FALSE, [&] {
        return static_cast<jboolean>(
            core_from(handle).battery_ram_dirty() ? JNI_TRUE : JNI_FALSE
        );
    });
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_example_core_NativeCoreBridge_snapshotBatteryRam(
    JNIEnv* env,
    jclass,
    jlong handle
) {
    return guarded<jobject>(env, nullptr, [&]() -> jobject {
        auto snapshot = core_from(handle).snapshot_battery_ram();
        if (!snapshot) return nullptr;
        const auto data = make_byte_array(env, snapshot->data);
        const auto klass = require_class(env, "com/example/core/NativeBatterySnapshot");
        const auto constructor = env->GetMethodID(klass, "<init>", "([BJ)V");
        const auto result = env->NewObject(
            klass,
            constructor,
            data,
            static_cast<jlong>(snapshot->generation)
        );
        env->DeleteLocalRef(data);
        env->DeleteLocalRef(klass);
        return result;
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_core_NativeCoreBridge_acknowledgeBatteryRamSaved(
    JNIEnv* env,
    jclass,
    jlong handle,
    jlong generation
) {
    guarded_void(env, [&] {
        core_from(handle).acknowledge_battery_ram_saved(
            static_cast<std::uint64_t>(generation)
        );
    });
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_example_core_NativeCoreBridge_saveState(JNIEnv* env, jclass, jlong handle) {
    return guarded<jbyteArray>(env, nullptr, [&] {
        const auto state = core_from(handle).save_state();
        return make_byte_array(env, state);
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_core_NativeCoreBridge_loadState(
    JNIEnv* env,
    jclass,
    jlong handle,
    jbyteArray state
) {
    guarded_void(env, [&] {
        const auto bytes = read_bytes(env, state, false);
        core_from(handle).load_state(bytes);
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_core_NativeCoreBridge_setClockEpoch(
    JNIEnv* env,
    jclass,
    jlong handle,
    jboolean overridden,
    jlong epoch_seconds
) {
    guarded_void(env, [&] {
        core_from(handle).set_clock_epoch(
            overridden != JNI_FALSE
                ? std::optional<std::int64_t>{static_cast<std::int64_t>(epoch_seconds)}
                : std::nullopt
        );
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_core_NativeCoreBridge_gbaSaveType(JNIEnv* env, jclass, jlong handle) {
    return guarded<jint>(env, 0, [&] {
        return static_cast<jint>(core_from(handle).gba_save_type());
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_core_NativeCoreBridge_setGbaForcedSaveType(
    JNIEnv* env,
    jclass,
    jlong handle,
    jint forced_save_type
) {
    guarded_void(env, [&] {
        core_from(handle).set_gba_forced_save_type(gba_save_type_from(forced_save_type));
    });
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_core_NativeCoreBridge_gbaRtcActive(JNIEnv* env, jclass, jlong handle) {
    return guarded<jboolean>(env, JNI_FALSE, [&] {
        return static_cast<jboolean>(core_from(handle).gba_rtc_active() ? JNI_TRUE : JNI_FALSE);
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_core_NativeCoreBridge_setGbaForcedRtc(
    JNIEnv* env,
    jclass,
    jlong handle,
    jint forced_rtc
) {
    guarded_void(env, [&] {
        core_from(handle).set_gba_forced_rtc(forced_rtc_from(forced_rtc));
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_core_NativeCoreBridge_setMeasuringTime(
    JNIEnv* env,
    jclass,
    jlong handle,
    jboolean enabled
) {
    guarded_void(env, [&] {
        core_from(handle).set_measuring_time(enabled != JNI_FALSE);
    });
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_core_NativeCoreBridge_measuringTime(JNIEnv* env, jclass, jlong handle) {
    return guarded<jboolean>(env, JNI_FALSE, [&] {
        return static_cast<jboolean>(
            core_from(handle).measuring_time() ? JNI_TRUE : JNI_FALSE
        );
    });
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_example_core_NativeCoreBridge_debugSnapshot(JNIEnv* env, jclass, jlong handle) {
    return guarded<jobject>(env, nullptr, [&]() -> jobject {
        const auto snapshot = core_from(handle).debug_snapshot();
        if (!snapshot) return nullptr;
        const auto& value = *snapshot;
        // El orden de este arreglo es el contrato con `RavenGbaCore`: se
        // documenta allí, campo por campo, y no se reordena sin cambiar ambos.
        const std::array<std::int32_t, 39> scalars{
            value.instructions_per_frame, value.program_counter,
            value.thumb ? 1 : 0, value.halted ? 1 : 0,
            value.last_swi, value.last_interrupt_mask, value.vcount,
            value.last_dma_channel, value.dma_active ? 1 : 0,
            value.fifo_a_size, value.fifo_b_size,
            value.fifo_a_empty_reads, value.fifo_b_empty_reads,
            value.audio_underruns, value.unsupported_swi_count,
            value.undefined_instruction_count, value.unsupported_access_count,
            value.missing_interrupt_count, value.decompression_error_count,
            value.first_unsupported_address, value.dispcnt,
            value.bg0_control, value.bg1_control, value.bg2_control,
            value.bg3_control, value.blend_control, value.blend_alpha,
            value.blend_brightness, value.window_inside, value.window_outside,
            value.luma_min, value.luma_max, value.luma_mean,
            value.bg2_reference_x, value.bg2_reference_y,
            value.bg2_scale_x, value.bg2_scale_y,
            value.bg2_matrix_writes, value.bg2_reference_writes,
        };
        const auto scalar_array = make_int_array(env, scalars);
        const auto layer_array = make_int_array(env, value.layer_pixels);
        const auto swi_array = make_int_array(env, value.swi_counts);
        const auto timings = require_jvm(
            env->NewDoubleArray(3),
            "No se pudo reservar la fotografía de depuración"
        );
        const std::array<jdouble, 3> timing_values{
            value.ppu_millis,
            value.dma_millis,
            value.apu_millis,
        };
        env->SetDoubleArrayRegion(timings, 0, 3, timing_values.data());
        const auto klass = require_class(env, "com/example/core/NativeGbaDebugSnapshot");
        const auto constructor = env->GetMethodID(klass, "<init>", "([I[I[I[D)V");
        const auto result = env->NewObject(
            klass,
            constructor,
            scalar_array,
            layer_array,
            swi_array,
            timings
        );
        env->DeleteLocalRef(scalar_array);
        env->DeleteLocalRef(layer_array);
        env->DeleteLocalRef(swi_array);
        env->DeleteLocalRef(timings);
        env->DeleteLocalRef(klass);
        return result;
    });
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_example_core_NativeCoreBridge_drainDiagnostics(JNIEnv* env, jclass, jlong handle) {
    return guarded<jobject>(env, nullptr, [&]() -> jobject {
        const auto messages = core_from(handle).drain_diagnostics();
        std::vector<std::int32_t> event_values;
        event_values.reserve(messages.size());
        for (const auto& message : messages) {
            event_values.push_back(static_cast<std::int32_t>(message.event));
        }
        const auto events = make_int_array(env, event_values);
        const auto string_class = require_class(env, "java/lang/String");
        const auto details = require_jvm(
            env->NewObjectArray(checked_length(messages.size()), string_class, nullptr),
            "No se pudo reservar el lote de diagnóstico"
        );
        for (std::size_t index = 0; index < messages.size(); ++index) {
            const auto text = env->NewStringUTF(messages[index].detail.c_str());
            env->SetObjectArrayElement(details, static_cast<jsize>(index), text);
            env->DeleteLocalRef(text);
        }
        const auto klass = require_class(env, "com/example/core/NativeDiagnosticBatch");
        const auto constructor = env->GetMethodID(
            klass,
            "<init>",
            "([I[Ljava/lang/String;)V"
        );
        const auto result = env->NewObject(klass, constructor, events, details);
        env->DeleteLocalRef(events);
        env->DeleteLocalRef(details);
        env->DeleteLocalRef(string_class);
        env->DeleteLocalRef(klass);
        return result;
    });
}
