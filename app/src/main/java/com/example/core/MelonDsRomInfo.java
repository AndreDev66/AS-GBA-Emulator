package com.example.core;

/**
 * Metadatos que AS lee de la cabecera de un cartucho de Nintendo DS.
 *
 * Los entrega el propio melonDS en lugar de analizarlos en Kotlin: ya los tiene
 * parseados —con sus comprobaciones de CRC— en el momento en que reconoce el
 * cartucho, y volver a hacerlo en Kotlin sería duplicar una tabla de offsets que
 * además depende de la versión del núcleo.
 *
 * Sólo van aquí los campos que AS enseña o decide con ellos. La cabecera trae
 * más (el código del fabricante, entre otros) y no se copia porque no se usa:
 * un valor transportado desde el nativo y nunca leído es ruido que además
 * obliga a mantenerlo cuando melonDS cambie su formato.
 */
public final class MelonDsRomInfo {
    /** Título del cartucho, de hasta 12 caracteres. */
    public final String title;

    /** Código de juego de 4 letras, tal y como lo usa la lista de melonDS. */
    public final String gameCode;

    /** Región declarada: "J" Japón, "E"Estados Unidos, "P"Europa, "libre"… */
    public final String region;

    /** Modelo de la consola para el que se hizo el juego: "DS", "DSi"… */
    public final String unit;

    MelonDsRomInfo(String title, String gameCode, String region, String unit) {
        this.title = title == null ? "" : title;
        this.gameCode = gameCode == null ? "" : gameCode;
        this.region = region == null ? "" : region;
        this.unit = unit == null ? "" : unit;
    }

    @Override
    public String toString() {
        return title + " (" + gameCode + ", " + region + ")";
    }
}
