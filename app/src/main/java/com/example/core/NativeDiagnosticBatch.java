package com.example.core;

/** Diagnósticos extraídos de forma atómica tras una llamada nativa. */
public final class NativeDiagnosticBatch {
    public final int[] events;
    public final String[] details;

    public NativeDiagnosticBatch(int[] events, String[] details) {
        this.events = events;
        this.details = details;
    }
}
