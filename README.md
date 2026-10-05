<div align="center">
  <img src="./assets/logo.png" alt="AS GBA Emulator Logo" width="180">
  <h1>AS GBA Emulator</h1>
  <p><strong>Emulador de Game Boy Advance para Android con núcleo nativo C++20 e interfaz Material 3.</strong></p>

  <p>
    <img src="https://img.shields.io/badge/Language-Kotlin%20%7C%20C%2B%2B20-blue?style=for-the-badge&logo=kotlin" alt="Language">
    <img src="https://img.shields.io/badge/Platform-Android%207.0%2B-green?style=for-the-badge&logo=android" alt="Platform">
    <img src="https://img.shields.io/badge/UI-Jetpack%20Compose%20M3-3DDC84?style=for-the-badge&logo=android" alt="UI">
    <img src="https://img.shields.io/badge/Status-Active-brightgreen?style=for-the-badge" alt="Status">
  </p>

  <p>
    <a href="#-descripción">Descripción</a> •
    <a href="#-características">Características</a> •
    <a href="#-requisitos-del-sistema">Requisitos</a> •
    <a href="#-compilación-e-instalación">Compilación</a> •
    <a href="#-arquitectura-del-proyecto">Arquitectura</a> •
    <a href="#-créditos-y-licencia">Créditos</a>
  </p>
</div>

---

## 📖 Descripción

**AS GBA Emulator** es un emulador de Game Boy Advance de código abierto diseñado para dispositivos Android. Su motor de emulación está escrito en **C++20** y se ejecuta en un hilo nativo independiente con sincronización contra el reloj de audio del sistema, mientras que la capa de aplicación y la interfaz gráfica están construidas desde cero con **Jetpack Compose (Material 3)**.

> [!NOTE]
> Este proyecto **no incluye ROMs ni BIOS**. Es necesario proporcionar archivos propios legalmente adquiridos.

---

## ✨ Características

| Característica | Descripción |
| :--- | :--- |
| ⚡ **Núcleo C++20 Nativo** | Emulación en hilo dedicado de CPU, PPU, APU, DMA, temporizadores y cartucho. |
| 🔊 **Sincronización de Audio** | Salida de sonido ajustada al reloj de audio del sistema para evitar cortes y desfases. |
| 🕹️ **Controles Táctiles Ajustables** | Botones M3 redimensionables y reubicables, con guardado de plantilla independiente para vertical y horizontal. |
| 🎮 **Soporte de Mandos Físicos** | Reasignación completa para controles Bluetooth y USB OTG (botones, gatillos y palanca analógica). |
| 💾 **Detección Real de Guardado** | Identificación de Flash (64/128 KiB), SRAM y EEPROM (512 B/8 KiB) inspeccionando los marcadores del SDK oficial. |
| 🚀 **Soporte de ROMs Extendidas** | Ejecución fluida de ROMs estándar y *hackroms* de 32 MiB y 64 MiB. |
| 📑 **Menú Rápido In-Game** | *Bottom sheet* intuitivo para gestión de salvaguardas y ajustes sin pausar abruptamente la sesión. |

---

## 🛠️ Requisitos del Sistema

- **Android Studio** con el siguiente entorno instalado:
  - **NDK:** `28.2.13676358`
  - **CMake:** `3.22.1`
- **JDK:** 11 o superior.
- **Gradle:** 9.3.1 en el sistema (el repositorio no incluye el wrapper `gradlew`).
- **Dispositivo objetivo:** Android 7.0 (API Nivel 24) o superior.

---

## 🚀 Compilación e Instalación

### Desde Android Studio

1. Clona el repositorio e importa el proyecto en Android Studio:

   ```bash
   git clone https://github.com/AndreDev66/AS-GBA-Emulator.git
   ```

2. Permite que Gradle sincronice las dependencias del módulo `:app`.
3. Ejecuta el objetivo `app` en un dispositivo físico o emulador.

### Desde línea de comandos

Para generar el paquete de depuración (Debug APK):

```bash
gradle :app:assembleDebug
```

El binario resultante estará disponible en:

```text
app/build/outputs/apk/debug/app-debug.apk
```

---

## 🏗️ Arquitectura del Proyecto

El proyecto separa de forma estricta la interfaz de usuario en Kotlin del motor de emulación nativo en C++20:

```text
app/src/main/
├── java/com/example/       # Capa de UI (Jetpack Compose) y lógica de la aplicación
└── cpp/
    ├── ravenemu/          # Núcleo de emulación (C++20)
    └── asgba_jni.cpp      # Puente JNI entre Kotlin y C++
```

### Fachada principal (`GBACore`)

La clase `com.example.core.GBACore` actúa como el punto de acceso central:

- Posee y gestiona el hilo de emulación dedicado.
- Administra el framebuffer compartido y el flujo de salida de audio.
- Serializa la comunicación y las llamadas a la API nativa vía JNI entre el hilo de interfaz y el núcleo.

---

## 🤝 Créditos y Licencia

### Créditos

- **Desarrollo principal en interfaz: y Mejoras del Núcleo** Andrés Socorro.
- **Núcleo de emulación:** Motor en C++20 del proyecto RavenEmu, integrado a través del puente JNI (`app/src/main/cpp/ravenemu` y `app/src/main/cpp/asgba_jni.cpp`). El código de RavenEmu se conserva en su mayoría, pero  con múltiples mejoras realizadas por mi.

### Licencia

El tipo de licencia de este repositorio se encuentra actualmente pendiente de definición. Previo a la publicación de la primera versión estable, se incluirán los archivos `LICENSE` y `NOTICE` con la atribución formal y los términos legales de RavenEmu.
