# Procedencia de este directorio

Este árbol es el **núcleo de melonDS**, copiado dentro del proyecto para poder
compilarlo desde el `CMakeLists.txt` de AS. No es código de AS y no se ha
tocado: los avisos de licencia originales siguen en cada fichero.

## Qué es

- Proyecto: melonDS, https://github.com/melonDS-emu/melonDS
- Fork usado: https://github.com/rafaelvcaetano/melonDS-android-lib
- Versión declarada en su `CMakeLists.txt`: 1.1
- Commit: `431ab4bd0003c4356e25fce89640ae1004579b9b`
- Licencia: **GPL-3.0**. La copia íntegra está en `LICENSE`.

Ese commit es exactamente el que fija el submódulo de
`rafaelvcaetano/melonDS-android@master`, y es la razón por la que ASGBA no usa
el melonDS oficial: el fork trae el núcleo con los cambios de CMake necesarios
para compilar en Android, que es lo que AS necesita y el de arriba no.

## Cómo se obtuvo

```
git clone https://github.com/rafaelvcaetano/melonDS-android-lib.git
git checkout 431ab4bd0003c4356e25fce89640ae1004579b9b
```

De ese clon se copió `src/` entero más `LICENSE`, y se descartaron tres
directorios que AS no compila: `src/frontend` (el Qt/SDL de escritorio),
`src/net` (enet, libcurl, libslirp, libpcap: el netplay) y `src/debug` (el stub
de GDB). No se copiaron porque no se usan, y no porque fallen.

## Por qué no se usa el sistema de build de melonDS

El suyo (`CMakeLists.txt` de la raíz) trae vcpkg, detección de plataforma de
escritorio, el frontend Qt/SDL y una lista de opciones que aquí no aplican. AS
compila el target `core` directamente desde su propio `CMakeLists.txt`, en
`cpp/CMakeLists.txt`, donde se ve exactamente qué ficheros entran.

Consecuencias que conviene tener presentes al tocar esto:

- **Sin render por OpenGL.** `ENABLE_OGLRENDERER=OFF`. El núcleo mantiene sus
  búferes de pantalla en CPU, y AS los copia a su framebuffer sin EGL.
- **Sin stub de GDB.** Es una herramienta de desarrollo.
- **El JIT solo en ARM64 y x86_64.** En 32 bits melonDS cae al intérprete.
- **`version.h` se genera** en el build de AS a partir de `version.h.in`, con el
  nombre que `NDS.cpp` incluye literalmente.

## Licencia: qué implica para AS GBA Emulator

melonDS es GPL-3.0 y ASGBA lo usa como biblioteca. ASGBA pasa por tanto a ser
una **obra derivada**, y debe distribuirse también bajo GPL-3.0:

- El código fuente completo de ASGBA, incluido este directorio.
- Los avisos de licencia de melonDS intactos (ya están en cada fichero y en
  `LICENSE`).
- El texto de la GPL-3.0, y la fuente original (melonds.kuribo64.net y el
  GitHub de melonDS).

Esto no es un aviso teórico: mientras ASGBA se distribuye cerrado, incumple.

## Si hay que volver a obtenerlo

Basta con los dos comandos de arriba y volver a copiar `src/` y `LICENSE`. El
commit está anotado aquí precisamente para que el origen no dependa de que
nadie conserve una copia local.
