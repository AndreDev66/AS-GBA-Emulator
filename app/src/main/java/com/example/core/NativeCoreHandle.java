package com.example.core;

import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Posee un motor nativo pasivo y lo libera cuando se abandona la sesión.
 *
 * <p>La liberación normal es explícita: {@link #close()}, llamado por la fachada
 * Kotlin y por el ciclo de vida de Android. La red de seguridad de aquí sólo
 * cubre el caso de que nadie llame a {@code close()} — un motor retiene varios
 * megabytes fuera de la pila de Java, que el recolector de basura no ve y jamás
 * reclamará por sí mismo.
 *
 * <p>Esa red se apoyaba antes en {@code finalize()}, obsoleto y condenado a
 * desaparecer: en una máquina virtual reciente su llamada ya no está garantizada
 * y es desactivable por opción, de modo que la garantía había desaparecido
 * mientras conservaba la apariencia de existir. {@code java.lang.ref.Cleaner}
 * sería el sustituto indicado, pero Android sólo lo expone a partir de la API 33
 * y el proyecto apunta a la API 24: se usa directamente el mecanismo portable
 * que lo sostiene — {@link PhantomReference} y {@link ReferenceQueue}.
 */
public final class NativeCoreHandle implements AutoCloseable {

    private static final ReferenceQueue<NativeCoreHandle> ABANDONED = new ReferenceQueue<>();

    /**
     * Mantiene vivas las centinelas.
     *
     * <p>Una {@link PhantomReference} que ya no referencia nadie es ella misma
     * recolectada antes de poder encolarse: sin este conjunto, la red no se
     * dispararía nunca.
     */
    private static final Set<Sentinel> SENTINELS = ConcurrentHashMap.newKeySet();

    static {
        Thread reaper = new Thread(NativeCoreHandle::reap, "asgba-native-reaper");
        reaper.setDaemon(true);
        reaper.start();
    }

    private final Sentinel sentinel;
    private long value;

    public NativeCoreHandle(int consoleStorageId, int forcedSaveType) {
        value = NativeCoreBridge.create(consoleStorageId, forcedSaveType);
        sentinel = new Sentinel(this, value);
        SENTINELS.add(sentinel);
    }

    public synchronized long value() {
        if (value == 0) throw new IllegalStateException("El motor nativo está cerrado");
        return value;
    }

    @Override
    public synchronized void close() {
        if (value == 0) return;
        NativeCoreBridge.destroy(value);
        value = 0;
        // Desarma la red: el motor está liberado, la centinela ya no tiene objeto.
        sentinel.disarm();
        SENTINELS.remove(sentinel);
    }

    /**
     * Libera los motores cuyo portador Java fue recolectado sin {@code close()}.
     *
     * <p>El hilo es demonio: nunca retiene la parada de la aplicación.
     */
    private static void reap() {
        while (true) {
            try {
                Sentinel abandoned = (Sentinel) ABANDONED.remove();
                SENTINELS.remove(abandoned);
                abandoned.release();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Centinela que sobrevive a su portador Java.
     *
     * <p>Sólo retiene el puntero nativo, nunca el {@link NativeCoreHandle}: una
     * referencia fuerte hacia él impediría para siempre su encolado, y la red de
     * seguridad no serviría de nada.
     */
    private static final class Sentinel extends PhantomReference<NativeCoreHandle> {
        private volatile long value;

        Sentinel(NativeCoreHandle owner, long value) {
            super(owner, ABANDONED);
            this.value = value;
        }

        void disarm() {
            value = 0;
        }

        void release() {
            long abandoned = value;
            value = 0;
            if (abandoned != 0) NativeCoreBridge.destroy(abandoned);
        }
    }
}
