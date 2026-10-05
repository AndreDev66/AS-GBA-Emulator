# CMakeLists.txt — AS GBA Emulator
#
# Compila la biblioteca nativa `asgba`: el puente JNI de AS GBA Emulator más el
# motor de emulación Game Boy Advance de RavenEmu, tomado como base.
#
# Todo lo que hay bajo `ravenemu/` es RavenEmu y no se modifica desde AS; el
# único archivo propio del proyecto es `asgba_jni.cpp`, que traduce ese motor a
# la frontera Java/Kotlin.
#
# Base: RavenEmu. Interfaz y fachada: AS GBA Emulator, de Andrés Socorro.

cmake_minimum_required(VERSION 3.22.1)

project("asgba" LANGUAGES CXX)

# RavenEmu está escrito en C++20: `std::span`, `std::numbers` y `std::bit_cast`
# aparecen en el núcleo. `CMAKE_CXX_STANDARD_REQUIRED` impide que la
# configuración caiga silenciosamente a una revisión anterior.
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# RavenEmu necesita RTTI y excepciones. El RTTI no es opcional: el bus separa
# EEPROM, SRAM y Flash con `dynamic_cast` sobre el tipo de memoria de cartucho,
# y `save_state` lanza `SaveStateError` a propósito para que el puente lo
# traduzca a una excepción Java. Desactivar cualquiera de los dos rompe el
# núcleo, así que se dejan los valores predeterminados explícitamente.
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -frtti -fexceptions")

set(RAVENEMU_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/ravenemu")

# El escalón de optimización se aplica a **todas** las piezas, no sólo al puente
# JNI. `Debug` deja los flags que CMake pone por omisión, que para C++ no
# incluyen ninguna `-O`: sin esto el núcleo se compila a -O0 y cada fotograma
# cuesta del orden de tres veces más, con el juego por debajo de 60 FPS.
function(asgba_optimize target)
    target_compile_options(${target} PRIVATE
        $<$<CONFIG:Release>:-O3>
        $<$<CONFIG:RelWithDebInfo>:-O2>
        $<$<CONFIG:Debug>:-O1>
        -fomit-frame-pointer
    )
endfunction()

# ---------------------------------------------------------------------------
# Motor RavenEmu — núcleo Game Boy Advance y utilidades comunes
# ---------------------------------------------------------------------------

add_library(ravenemu_common STATIC
    ${RAVENEMU_ROOT}/common/src/core.cpp
    ${RAVENEMU_ROOT}/common/src/sha256.cpp
)
target_include_directories(ravenemu_common PUBLIC ${RAVENEMU_ROOT}/common/include)
set_target_properties(ravenemu_common PROPERTIES POSITION_INDEPENDENT_CODE ON)

add_library(gba_raven_core STATIC
    ${RAVENEMU_ROOT}/gba/src/core.cpp
    ${RAVENEMU_ROOT}/gba/src/cpu/cpu.cpp
    ${RAVENEMU_ROOT}/gba/src/memory/bus.cpp
)
# `include` es la raíz pública; `src` contiene los encabezados internos del
# núcleo, a los que éste llega por ruta relativa a esa raíz.
target_include_directories(gba_raven_core
    PUBLIC ${RAVENEMU_ROOT}/gba/include
    PUBLIC ${RAVENEMU_ROOT}/gba/src
)
target_link_libraries(gba_raven_core PUBLIC ravenemu_common)
set_target_properties(gba_raven_core PROPERTIES POSITION_INDEPENDENT_CODE ON)
asgba_optimize(gba_raven_core)
asgba_optimize(ravenemu_common)

# ---------------------------------------------------------------------------
# Biblioteca compartida de AS GBA Emulator
# ---------------------------------------------------------------------------

add_library(asgba SHARED asgba_jni.cpp)

target_link_libraries(asgba PRIVATE gba_raven_core ravenemu_common)

# El ritmo de emulación lo fija la sesión de Kotlin, no el compilador: -O3 en
# Release y -O1 en Debug bastan y dejan el perfilado con símbolos.
asgba_optimize(asgba)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(asgba PRIVATE ${log-lib} ${android-lib})
