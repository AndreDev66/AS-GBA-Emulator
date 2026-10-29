# CMakeLists.txt — AS GBA Emulator
#
# Compila la biblioteca nativa `asgba`, que es todo el motor de emulación de la
# aplicación. Dentro hay dos motores que no comparten ni una línea, más el puente
# que los expone:
#
#   * RavenEmu, bajo `ravenemu/`: Game Boy Advance y Game Boy / Game Boy Color.
#     Es el motor original de la aplicación.
#   * melonDS, bajo `melonds/`: Nintendo DS. Es un proyecto ajeno, GPL-3.0,
#     vendorizado aquí; `melonds/ORIGEN.md` dice de dónde salió exactamente, qué
#     commits y qué directorios se dejaron fuera.
#   * El puente: `asgba_jni.cpp`, que traduce los motores a la frontera
#     Java/Kotlin, `link/`, con el transporte TCP del enlace LAN, y
#     `melonds_adapter/`, que es la traducción del núcleo de melonDS y la
#     implementación de su capa `Platform`.
#
# Cada motor tiene su propio estándar de C++ y su propia frontera JNI. Conviene
# saber por qué antes de tocar nada:
#
#   * RavenEmu está escrito en C++20 y melonds en C++17. Mezclarlos en una misma
#     unidad de traducción rompe cosas como la comparación de operadores con
#     sobrecarga, así que cada motor se compila con el suyo y se comunican a
#     través de encabezados que no incluyen los tipos del otro.
#   * Game Boy y GBA viajan por `NativeCoreBridge`, que convierte el
#     identificador de núcleo directamente en un `ravenemu::Core*`. Nintendo DS
#     va por `MelonDsCoreBridge`, aparte, y Kotlin elige según la consola.
#
# Base: RavenEmu y melonDS. Interfaz y fachada: AS GBA Emulator, de Andrés
# Socorro.

cmake_minimum_required(VERSION 3.22.1)

project("asgba" LANGUAGES C CXX)

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
#
# Debug va a -O3 y no a -O1: melonDS pasa la mayor parte del fotograma en el
# renderizador 3D por software, y cada escalón de optimización se nota en el
# contador de FPS. Los símbolos siguen ahí (-g lo pone CMake en Debug), así que
# perfilar y depurar con este APK sigue siendo posible.
function(asgba_optimize target)
    target_compile_options(${target} PRIVATE
        $<$<CONFIG:Release>:-O3>
        $<$<CONFIG:RelWithDebInfo>:-O2>
        $<$<CONFIG:Debug>:-O3>
        -fomit-frame-pointer
    )
endfunction()

# ---------------------------------------------------------------------------
# Motor RavenEmu — utilidades comunes y núcleos Game Boy / Game Boy Advance
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

# Núcleo Game Boy / Game Boy Color: una sola implementación cuyo modo de
# hardware (DMG o CGB) lo decide la cabecera del cartucho cargado, de modo que
# `.gb` y `.gbc` comparten este objetivo. Casi todo vive en encabezados
# internos bajo `gb/src/`, igual que en el núcleo GBA; `core.cpp` es la única
# unidad de traducción y contiene la fábrica `make_game_boy_core()`.
#
# `gbc/include` lleva los componentes CGB ya extraídos (KEY1 y puerto
# infrarrojos, que es donde RavenEmu los separó de la implementación
# unificada): sólo los encabezados internos de `gb/src/` los incluyen, de
# modo que ambos directorios son privados del objetivo, como en RavenEmu.
add_library(gb_raven_core STATIC
    ${RAVENEMU_ROOT}/gb/src/core.cpp
)
target_include_directories(gb_raven_core
    PUBLIC ${RAVENEMU_ROOT}/gb/include
    PRIVATE ${RAVENEMU_ROOT}/gb/src
    PRIVATE ${RAVENEMU_ROOT}/gbc/include
)
target_link_libraries(gb_raven_core PUBLIC ravenemu_common)
set_target_properties(gb_raven_core PROPERTIES POSITION_INDEPENDENT_CODE ON)
asgba_optimize(gb_raven_core)

# ---------------------------------------------------------------------------
# Núcleo melonDS — Nintendo DS
# ---------------------------------------------------------------------------
#
# melonDS llega como un repositorio propio con su propio sistema de build (CMake
# con vcpkg, frontend Qt/SDL y detección de plataforma de escritorio). AS no
# puede consumirse así: aquí se compila el **núcleo** directamente, como se hizo
# con RavenEmu, y se deja fuera todo lo que no se usa.
#
# Lo que se deja fuera es casi todo el peso del proyecto original:
#   * el render por OpenGL (`ENABLE_OGLRENDERER=OFF`). El núcleo mantiene sus
#     búferes de pantalla en CPU, así que AS los copia tal cual, sin EGL.
#   * el stub de GDB (`ENABLE_GDBSTUB=OFF`): es una herramienta de desarrollo.
#   * el frontend Qt/SDL y `src/net` (enet, libcurl, libslirp, libpcap): AS
#     enlaza el juego por otra vía y el wifi de melonDS queda desactivado.
#   * faad2 y oboe: el audio sale por `SPU::ReadOutput()` y lo reproduce el
#     `AndroidAudioSink` que AS ya usa para Game Boy y GBA.
#
# Se conservan, en cambio, el JIT (es lo que hace aceptable el ARM9 en un
# móvil) y teakra, el intérprete del DSP de DSi, que el núcleo enlaza siempre.
#
# Base: melonDS, https://github.com/melonDS-emu/melonDS — GPL-3.0. Sus avisos de
# licencia originales se conservan intactos en cada fichero.

set(MELONDS_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/melonds")
set(MELONDS_TEAKRA "${MELONDS_ROOT}/teakra")

# El JIT sólo existe para ARM64 y x86_64; en 32 bits el núcleo cae al
# intérprete, que funciona pero multiplica la carga del ARM9.
set(MELONDS_JIT OFF)
if (ANDROID_ABI STREQUAL "arm64-v8a" OR ANDROID_ABI STREQUAL "x86_64")
    set(MELONDS_JIT ON)
endif()

if (MELONDS_JIT)
    enable_language(ASM)
endif()

add_library(melonds_core STATIC
    ${MELONDS_ROOT}/ARCodeFile.cpp
    ${MELONDS_ROOT}/ARDatabaseDAT.cpp
    ${MELONDS_ROOT}/AREngine.cpp
    ${MELONDS_ROOT}/ARM.cpp
    ${MELONDS_ROOT}/ARMInterpreter.cpp
    ${MELONDS_ROOT}/ARMInterpreter_ALU.cpp
    ${MELONDS_ROOT}/ARMInterpreter_Branch.cpp
    ${MELONDS_ROOT}/ARMInterpreter_LoadStore.cpp
    ${MELONDS_ROOT}/CP15.cpp
    ${MELONDS_ROOT}/CRC32.cpp
    ${MELONDS_ROOT}/DMA.cpp
    ${MELONDS_ROOT}/DMA_Timings.cpp
    ${MELONDS_ROOT}/DSi.cpp
    ${MELONDS_ROOT}/DSi_AES.cpp
    ${MELONDS_ROOT}/DSi_Camera.cpp
    ${MELONDS_ROOT}/DSi_DSP.cpp
    ${MELONDS_ROOT}/DSi_I2C.cpp
    ${MELONDS_ROOT}/DSi_I2S.cpp
    ${MELONDS_ROOT}/DSi_NAND.cpp
    ${MELONDS_ROOT}/DSi_NDMA.cpp
    ${MELONDS_ROOT}/DSi_NWifi.cpp
    ${MELONDS_ROOT}/DSi_SD.cpp
    ${MELONDS_ROOT}/DSi_SPI_TSC.cpp
    ${MELONDS_ROOT}/FATIO.cpp
    ${MELONDS_ROOT}/FATStorage.cpp
    ${MELONDS_ROOT}/FreeBIOS.cpp
    ${MELONDS_ROOT}/GBACart.cpp
    ${MELONDS_ROOT}/GBACartMotionPak.cpp
    ${MELONDS_ROOT}/GPU.cpp
    ${MELONDS_ROOT}/GPU2D.cpp
    ${MELONDS_ROOT}/GPU2D_Soft.cpp
    ${MELONDS_ROOT}/GPU3D.cpp
    ${MELONDS_ROOT}/GPU3D_Soft.cpp
    ${MELONDS_ROOT}/GPU3D_Texcache.cpp
    ${MELONDS_ROOT}/Mic.cpp
    ${MELONDS_ROOT}/NDS.cpp
    ${MELONDS_ROOT}/NDSCart.cpp
    ${MELONDS_ROOT}/NDSCartR4.cpp
    ${MELONDS_ROOT}/RTC.cpp
    ${MELONDS_ROOT}/ROMList.cpp
    ${MELONDS_ROOT}/Savestate.cpp
    ${MELONDS_ROOT}/SPI.cpp
    ${MELONDS_ROOT}/SPI_Firmware.cpp
    ${MELONDS_ROOT}/SPU.cpp
    ${MELONDS_ROOT}/Utils.cpp
    ${MELONDS_ROOT}/Wifi.cpp
    ${MELONDS_ROOT}/WifiAP.cpp

    ${MELONDS_ROOT}/DSP_HLE/UcodeBase.cpp
    ${MELONDS_ROOT}/DSP_HLE/AACUcode.cpp
    ${MELONDS_ROOT}/DSP_HLE/G711Ucode.cpp
    ${MELONDS_ROOT}/DSP_HLE/GraphicsUcode.cpp

    ${MELONDS_ROOT}/fatfs/ff.c
    ${MELONDS_ROOT}/fatfs/ffsystem.c
    ${MELONDS_ROOT}/fatfs/ffunicode.c
    ${MELONDS_ROOT}/sha1/sha1.c
    ${MELONDS_ROOT}/tiny-AES-c/aes.c
    ${MELONDS_ROOT}/xxhash/xxhash.c
    ${MELONDS_ROOT}/blip-buf/blip_buf.c

    # teakra: el intérprete del DSP de DSi. melonDS lo enlaza siempre, aunque
    # AS no emule DSi todavía; omitirlo rompe el enlace.
    ${MELONDS_TEAKRA}/src/ahbm.cpp
    ${MELONDS_TEAKRA}/src/apbp.cpp
    ${MELONDS_TEAKRA}/src/btdmp.cpp
    ${MELONDS_TEAKRA}/src/disassembler.cpp
    ${MELONDS_TEAKRA}/src/dma.cpp
    ${MELONDS_TEAKRA}/src/memory_interface.cpp
    ${MELONDS_TEAKRA}/src/mmio.cpp
    ${MELONDS_TEAKRA}/src/parser.cpp
    ${MELONDS_TEAKRA}/src/processor.cpp
    ${MELONDS_TEAKRA}/src/timer.cpp
    ${MELONDS_TEAKRA}/src/teakra.cpp
)

if (MELONDS_JIT)
    target_sources(melonds_core PRIVATE
        ${MELONDS_ROOT}/ARM_InstrInfo.cpp
        ${MELONDS_ROOT}/ARMJIT.cpp
        ${MELONDS_ROOT}/ARMJIT_Global.cpp
        ${MELONDS_ROOT}/ARMJIT_Memory.cpp
        ${MELONDS_ROOT}/dolphin/CommonFuncs.cpp
    )
    if (ANDROID_ABI STREQUAL "arm64-v8a")
        target_sources(melonds_core PRIVATE
            ${MELONDS_ROOT}/dolphin/Arm64Emitter.cpp
            ${MELONDS_ROOT}/dolphin/MathUtil.cpp
            ${MELONDS_ROOT}/ARMJIT_A64/ARMJIT_ALU.cpp
            ${MELONDS_ROOT}/ARMJIT_A64/ARMJIT_Branch.cpp
            ${MELONDS_ROOT}/ARMJIT_A64/ARMJIT_Compiler.cpp
            ${MELONDS_ROOT}/ARMJIT_A64/ARMJIT_LoadStore.cpp
            ${MELONDS_ROOT}/ARMJIT_A64/ARMJIT_Linkage.S
        )
    else()
        target_sources(melonds_core PRIVATE
            ${MELONDS_ROOT}/dolphin/x64ABI.cpp
            ${MELONDS_ROOT}/dolphin/x64CPUDetect.cpp
            ${MELONDS_ROOT}/dolphin/x64Emitter.cpp
            ${MELONDS_ROOT}/ARMJIT_x64/ARMJIT_ALU.cpp
            ${MELONDS_ROOT}/ARMJIT_x64/ARMJIT_Branch.cpp
            ${MELONDS_ROOT}/ARMJIT_x64/ARMJIT_Compiler.cpp
            ${MELONDS_ROOT}/ARMJIT_x64/ARMJIT_LoadStore.cpp
            ${MELONDS_ROOT}/ARMJIT_x64/ARMJIT_Linkage.S
        )
    endif()
    target_compile_definitions(melonds_core PUBLIC JIT_ENABLED)
endif()

# `version.h` lo genera el propio melonDS desde una plantilla; se reproduce aquí
# con los mismos valores para que el núcleo pueda mostrar su versión en el log.
# El nombre no es libre: `NDS.cpp` lo incluye literalmente como "version.h".
set(melonDS_VERSION "1.1")
set(melonDS_HOMEPAGE_URL "https://melonds.kuribo64.net")
set(MELONDS_VERSION_SUFFIX "")
configure_file("${MELONDS_ROOT}/version.h.in" "${CMAKE_CURRENT_BINARY_DIR}/version.h")

target_include_directories(melonds_core
    PUBLIC ${MELONDS_ROOT}
    PUBLIC ${CMAKE_CURRENT_BINARY_DIR}
    PRIVATE ${MELONDS_TEAKRA}/include
    PRIVATE ${MELONDS_TEAKRA}/src
)

# melonDS está escrito para C++17 y así lo exige su CMake; el proyecto, en
# cambio, usa C++20. Mezclar ambos en una unidad de traducción rompe cosas como
# la comparación de overloaded, así que la frontera se mantiene en archivos
# separados: el núcleo y el adaptador son C++17, `asgba_jni.cpp` sigue en C++20
# y sólo los ve a través de `melonds_adapter.h`, que no incluye cabeceras de
# melonDS.
set_target_properties(melonds_core PROPERTIES
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED ON
    CXX_EXTENSIONS OFF
    POSITION_INDEPENDENT_CODE ON
)

# El núcleo depende de la semántica de desbordamiento con signo (lo dice el
# emulador en varios puntos del bus y del SPU), y compila limpio con este
# aviso desactivado porque sólo lo dispara el código no portable del JIT.
target_compile_options(melonds_core PRIVATE
    -fwrapv
    -Wno-invalid-offsetof
)

asgba_optimize(melonds_core)

# El adaptador: la capa `Platform` que el núcleo exige por enlace y el envoltorio
# que traduce melonDS a los tipos que el puente JNI de AS ya conoce.
add_library(melonds_adapter STATIC
    melonds_adapter/melonds_platform.cpp
    melonds_adapter/melonds_adapter.cpp
)
target_include_directories(melonds_adapter
    PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/melonds_adapter
)
target_link_libraries(melonds_adapter PUBLIC melonds_core)
set_target_properties(melonds_adapter PROPERTIES
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED ON
    CXX_EXTENSIONS OFF
    POSITION_INDEPENDENT_CODE ON
)
asgba_optimize(melonds_adapter)

# Las funciones JNI sólo se resuelven desde Java y no tienen referencias C++
# que hagan que el enlazador extraiga su objeto desde una biblioteca estática.
# Compilarlo como objeto e incluirlo directamente en `asgba` garantiza que los
# símbolos `Java_com_example_core_MelonDsCoreBridge_*` queden exportados.
add_library(melonds_jni OBJECT
    melonds_adapter/melonds_jni.cpp
)
target_include_directories(melonds_jni PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/melonds_adapter
)
set_target_properties(melonds_jni PROPERTIES
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED ON
    CXX_EXTENSIONS OFF
    POSITION_INDEPENDENT_CODE ON
)
asgba_optimize(melonds_jni)

find_library(log-lib log)
find_library(android-lib android)
find_library(dl-lib dl)

target_link_libraries(melonds_adapter PUBLIC ${log-lib} ${dl-lib})

# ---------------------------------------------------------------------------
# Biblioteca compartida de AS GBA Emulator
# ---------------------------------------------------------------------------

add_library(asgba SHARED
    asgba_jni.cpp
    link/network_link_endpoint.cpp
    $<TARGET_OBJECTS:melonds_jni>
)

target_link_libraries(asgba PRIVATE
    gba_raven_core gb_raven_core ravenemu_common melonds_adapter
)

# El ritmo de emulación lo fija la sesión de Kotlin, no el compilador: -O3 basta
# y, como CMake añade -g en Debug, perfilar con símbolos sigue siendo posible.
asgba_optimize(asgba)

target_link_libraries(asgba PRIVATE ${android-lib})
