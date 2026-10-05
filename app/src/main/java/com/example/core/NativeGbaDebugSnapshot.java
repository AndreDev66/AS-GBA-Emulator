package com.example.core;

/** Transporte JNI compacto para la superposición de depuración del GBA. */
public final class NativeGbaDebugSnapshot {
    public final int[] scalars;
    public final int[] layerPixels;
    public final int[] swiCounts;
    public final double[] timingsMillis;

    public NativeGbaDebugSnapshot(
            int[] scalars,
            int[] layerPixels,
            int[] swiCounts,
            double[] timingsMillis
    ) {
        this.scalars = scalars;
        this.layerPixels = layerPixels;
        this.swiCounts = swiCounts;
        this.timingsMillis = timingsMillis;
    }
}
