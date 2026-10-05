package com.example.core;

/**
 * Frontera Java/JNI estable del motor de emulación de AS GBA Emulator.
 *
 * El transporte a través de JNI se limita deliberadamente a arreglos primitivos y
 * a cuatro objetos de valor diminutos e inmutables. Toda la política de emulación
 * —cadencia, audio, persistencia de la RAM de cartucho, renderizado— queda del
 * lado de Kotlin: la frontera describe el hardware, no decide por la interfaz.
 *
 * El motor al que sirve esta frontera es el proyecto RavenEmu, tomado como base
 * de la emulación de esta versión de AS GBA Emulator.
 */
public final class NativeCoreBridge {
    private NativeCoreBridge() {}

    static {
        // La ruta absoluta permite reemplazar la biblioteca en pruebas o en
        // compilaciones locales sin tocar el APK.
        String explicitLibrary = System.getProperty("asgba.native.library");
        if (explicitLibrary == null || explicitLibrary.trim().isEmpty()) {
            System.loadLibrary("asgba");
        } else {
            System.load(new java.io.File(explicitLibrary).getAbsolutePath());
        }
    }

    /** Identificador de almacenamiento de Game Boy Advance. */
    public static final int CONSOLE_GAME_BOY_ADVANCE = 2;

    /** Crea el motor y devuelve su asa nativa, o 0 si la construcción falló. */
    public static native long create(int consoleStorageId, int forcedSaveType);

    public static native void destroy(long handle);

    public static native void loadRom(long handle, byte[] rom, byte[] batteryRam);

    public static native void reset(long handle);

    public static native void runFrame(long handle, int[] framebuffer, boolean renderVideo);

    public static native void setButton(long handle, int buttonOrdinal, boolean pressed);

    /** Copia muestras disponibles al búfer y devuelve cuántas se copiaron. */
    public static native int readAudio(long handle, short[] destination);

    public static native int framebufferFormat(long handle);

    public static native boolean hasBatteryRam(long handle);

    public static native boolean batteryRamDirty(long handle);

    public static native NativeBatterySnapshot snapshotBatteryRam(long handle);

    public static native void acknowledgeBatteryRamSaved(long handle, long generation);

    public static native byte[] saveState(long handle);

    public static native void loadState(long handle, byte[] state);

    public static native void setClockEpoch(long handle, boolean overridden, long epochSeconds);

    public static native int gbaSaveType(long handle);

    public static native void setGbaForcedSaveType(long handle, int forcedSaveType);

    /**
     * Impone la presencia del reloj de cartucho de Game Boy Advance.
     *
     * Tri-estado transportado como entero, al faltar {@code Optional} en la
     * frontera JNI: negativo devuelve el control a la detección, cero impone la
     * ausencia, positivo impone la presencia. Tiene efecto en la próxima carga
     * de ROM.
     */
    public static native void setGbaForcedRtc(long handle, int forcedRtc);

    public static native boolean gbaRtcActive(long handle);

    public static native void setMeasuringTime(long handle, boolean enabled);

    public static native boolean measuringTime(long handle);

    public static native NativeGbaDebugSnapshot debugSnapshot(long handle);

    public static native NativeDiagnosticBatch drainDiagnostics(long handle);
}
