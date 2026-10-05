# AS GBA Emulator

Emulador de Game Boy Advance para Android, con interfaz en Compose Material 3 y
núcleo de emulación nativo en C++20.

## Qué es

AS GBA Emulator carga una ROM de GBA y la ejecuta en un hilo próprio, con el
sonido sincronizado contra el reloj de audio del sistema. Todo lo que ocurre dentro
de la consola (CPU, PPU, APU, temporizadores, DMA, cartucho y memoria de guardado)
lo hace el núcleo nativo; la capa de Android se ocupa de la interfaz, la biblioteca
de ROM y las salvaguardas.

Admite ROM estándar y hackroms de 32 y 64 MiB.

## Requisitos

- Android Studio con el NDK **28.2.13676358** y CMake **3.22.1** instalados.
- JDK 11 o superior.
- Un dispositivo o emulador con **Android 7.0 (API 24)** o posterior.

El proyecto no incluye los scripts `gradlew`, así que hace falta tener Gradle 9.3.1
disponible en el sistema.

## Compilar y ejecutar

1. Abre el proyecto en Android Studio.
2. Deja que Gradle sincronice y compilar el módulo `:app`.
3. Ejecuta la configuración `app` en un dispositivo o emulador.

Desde línea de órdenes:

```
gradle :app:assembleDebug
```

El APK resultante queda en `app/build/outputs/apk/debug/app-debug.apk`.

## Controles

**En pantalla.** Cruceta, A, B, Start, Select, L y R. Se pueden mover y cambiar de
tamaño con el editor de la pantalla de juego; el reparto se guarda aparte para
horizontal y para vertical, de modo que cambiar la orientación no restaura una
disposición ajustada a la otra.

**Mando.** Se reconocen los mandos Bluetooth y los conectados por cable. Los botones
se reasignan desde Ajustes, y la palanca izquierda y los gatillos se activan o
desactivan por separado. Ninguna de las dos vías es obligatoria.

**Menú rápido.** Durante la partida, el botón de menú abre una hoja inferior con las
partidas guardadas, los ajustes, la reasignación de botones y la salida.

## Partidas y guardado

El tipo de memoria de la.partida se deduce de la propia ROM, no de su tamaño. Un
cartucho puede medir 16 MiB exactos y ser EEPROM de 8 KiB, así que la detección usa
los marcadores que deja el SDK y sólo recurre a una heurística cuando la ROM no
trae ninguno. Un tipo incorrecto hace que el juego rechace su propia partida como
corrupta, así que la detección manda sobre cualquier valor forzado.

Los tipos que se reconocen son Flash (64 y 128 KiB), SRAM y EEPROM (512 B y 8 KiB).

## Arquitectura

```
app/src/main/java/com/example/     Interfaz y lógica de la aplicación
app/src/main/cpp/ravenemu/        Núcleo de emulación (C++20, de terceros)
app/src/main/cpp/asgba_jni.cpp    Puente JNI entre Kotlin y el núcleo
```

`com.example.core.GBACore` es la fachada: posee el hilo de emulación, el framebuffer
compartido y la salida de audio, y serializa el acceso al núcleo entre ese hilo y el
de la interfaz. Todo lo que llama a la API nativa pasa por ahí.

## Estado

Es una aplicación en desarrollo. Funciona la biblioteca de ROM, la carga, la
ejecución, el sonido, las salvaguardas y los mandos; la interfaz puede cambiar de
aspecto según lo que se vaya añadiendo.

## Créditos

AS GBA Emulator lo desarrolla **Andrés Socorro**. La emulación de Game Boy Advance la
realiza el núcleo C++20 del proyecto **RavenEmu**, tomado como base e integrado
mediante JNI (`app/src/main/cpp/ravenemu` y `app/src/main/cpp/asgba_jni.cpp`). El
código de RavenEmu se conserva sin modificaciones; la interfaz, la gestión de ROM y
salvaguardas, y la fachada `com.example.core` pertenecen a AS GBA Emulator.

## Licencia

Todavía no se ha definido. Antes de publicar el proyecto se añadirán un `LICENSE`
propio y un `NOTICE` con la atribución de RavenEmu y su licencia, que está por
confirmar.