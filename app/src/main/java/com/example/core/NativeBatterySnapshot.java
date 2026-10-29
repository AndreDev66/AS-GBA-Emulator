package com.example.core;

/** Copia inmutable de una RAM de cartucho nativa y su generación. */
public final class NativeBatterySnapshot {
    public final byte[] data;
    public final long generation;

    public NativeBatterySnapshot(byte[] data, long generation) {
        this.data = data;
        this.generation = generation;
    }
}
