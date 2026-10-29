package com.example.core;

/**
 * Frontera Java/JNI del núcleo de Nintendo DS (melonDS).
 *
 * Es una frontera **aparte** de {@link NativeCoreBridge}, y no una extensión de
 * ella. El motivo es concreto: {@code NativeCoreBridge} convierte el
 * identificador de núcleo que recibe directamente en un {@code ravenemu::Core*},
 * y en esa suposición se apoya cada una de sus operaciones. Una Nintendo DS no
 * es un {@code ravenemu::Core} —viene de otro proyecto, con otra API y otro
 * lenguaje estándar— así que encajarla ahí habría obligado a representar
 * «cualquier consola» en el camino de Game Boy y GBA, que hoy funciona.
 *
 * Con dos fronteras, Kotlin elige la que corresponde a la consola en cuestión y
 * ninguna de las dos cambia de forma. El precio es tener dos superficies que
 * mantener, y es un precio que compensa: los dos núcleos no comparten ni una
 * línea.
 *
 * Como en la otra frontera, el transporte se limita a arreglos primitivos y a
 * un valor inmutable de texto. Nada de esto decide política de emulación.
 *
 * Base: melonDS, https://github.com/melonDS-emu/melonDS — GPL-3.0.
 */
public final class MelonDsCoreBridge {
    private MelonDsCoreBridge() {}

    static {
        System.loadLibrary("asgba");
    }

    /**
     * Identificadores de los botones, en el mismo orden que
     * {@link com.example.core.emulation.EmulatorButton}: el ordinal es un
     * contrato entre Kotlin y este puente.
     */
    public static final int BUTTON_UP = 0;
    public static final int BUTTON_DOWN = 1;
    public static final int BUTTON_LEFT = 2;
    public static final int BUTTON_RIGHT = 3;
    public static final int BUTTON_A = 4;
    public static final int BUTTON_B = 5;
    public static final int BUTTON_START = 6;
    public static final int BUTTON_SELECT = 7;
    public static final int BUTTON_L = 8;
    public static final int BUTTON_R = 9;
    /** Botones propios de la Nintendo DS, que la Game Boy Advance no tiene. */
    public static final int BUTTON_X = 10;
    public static final int BUTTON_Y = 11;

    /** Píxeles del fotograma: dos pantallas de 256×192 en horizontal. */
    public static final int FRAME_WIDTH = 512;
    public static final int FRAME_HEIGHT = 192;
    public static final int FRAME_PIXELS = FRAME_WIDTH * FRAME_HEIGHT;

    /**
     * Crea el núcleo y devuelve su asa, o 0 si no se pudo construir.
     *
     * @param dataDir directorio privado de la app, donde melonDS puede escribir
     *                (firmware, configuración). No debe ser nulo.
     */
    public static native long create(String dataDir);

    public static native void destroy(long handle);

    /**
     * Carga el cartucho desde memoria y lo deja listo para arrancar.
     *
     * @param save memoria de partida previa, o {@code null} si es la primera
     *             vez que se abre el juego.
     * @return los metadatos de la cabecera, o {@code null} si no arrancó, en cuyo
     *         caso se ha lanzado una excepción con el motivo.
     */
    public static native MelonDsRomInfo loadRom(long handle, byte[] rom, byte[] save);

    /** Carga una ROM grande desde un archivo para no copiarla al heap de Java. */
    public static native MelonDsRomInfo loadRomFile(long handle, String path, byte[] save);

    /** Arranca la emulación. */
    public static native void start(long handle);

    /** Reinicia la consola conservando el cartucho. */
    public static native void reset(long handle);

    /**
     * false cuando el juego apaga la consola.
     *
     * El núcleo sigue vivo y se puede volver a arrancar con {@link #reset}, pero
     * hasta entonces no hay nada que emular.
     */
    public static native boolean isRunning(long handle);

    /**
     * Emula un fotograma y escribe las dos pantallas en {@code framebuffer}.
     * Debe medir al menos {@link #FRAME_PIXELS} enteros.
     */
    public static native void runFrame(long handle, int[] framebuffer);

    public static native void setButton(long handle, int button, boolean pressed);

    /**
     * Puntero sobre la pantalla táctil, en coordenadas de pantalla: la
     * horizontal va de 0 a 255 y la vertical de 0 a 191, que es como las mide
     * el hardware.
     */
    public static native void setStylus(long handle, int x, int y, boolean pressed);

    /**
     * Copia muestras estéreo entrelazadas y devuelve cuántas copió. Cada
     * muestra ocupa dos {@code short}.
     */
    public static native int readAudio(long handle, short[] destination);

    /** true si el cartucho tiene memoria de guardado. */
    public static native boolean hasBattery(long handle);

    /** Serializa el estado completo de la consola, o {@code null} si falló. */
    public static native byte[] saveState(long handle);

    public static native boolean loadState(long handle, byte[] state);

    /** Vuelca la memoria de partida del cartucho, o {@code null} si no hay. */
    public static native byte[] snapshotBattery(long handle);

    /** true si el juego ha escrito en la memoria de partida desde el último volcado. */
    public static native boolean batteryDirty(long handle);

    /** Da por guardado lo que había pendiente. */
    public static native void acknowledgeBattery(long handle);
}
