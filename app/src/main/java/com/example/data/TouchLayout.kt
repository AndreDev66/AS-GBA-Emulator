package com.example.data

/**
 * Los siete controles que se dibujan en pantalla y que el usuario puede colocar.
 *
 * La cruceta es un control único y no cuatro: partirla en piezas cambiaría cómo se
 * pulsan las diagonales, que en la GBA existen.
 */
enum class TouchControl(val label: String) {
    DPAD("Cruceta"),
    A("A"),
    B("B"),
    START("Start"),
    SELECT("Select"),
    L("L"),
    R("R")
}

/**
 * Posición y tamaño de un control, en fracción sobre la zona de controles.
 *
 * Fracciones y no píxeles o dp porque el mismo mando vale en un móvil de 5" y en
 * una tableta de 10": quien pone los botones en la esquina los quiere en la
 * esquina en los dos.
 *
 * [scale] multiplica el tamaño de fábrica de ese control, al margen del ajuste
 * global de Ajustes, que agranda o encoge todos a la vez.
 */
data class TouchPlacement(
    val x: Float,
    val y: Float,
    val scale: Float = 1.0f
) {
    /**
     * Mueve el control sin salirse de la zona.
     *
     * El margen es la mitad del control en esa dirección, para que un botón pegado
     * al borde siga entero y no se pierda bajo la muesca ni tras la barra de gestos.
     */
    fun clamped(halfWidthFraction: Float, halfHeightFraction: Float): TouchPlacement {
        // Un control más ancho que la zona no tiene dónde esconderse y `coerceIn`
        // lanzaría, así que en ese caso se centra.
        fun axis(value: Float, half: Float): Float {
            val max = 1f - half
            return if (max <= half) 0.5f else value.coerceIn(half, max)
        }
        return TouchPlacement(
            x = axis(x, halfWidthFraction.coerceAtLeast(0f).coerceAtMost(0.5f)),
            y = axis(y, halfHeightFraction.coerceAtLeast(0f).coerceAtMost(0.5f)),
            scale = scale
        )
    }
}

/**
 * Distribución de los controles táctiles editada por el usuario.
 *
 * Sólo se guarda lo que el usuario ha tocado. Lo demás se dibuja con el reparto de
 * fábrica, que se calcula al pintar en [legacyDefaultFor] con las fórmulas de
 * antes del editor.
 *
 * Gracias a eso "Restablecer" devuelve exactamente lo que había: el reparto
 * antiguo dependía del ancho de pantalla y del deslizador de tamaño, así que
 * guardado como fracciones fijas se deformaba al cambiar cualquiera de los dos.
 *
 * Hay un mapa por orientación porque compartirlo obligaría a recolocar todo al
 * girar.
 */
data class TouchLayout(
    val portrait: Map<TouchControl, TouchPlacement> = emptyMap(),
    val landscape: Map<TouchControl, TouchPlacement> = emptyMap()
) {
    /** Controles que el usuario ha colocado en la orientación indicada. */
    fun forOrientation(isLandscape: Boolean): Map<TouchControl, TouchPlacement> =
        if (isLandscape) landscape else portrait

    /** Sustituye los controles de una orientación, dejando la otra intacta. */
    fun with(isLandscape: Boolean, placements: Map<TouchControl, TouchPlacement>): TouchLayout =
        if (isLandscape) copy(landscape = placements) else copy(portrait = placements)

    /** Coloca un control, o lo devuelve al reparto de fábrica si [placement] es `null`. */
    fun withPlacement(
        isLandscape: Boolean,
        control: TouchControl,
        placement: TouchPlacement?
    ): TouchLayout {
        val updated = forOrientation(isLandscape).toMutableMap()
        if (placement == null) updated.remove(control) else updated[control] = placement
        return with(isLandscape, updated)
    }

    /**
     * Si el usuario llegó a colocar algo. Distingue "nunca se tocó" de "se movió y
     * se devolvió", que es lo único que el botón de restablecer puede deshacer.
     */
    val isDefault: Boolean
        get() = portrait.isEmpty() && landscape.isEmpty()

/**
     * Formato: `P:{CTRL:x,y,scale;…}|L:{CTRL:x,y,scale;…}`, con las fracciones
     * redondeadas a tres decimales.
     *
     * Los controles se nombran con identificadores fijos y no con ordinales del
     * enum, para que añadir uno no descuadre lo ya guardado. Sólo se escriben los
     * colocados; los que falten salen con el reparto de fábrica.
     */
    fun serialize(): String = buildString {
        append("P:")
        append(encode(portrait))
        append("|L:")
        append(encode(landscape))
    }

    private fun encode(placements: Map<TouchControl, TouchPlacement>): String =
        placements.entries
            .sortedBy { it.key.ordinal }
            .joinToString(";") { (control, p) ->
                "${control.name}:${round(p.x)},${round(p.y)},${round(p.scale)}"
            }

    companion object {
        private fun round(value: Float): String =
            String.format(java.util.Locale.ROOT, "%.3f", value)

        /**
         * Lee una distribución guardada. Cualquier dato raro se descarta y el
         * control afectado vuelve al reparto de fábrica antes que dejar la
         * pantalla vacía.
         */
        fun deserialize(raw: String?): TouchLayout {
            if (raw.isNullOrEmpty()) return TouchLayout()
            return TouchLayout(
                portrait = decode(raw.substringBefore('|').removePrefix("P:")),
                landscape = decode(raw.substringAfter('|', "").removePrefix("L:"))
            )
        }

        private fun decode(chunk: String): Map<TouchControl, TouchPlacement> {
            if (chunk.isBlank()) return emptyMap()
            val result = mutableMapOf<TouchControl, TouchPlacement>()
            for (entry in chunk.split(';')) {
                val parts = entry.split(':')
                if (parts.size != 2) continue
                val control = TouchControl.entries.firstOrNull { it.name == parts[0] } ?: continue
                val numbers = parts[1].split(',').map { it.trim().toFloatOrNull() }
                if (numbers.size != 3 || numbers.any { it == null }) continue
                val (x, y, scale) = numbers
                if (!x!!.isFinite() || !y!!.isFinite() || !scale!!.isFinite()) continue
                result[control] = TouchPlacement(x, y, scale)
            }
            return result
        }

        // ── El reparto de fábrica ────────────────────────────────────────────
        //
        // Medidas del overlay original: dos columnas de `SpaceBetween` en
        // horizontal, filas y pesos en vertical. Se dejan tal cual para que
        // restablecer devuelva el mismo aspecto y las mismas proporciones.

        private const val SHOULDER_W = 74f
        private const val SHOULDER_H = 34f
        private const val DPAD_SIZE = 144f
        private const val AB_BOX_W = 140f
        private const val AB_BOX_H = 110f
        private const val ACTION_SIZE = 60f
        private const val ACTION_INSET = 4f
        private const val PILL_VIS_W = 64f
        private const val PILL_VIS_H = 28f
        private const val PILL_MIN_W = 96f
        private const val PILL_MIN_H = 48f

        /** Relleno que el overlay original aplicaba por dentro de la zona. */
        private const val PAD_H_LANDSCAPE = 24f
        private const val PAD_V_LANDSCAPE = 12f
        private const val PAD_H_PORTRAIT = 20f
        private const val PAD_V_PORTRAIT = 14f

        /** Anchura de referencia con la que se calibraba el reparto vertical. */
        private const val REFERENCE_WIDTH = 402f

        /**
         * Dónde estaba cada control antes de que existiera el editor.
         *
         * Devuelve el reparto en fracción sobre [areaWidthDp] × [areaHeightDp], la
         * zona real de dibujo, con el relleno original ya descontado: la fracción
         * es sobre toda la zona, no sobre la interior.
         *
         * El hueco vertical va con `SpaceBetween` y un `Spacer` con peso, así que
         * mide lo que sobra. Eso es lo que hace subir o bajar la cruceta según la
         * altura disponible.
         */
        fun legacyDefaultFor(
            control: TouchControl,
            isLandscape: Boolean,
            areaWidthDp: Float,
            areaHeightDp: Float,
            globalScale: Float
        ): TouchPlacement {
            val w = areaWidthDp.coerceAtLeast(1f)
            val h = areaHeightDp.coerceAtLeast(1f)
            val s = globalScale.coerceAtLeast(0.01f)

            // Coordenadas del centro del control en dp, dentro de la zona con el
            // relleno ya descontado.
            val centerDp: Pair<Float, Float>
            // Multiplicador sobre el tamaño de fábrica, que es lo que aplica la
            // capa de dibujo después.
            val sizeFactor: Float

            if (isLandscape) {
                // Hombros y pastillas al 90 %, cruceta y grupo A/B al 95 %.
                val k1 = 0.9f * s
                val k = 0.95f * s

                val innerW = (w - 2f * PAD_H_LANDSCAPE).coerceAtLeast(1f)
                val innerH = (h - 2f * PAD_V_LANDSCAPE).coerceAtLeast(1f)

                val shoulderW = SHOULDER_W * k1
                val shoulderH = SHOULDER_H * k1
                val pillW = maxOf(PILL_MIN_W, PILL_VIS_W * k1)
                val pillH = maxOf(PILL_MIN_H, PILL_VIS_H * k1)
                val abH = AB_BOX_H * k
                val dpadS = DPAD_SIZE * k

                // Columna izquierda: L, hueco, cruceta, 10 dp, SELECT.
                val leftGap = (innerH - shoulderH - dpadS - 10f - pillH).coerceAtLeast(0f)
                // Columna derecha: R, hueco, grupo A/B, 10 dp, START.
                val rightGap = (innerH - shoulderH - abH - 10f - pillH).coerceAtLeast(0f)
                val abTop = shoulderH + rightGap

                // El grupo A/B es un recuadro de 140 × 110 pegado al borde
                // derecho; A va arriba a la derecha y B abajo a la izquierda, cada
                // uno con 4 dp de margen por dentro, igual que en el original.
                val boxLeft = innerW - AB_BOX_W * k
                val insetTop = (ACTION_INSET + ACTION_SIZE / 2f) * k
                val insetBottom = (AB_BOX_H - ACTION_INSET - ACTION_SIZE / 2f) * k
                val insetEnd = (AB_BOX_W - ACTION_INSET - ACTION_SIZE / 2f) * k
                val insetStart = (ACTION_INSET + ACTION_SIZE / 2f) * k

                val dx = when (control) {
                    TouchControl.L -> shoulderW / 2f to shoulderH / 2f
                    TouchControl.R -> innerW - shoulderW / 2f to shoulderH / 2f
                    TouchControl.DPAD -> dpadS / 2f to shoulderH + leftGap + dpadS / 2f
                    TouchControl.SELECT ->
                        pillW / 2f to shoulderH + leftGap + dpadS + 10f + pillH / 2f

                    TouchControl.START ->
                        innerW - pillW / 2f to shoulderH + rightGap + abH + 10f + pillH / 2f

                    TouchControl.A -> boxLeft + insetEnd to abTop + insetTop

                    TouchControl.B -> boxLeft + insetStart to abTop + insetBottom
                }
                centerDp = PAD_H_LANDSCAPE + dx.first to PAD_V_LANDSCAPE + dx.second
                sizeFactor = if (control == TouchControl.DPAD ||
                    control == TouchControl.A ||
                    control == TouchControl.B
                ) 0.95f else 0.9f
            } else {
                // En vertical la cruceta y las pastillas se encogían si la
                // pantalla era más estrecha que la de referencia, para que el
                // conjunto no se saliera por los lados.
                val k = minOf(s, maxOf(0.6f, (w - 2f * PAD_H_PORTRAIT - 24f) / REFERENCE_WIDTH))

                val innerW = (w - 2f * PAD_H_PORTRAIT).coerceAtLeast(1f)
                val innerH = (h - 2f * PAD_V_PORTRAIT).coerceAtLeast(1f)

                val shoulderW = SHOULDER_W * s
                val shoulderH = SHOULDER_H * s
                val pillW = maxOf(PILL_MIN_W, PILL_VIS_W * k)
                val pillH = maxOf(PILL_MIN_H, PILL_VIS_H * k)
                val dpadS = DPAD_SIZE * k
                val abH = AB_BOX_H * k

                // El recuadro de abajo deja 6 dp de margen, la fila de A/B sube
                // 88 dp desde el borde inferior y la de SELECT/START 12 dp.
                val contentBottom = innerH - 6f
                val rowBottom = contentBottom - 88f

                val actionRight = AB_BOX_W - ACTION_INSET - ACTION_SIZE / 2f
                val actionLeft = ACTION_INSET + ACTION_SIZE / 2f
                val insetTop = ACTION_INSET + ACTION_SIZE / 2f
                val insetBottom = AB_BOX_H - ACTION_INSET - ACTION_SIZE / 2f

                // El grupo A/B se alinea al fondo de la fila de la cruceta, que a
                // su vez se apoya 88 dp por encima del borde inferior.
                val boxLeft = innerW - AB_BOX_W * k
                val boxTop = rowBottom - abH

                val dx = when (control) {
                    TouchControl.L -> shoulderW / 2f to shoulderH / 2f
                    TouchControl.R -> innerW - shoulderW / 2f to shoulderH / 2f
                    TouchControl.DPAD -> dpadS / 2f to rowBottom - dpadS / 2f
                    TouchControl.SELECT -> innerW / 2f - pillW / 2f - 12f to contentBottom - 12f - pillH / 2f
                    TouchControl.START -> innerW / 2f + pillW / 2f + 12f to contentBottom - 12f - pillH / 2f
                    TouchControl.A -> boxLeft + actionRight * k to boxTop + insetTop * k

                    TouchControl.B -> boxLeft + actionLeft * k to boxTop + insetBottom * k
                }
                centerDp = PAD_H_PORTRAIT + dx.first to PAD_V_PORTRAIT + dx.second
                // Los hombros sí seguían la escala global en vertical: sólo el
                // conjunto de cruceta y A/B, que es el que se salía por los
                // lados, se encogía con la anchura de la pantalla.
                sizeFactor = when (control) {
                    TouchControl.L, TouchControl.R -> 1f
                    else -> k / s
                }
            }

            return TouchPlacement(
                x = (centerDp.first / w).coerceIn(0f, 1f),
                y = (centerDp.second / h).coerceIn(0f, 1f),
                scale = sizeFactor
            )
        }
    }
}