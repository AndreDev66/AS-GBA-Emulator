# Diseño del código nativo de AS GBA Emulator

Este documento explica **por qué** está hecho como está el árbol de C++ de la app
(`app/src/main/cpp/`), no qué hace cada función: eso está en los comentarios de
cada archivo. Está pensado para quien venga a tocar el núcleo —cheats, enlace por
cable, depurar un juego— sin tener que reconstruir el razonamiento leyendo mil
líneas.

Tres piezas viven aquí:

| Pieza | Ruta | Origen |
|---|---|---|
| Puente JNI | `asgba_jni.cpp` | Propio de AS GBA |
| Núcleo de emulación | `ravenemu/` | RavenEmu, vendorizado y ampliado por AS |
| Reglas de compilación | `CMakeLists.txt` | Propio de AS GBA |

El núcleo `ravenemu/` **es la base sobre la que se implementan las mejoras de
AS** (cheats, enlace local, estado de guardado…): se modifica cuando una
característica lo exige. Lo que no se toca por capricho es su contrato público,
`ravenemu/common/include/ravenemu/core.hpp`, porque es la única frontera que
separa el núcleo de Kotlin.

---

## 1. El flujo de un frame

```
Kotlin (GBACore.runFrame)
   └─▶ JNI: runFrame(jintArray, renderVideo)          asgba_jni.cpp
          └─▶ Core::run_frame(span<int32_t>, flag)    ravenemu (GbaCore)
                ├─ ppu.render_enabled = flag
                └─ Machine::run_frame()               ← bucle real
                      │
                      ├─ [1] DMA activo   → corren periféricos, el CPU espera
                      ├─ [2] CPU en halt  → avanza de 64 en 64 ciclos
                      ├─ [3] IRQ pendiente→ se levanta antes de step()
                      └─ [4] Normal       → cpu.step() y se cobran sus ciclos
                             │
                             advance_peripherals(ciclos)
                             ├─ Ppu::tick    → en HBlank dibuja el scanline
                             │                 (o no, si render_video=false)
                             ├─ Timers::tick → desbordes → IRQ y FIFO de audio
                             └─ Apu::tick    → CPU/4; cada 128 ciclos de audio
                                               emite una muestra al ring
```

Duración exacta de frame: `228 líneas × 1232 ciclos = 280 896` ciclos CPU a
16,78 MHz ⇒ **59,73 Hz**. De ahí `video_spec().refresh_rate_hz` en
`gba/src/core.cpp`.

### Invariantes que conviene no romper

1. **El framebuffer del core vive en la PPU** (`Ppu::frame`, `array<int32_t,
   240×160>`). El span que pasa Kotlin es un *destino*: `GbaCore::run_frame` lo
   rellena **sólo si hubo render**. En los frames saltados el buffer se queda
   como estaba, y eso es deliberado (ahorrar la copia es el ahorro del
   frame-skip).
2. **El frame-skip sólo apaga el dibujo**: con `render_video=false` siguen
   avanzando el contador de línea, los IRQ de HBlank/VBlank, las referencias
   afines de los fondos y los triggers de DMA. Si alguno de esos se apagara, los
   juegos que esperan un HBlank saltarían de comportamiento al reducir FPS.
   `render_enabled` se restaura a `true` incluso si el frame lanza excepción.
3. **El audio se genera dentro del emulador**, a 32768 Hz, en un ring buffer
   interno (4096 muestras estéreo ≈ 125 ms). El core **no bloquea, no hace
   callback y no reloja al host**: Kotlin llama a `read_audio()` cuando quiere y
   si la cola está vacía sube el contador de `underruns` (que se ve en el
   snapshot de depuración). No existe ninguna sincronización audio/vídeo en el
   núcleo: la cadencia la fija el lado Kotlin.
4. **La batería se exporta por generaciones**: `snapshot_battery_ram()` devuelve
   datos + número de generación, y `acknowledge_battery_ram_saved(g)` sólo
   limpia si coincide. Así un volcado a disco lento no puede borrar cambios
   hechos mientras se escribía.
5. **El DMA manda sobre la CPU**: mientras hay una transferencia en curso, el
   CPU no ejecuta instrucciones (rama 1 del bucle). Reordenar esas ramas cambia
   el timing visible para los juegos.

---

## 2. Mapa por subsistemas

Rutas bajo `ravenemu/gba/src/` salvo indicación.

| Subsistema | Archivos | Qué hay que saber |
|---|---|---|
| Ensamblador/planificador | `machine/machine.hpp` | Dueño de todo y del cableado; prioridad DMA > halt > IRQ > CPU |
| CPU | `cpu/cpu.{hpp,cpp}`, `cpu/cpu_state.hpp` | Sin pipeline real (PC+8/PC+4); ciclos por instrucción constantes + waitstates del bus; bancos de registros por `switch_mode` con 28 palabras exactas |
| Memoria | `memory/bus.{hpp,cpp}`, `memory/memory_timing.hpp` | El bus *contiene* la RAM; lecturas anchas por byte para reaplicar espejos; WAITCNT y "prefetch" simplificado en `memory_timing` |
| Vídeo | `ppu/ppu.hpp` | Render por scanline al entrar en HBlank; composición de capas en sólo dos campos (color y prioridad); framebuffer propio |
| Audio | `apu/apu.hpp` + canales `apu/*.hpp` | FIFO servidas por DMA; promedio entero sobre la ventana con una sola división; colas por canal |
| Cartucho y guardado | `cartridge/*`, `save/*` | Detección por marcadores alineados a 4; EEPROM 512 B/8 KiB según versión; promoción a SRAM en caliente |
| DMA | `dma/dma_controller.hpp` | Fases con coste temporal real, preempción de canales, suspensiones serializadas |
| Temporizadores | `timers/timers.hpp` | Evaluación perezosa; cascada (count-up) entre canales |
| Interrupciones | `interrupts/interrupt_controller.hpp` | `pending = master && (enable & flags)` |
| BIOS | `bios/bios.hpp` | HLE: trampolín de IRQ de 6 palabras + `handle_swi` |
| Teclado | `input/keypad.hpp` | Mapeo Button→bit; **sin KEYCNT ni IRQ de teclado** |
| Diagnóstico | `diagnostics/diagnostics.hpp` | Contadores por trama y máx. 8 mensajes por evento |
| Común | `ravenemu/common/` | Interfaz `Core`, enlace local, E/S binaria, SHA-256 |

---

## 3. Decisiones de diseño transversales

### 3.1 El bus es la memoria

Todas las regiones (BIOS, EWRAM, IWRAM, I/O, paleta, VRAM, OAM) son arrays
embebidos en `Bus`. Los subsistemas cuelgan como punteros **no propietarios**
(poseídos por `Machine`). Ventaja: los accesos de 16 y 32 bits se construyen
byte a byte, así que el espejo de VRAM y el enmascarado de ROM se aplican una
sola vez y no hay dos caminos que divergir.

### 3.2 Ciclos: quién los cuenta

- El **decodificador** devuelve un coste fijo por instrucción.
- El **bus** acumula los waitstates que los accesos de esa instrucción
  generaron; `Cpu::step()` los suma al salir.
- El **DMA** cobra sus propios waitstates en su fase de acceso y **vacia** los
  del bus para no contarlos dos veces.
- El scheduler (`advance_peripherals`) nunca avanza a ciegas: se detiene en el
  próximo evento (desbordamiento de timer o muestra de audio).

### 3.3 Sin open bus y sin ROM de BIOS

Dos desviaciones conscientes de la hardware, ambas documentadas en sus
archivos:

- Lecturas fuera del mapa devuelven `0` y se anotan en `diagnostics`, en vez de
  devolver el último valor leído.
- No se distribuye imagen de BIOS: seis palabras ARM escritas a mano hacen de
  trampolín de IRQ y los SWI se atienden en C++ (`handle_swi`). Un SWI no
  implementado genera diagnóstico en vez de ejecutar la rutina original.

### 3.4 Guardado de estado: un formato con contrato

`GbaCore::save_state` escribe magia `RVNS`, versión (escrita 11, aceptadas
9-10-11), id de consola y **SHA-256 de la ROM** como guard de identidad; luego
el estado en un orden fijo. Puntos no negociables:

- **El APU se serializa primero.** Si no, el estado describe una consola muda y
  el audio arranca a los pocos segundos. Está escrito en el código a propósito.
- `load_state` construye una **máquina nueva entera** y sólo la publica si todos
  los tamaños de campo cuadran: un estado truncado no degrada, lanza
  `SaveStateError`.
- Las versiones 9/10/11 añaden campos con condición (`apu`, ciclos pendientes,
  secuencialidad del bus). Al cargar un estado viejo se usan los repliegues
  correspondientes (`apu.replay_from_io`, bancos de CPU).

### 3.5 Arquitectura de compilación

- El núcleo GBA son **sólo tres unidades de traducción** (`core.cpp`, `cpu.cpp`,
  `bus.cpp`); PPU, APU, DMA, timers, BIOS y saves son *header-only* con
  `inline`. Compilar tres archivos a `O3` en lugar de treinta es la razón
  práctica del diseño, y también su coste: cambiar una cabecera recompila casi
  todo el núcleo.
- `asgba_optimize` aplica optimización a **todos** los targets, incluido Debug
  (`-O1`), porque un núcleo a `-O0` multiplica por tres el coste de cada
  fotograma.
- `-frtti -fexceptions` son **obligatorios**: el bus distingue `Eeprom`/`Sram`/
  `Flash` con `dynamic_cast` en el camino caliente de cada acceso a guardado.

### 3.6 Trucos: una lista es un programa, no una tabla

El punto que no es obvio al mirar un cheat de GameShark es que **una línea "if"
gobierna las líneas siguientes**, y cuántas depende del propio código: el
`repeat` de un rango viaja en los bits 16-23 del primer operando. Por eso el
camino completo está repartido en tres capas y ninguna basta sola.

| Capa | Dónde | Responsabilidad |
|---|---|---|
| Decodificación | `core/cheats/CheatParser.kt` | Texto → `CheatOp`, con la semántica de salto ya resuelta |
| Contrato | `asgba_jni.cpp`, `NativeCoreBridge.java` | `setCheats(long, int[])`; 5 enteros por operación |
| Aplicación | `GbaCore::set_cheats` / `apply_cheats` | Escribe por frame en los arrays del bus |

**El ordinal de `CheatKind` es un contrato de tres archivos**, igual que
`GbaDebugSnapshot`: el enum de Kotlin, la validación del JNI y el bucle de
`core.cpp` tienen que coincidir. No se reordena sin tocar los tres.

Dos decisiones del lado nativo que conviene no deshacer:

- **Se escribe directo sobre los arrays, no por `Bus::write`.** Pasar por el bus
  dispararía los efectos secundarios de los registros de E/S, que es justo lo
  que ningún truco de memoria quiere. El coste es tener que decidir a mano qué
  regiones cuentan como memoria respaldada (`cheat_write`).
- **`mark_modified()` en SRAM sólo si los bytes cambian.** Un truco que reescribe
  el mismo valor cada frame marcaría la partida guardada como sucia y forzaría
  una escritura a disco constante.

Y dos del lado de la decodificación, que son la razón de que el parseo esté
separado del núcleo:

- **Un error aborta la lista entera.** Aplicar el prefijo entendido dejaría
  escrituras sin la condición que las protegía. Por eso `CheatParseResult` lleva
  `accepted` y el llamador lo consulta.
- **Las condiciones se emiten aunque su dirección no sea válida.** El núcleo
  lee, falla y salta lo que deba: fallar cerrado. Omitirlas dejaría pasar las
  escrituras que dependían de ellas, que es lo contrario de lo que el código pide.
  Las escrituras, en cambio, sí se descartan (con aviso) si van a zona no
  escribible: sobrar una escritura no desalinea la estructura de control.

Formatos: CodeBreaker (8+4 hex) y GameShark (8+8, cifrado con TEA o en claro,
autodetectado con la misma puntuación que usa mGBA). Se rechazan con mensaje
claro lo que no se implementa en lugar de adivinar: **Action Replay v3** (su
formato es indistinguible del de GameShark salvo por puntuación, y un PAR mal
interpretado escribe en direcciones falsas) y los **reseeds** `DEADFACE` / tipo 9,
que cambian las semillas de las líneas siguientes.

El almacenamiento guarda **las líneas crudas**, no las operaciones ya
decodificadas (`CheatStorageCodec`). Con el texto a mano, una mejora futura del
decodificador —más formatos, mejores detecciones— se aplica sola la próxima vez
que se carga la lista.

---

## 4. Limitaciones conocidas (no son bugs, son decisiones)

| Limitación | Dónde | Consecuencia |
|---|---|---|
| Sin *open bus* | `memory/bus.cpp` | Lecturas fuera de mapa = 0 |
| BIOS por HLE | `bios/bios.hpp` | SWIs no listados ⇒ diagnóstico |
| Instrucción indefinida silenciosa | `cpu/cpu.cpp` | No levanta excepción; cuesta 1 ciclo |
| Prefetch simplificado | `memory/memory_timing.hpp` | WAITCNT bit 14 ⇒ lectura secuencial de código a 0 ciclos; no hay búfer real |
| Sin KEYCNT | `input/keypad.hpp` | Ninguna IRQ de teclado |
| Mosaico de objetos anclado al sprite | `ppu/ppu.hpp` | Difiere del hardware en desplazamiento |
| Composición "simple" como atajo | `ppu/ppu.hpp` | Se salta el blend si `BLDCNT & 0x3fc0 == 0` |
| Sin sincronización audio/vídeo | todo el núcleo | La cadencia la pone Kotlin |
| `Cartridge::read8` fuera de ROM = 0 | `cartridge/cartridge.hpp` | Sin espejos de ROM |

---

## 5. Puntos de enganche para características nuevas

- **Trucos (GameShark/CodeBreaker).** Hecho, ver §3.6. Queda pendiente **Action
  Replay v3**: el `CheatParser` ya lo detecta y lo rechaza, así que encaja donde
  el resto de `parScore`, como un `GsaCandidate` más. Su semántica es más
  rica (bloques `endif`/`else`, escrituras indirectas, parches con continuation),
  así que no es un caso más del `when`.
- **Enlace por cable.** `ravenemu/common/include/ravenemu/link_endpoint.hpp`
  ya trae `LocalLinkEndpoint` (hasta 2 máquinas en proceso), cableable con
  `Core::connect_link_endpoint`. Falta exponerlo por JNI y darle UI.
- **Selector de renderer.** El píxel sale del span JNI hacia un `Bitmap` que
  `ScreenRenderer.kt` pinta en un `Canvas` de Compose. No existe hoy ninguna
  ruta OpenGL/Vulkan: hay que construirla, no migrarla.
- **Estado y batería.** Usar el protocolo de generaciones de §1.4 en vez de
  volcar a disco cada vez que el core marca sucio.

---

## 6. Artefactos que hay que conocer

Hechos verificados por lectura; conviene no redescubrirlos a golpes:

1. `ravenemu/gba/include/ravenemu/gba/core.hpp` **no compila si se incluye**:
   `make_core()` llama a `make_gba_core()` sin el argumento obligatorio. Está
   deliberadamente fuera del build y así lo advierte `asgba_jni.cpp`.
2. `make_game_boy_core()` está **declarada pero no definida** en todo el árbol:
   cualquier uso fallaría al enlazar.
3. El constructor `Bus::Bus(Cartridge&)` está definido en **`cpu.cpp`**, no en
   `bus.cpp`.
4. `ravenemu/common/src/core.cpp` no contiene las fábricas: sólo un comentario
   que explica que viven junto a su implementación concreta.
5. Los mensajes de excepción y diagnóstico del núcleo están en **francés**
   (herencia de RavenEmu) mientras que los comentarios de AS están en
   **español**. No unificar por ahora: tocar los textos afectaría a quien
   busque esos mensajes tal cual.

---

## 7. Verificación

No hay test de C++ en el proyecto. Lo que sí se puede comprobar:

```bat
gradlew.bat :app:testDebugUnitTest      :: Kotlin (GBACore, TouchLayout, etc.)
gradlew.bat :app:assembleDebug          :: incluye la compilación nativa vía CMake
```

Si la compilación nativa falla, el culpable casi siempre es una cabecera
*header-only* tocada sin darse cuenta de que recompila media app: mirar primero
`ppu.hpp`, `apu.hpp` y `dma_controller.hpp`.
