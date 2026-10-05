# AS GBA Emulator

> **AS GBA Emulator** es un emulador de Game Boy Advance de código abierto para Android, desarrollado con una interfaz moderna en **Jetpack Compose (Material 3)** e impulsado por un núcleo nativo en **C++20**.

---

## ⚡ Características Principales

- **Núcleo nativo de alta precisión:** Ejecución del sistema GBA (CPU, PPU, APU, temporizadores, DMA, cartucho y memoria de guardado) en C++20 sobre un hilo dedicado.
- **Sincronización de audio:** Sonido ajustado directamente al reloj de audio del sistema para evitar *tearing* o desfases.
- **Soporte amplio de ROMs:** Compatible con ROMs estándar y *hackroms* de 32 MiB y 64 MiB.
- **Detección inteligente de guardado:** Identificación precisa del tipo de memoria (Flash 64/128 KiB, SRAM y EEPROM 512 B/8 KiB) inspeccionando los marcadores del SDK oficial, evitando la corrupción de datos sin depender de falsas heurísticas basadas en el tamaño del archivo.
- **Mapeo de controles versátil:**
  - **En pantalla:** Cruceta, A, B, Start, Select, L y R. Controles redimensionables y reubicables con guardado de plantilla independiente para modo vertical (*portrait*) y horizontal (*landscape*).
  - **Mandos físicos:** Compatibilidad total con mandos Bluetooth y USB OTG con reasignación completa de botones, gatillos y palanca analógica izquierda.
- **Menú Rápido (In-Game):** *Bottom sheet* intuitivo para gestión de partidas guardadas, ajustes rápidos y mapeo de controles sin interrumpir la sesión.

---

## 🛠️ Requisitos del Sistema

- **Android Studio** con las siguientes herramientas instaladas:
  - **NDK:** `28.2.13676358`
  - **CMake:** `3.22.1`
- **JDK:** 11 o superior.
- **Gradle:** 9.3.1 disponible en el entorno de ejecución (el proyecto no incluye el wrapper `gradlew`).
- **Dispositivo objetivo:** Android 7.0 (API Nivel 24) o superior.

---

## 📦 Compilación e Instalación

### Desde Android Studio
1. Clona el repositorio e importa el proyecto en Android Studio.
2. Sincroniza los archivos de Gradle.
3. Ejecuta el módulo `:app` en un dispositivo físico o emulador.

### Desde la línea de comandos
Puedes generar el ejecutable Debug corriendo el siguiente comando:

```bash
gradle :app:assembleDebug
