package com.example.ui.components

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.GBACore
import com.example.data.TouchControl
import com.example.data.TouchLayout
import com.example.data.TouchPlacement
import com.example.ui.theme.ControlFamily
import com.example.ui.theme.SteamCyan
import com.example.ui.theme.SteamGreen
import com.example.ui.theme.SteamPink
import com.example.ui.theme.rememberFrostSkin
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Mandando en pantalla para AS GBA Emulator.
 *
 * Cada control se coloca por fracción (ver [TouchLayout]) y no con `align` ni con
 * `SpaceBetween`, porque así se pueden arrastrar sueltos y guardarlos. En modo
 * [editing] los mismos controles pasan a ser manejables con el dedo y dejan de
 * pulsarse: mover un botón y disparar ese botón a la vez no pueden ser el mismo
 * gesto.
 *
 * El overlay ocupa **toda** la pantalla en los dos modos, también en vertical, y
 * no sólo la mitad de abajo. Es lo que permite el viaje de un modo a otro: si
 * cada modo trajera su propia copia de los mandos, al girar habría dos juegos de
 * botones distintos y sólo se podría fundir un encuadre con otro. Con una sola
 * capa, cada control tiene dos destinos —el área de mandos de vertical y la
 * pantalla completa— y [portraitArea] dice cuál es el primero; el recorrido entre
 * los dos es una interpolación, no un telón.
 */
@Composable
fun TouchControllerOverlay(
    core: GBACore,
    opacity: Float,
    scale: Float,
    hapticFeedbackEnabled: Boolean,
    layout: TouchLayout = TouchLayout(),
    isLandscape: Boolean = false,
    editing: Boolean = false,
    selectedControl: TouchControl? = null,
    onSelectControl: (TouchControl?) -> Unit = {},
    onPlacementChange: (TouchControl, TouchPlacement) -> Unit = { _, _ -> },
    /**
     * Dibuja L y R. La Game Boy no los tiene en el cartucho y su núcleo los
     * descarta, así que se ocultan en vez de dejar dos botones muertos.
     */
    showShoulderButtons: Boolean = true,
    /**
     * Dibuja X e Y. Sólo la Nintendo DS los tiene en el cartucho; en Game Boy y
     * GBA no existen, y sus núcleos los descartan, así que se ocultan en vez de
     * dejar dos botones muertos.
     */
    showFaceButtons: Boolean = false,
    isNintendoDs: Boolean = false,
    /**
     * Zona de mandos de vertical, en píxeles y con el mismo origen que el
     * overlay. La mide quien compone (la mitad inferior de la columna) porque
     * depende de pesos de layout y de los márgenes de sistema, que aquí no se
     * conocen. Sin ella, el destino de vertical cae en la pantalla entera.
     */
    portraitArea: Rect = Rect.Zero,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val vibrator = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    fun triggerHaptic() {
        if (!hapticFeedbackEnabled || vibrator == null) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(16, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(16)
            }
        } catch (_: Exception) {}
    }

    // Un lector de pantalla no tiene un dedo que mantenga pulsado un botón, así
    // que la acción accesible pulsa y suelta sola a los 400 ms: el mismo camino
    // (`core.setKey`) que recorre el toque real, no una ruta paralela que pudiera
    // divergir de la emulación.
    val a11yScope = rememberCoroutineScope()
    fun accessiblePress(key: Int) {
        core.setKey(key, true)
        triggerHaptic()
        a11yScope.launch {
            // El `finally` suelta la tecla aunque el usuario salga de la
            // partida antes de los 400 ms: cancelar en mitad del `delay` no
            // debe dejar un botón pulsado en el núcleo.
            try {
                delay(400)
            } finally {
                core.setKey(key, false)
            }
        }
    }

    // Tamaño real del overlay, en píxeles. Las fracciones se convierten aquí, con
    // las medidas de verdad, en vez de suponer un ancho de referencia: así el
    // mismo reparto encaja en cualquier pantalla.
    var areaWidth by remember { mutableStateOf(0) }
    var areaHeight by remember { mutableStateOf(0) }

    // Zona sobre la que se mide una fracción mientras se edita. En vertical el
    // reparto vive en el área de mandos, así que el desplazamiento del dedo se
    // convierte a fracción de esa área y no de la pantalla entera: si no, mover
    // el dedo un cuarto de pantalla desplazaría el botón un cuarto de la zona
    // pequeña, y el botón se adelantaría al dedo.
    val activeZoneWidthPx by rememberUpdatedState(
        if (editing && !isLandscape && portraitArea.width > 0) portraitArea.width else areaWidth.toFloat()
    )
    val activeZoneHeightPx by rememberUpdatedState(
        if (editing && !isLandscape && portraitArea.height > 0) portraitArea.height else areaHeight.toFloat()
    )

    val d = density.density

    // Los dos destinos de cada control, con las medidas de cada zona.
    //
    // Se calculan los dos modos siempre, y no sólo el actual, porque el viaje
    // entre ellos es una interpolación: sin el destino final no hay viaje, hay un
    // salto. El reparto de fábrica se pide con la zona y el ajuste de tamaño
    // reales de cada modo, que es el mismo cálculo que hacía la composición
    // original, así que un control sin tocar acaba donde acababa antes de que
    // existiera el editor.
    //
    // Los dos modos consultan lo guardado y son independientes: mover un botón en
    // vertical no lo mueve en pantalla completa ni al revés. Cuando no hay nada
    // guardado —lo normal hasta que alguien abre el editor— manda la fábrica.
    data class ModeTargets(val full: Map<TouchControl, TouchPlacement>, val portrait: Map<TouchControl, TouchPlacement>)

    val targets = remember(areaWidth, areaHeight, scale, layout, portraitArea, isNintendoDs, d) {
        if (areaWidth <= 0 || areaHeight <= 0) {
            null
        } else {
            // Vertical cae en la pantalla entera mientras nadie mida el área de
            // mandos: en el primer fotograma no hay medida y, antes de medirla,
            // es mejor dibujarlos donde caerán que saltando al encuadre siguiente.
            // Es también el caso del editor en vertical, que se dibuja ya dentro
            // de la zona de mandos y por eso no necesita que se la señalen.
            val pw = if (portraitArea.width > 0) portraitArea.width / d else areaWidth / d
            val ph = if (portraitArea.height > 0) portraitArea.height / d else areaHeight / d

            val full = TouchControl.entries.associateWith { control ->
                layout.forOrientation(true)[control]
                    ?: TouchLayout.legacyDefaultFor(
                        control = control,
                        isLandscape = true,
                        areaWidthDp = areaWidth / d,
                        areaHeightDp = areaHeight / d,
                        globalScale = scale,
                        isNintendoDs = isNintendoDs
                    )
            }
            val portrait = TouchControl.entries.associateWith { control ->
                layout.forOrientation(false)[control]
                    ?: TouchLayout.legacyDefaultFor(
                        control = control,
                        isLandscape = false,
                        areaWidthDp = pw,
                        areaHeightDp = ph,
                        globalScale = scale,
                        isNintendoDs = isNintendoDs
                    )
            }
            ModeTargets(full, portrait)
        }
    }

    // Durante la recolocación el mando se queda quieto en el modo que se está
    // editando: animar su sitio mientras se arrastra sería pelearse con el dedo.
    val modeT = if (editing) {
        if (isLandscape) 1f else 0f
    } else {
        animateModeProgress(isLandscape)
    }
    val travel = travelEnvelope(modeT)

    // El gesto necesita leer durante el arrastre, y el bloque de `pointerInput` no se
    // reinicia en cada recomposición: capturar aquí el valor lo congelaría en el
    // primer fotograma y el botón iría y volvería a su sitio en vez de seguir al dedo.
    //
    // Se guarda el reparto del modo **activo** (el que se edita), no siempre el de
    // pantalla completa: en vertical el arrastre tiene que partir de la posición
    // que el botón tiene en vertical.
    val activeTargets = if (editing && !isLandscape) targets?.portrait else targets?.full
    val livePlacements by rememberUpdatedState(activeTargets.orEmpty())
    val liveSelect by rememberUpdatedState(onSelectControl)
    val liveChange by rememberUpdatedState(onPlacementChange)
    val liveSelected by rememberUpdatedState(selectedControl)

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { areaWidth = it.width; areaHeight = it.height }
            // Pellizco resuelto en toda la zona, no dentro de cada botón. Con el gesto
            // por botón, un pellizco sólo se hace si los dos dedos caen en el mismo, y
            // los botones pequeños no dan pie para eso: sólo respondía la cruceta, que
            // es el único hitbox grande.
            //
            // El modificador **sólo existe mientras se edita**. Un `pointerInput`
            // en la raíz, aunque su cuerpo no consuma nada, basta para que el
            // hit-test pare en esta capa y no llegue a lo que hay debajo. En
            // vertical el overlay se dibuja por encima de la barra superior, así
            // que en juego —cuando este gesto no sirve para nada— dejaba la barra
            // muerta: el botón de volver y el menú no respondían a ningún toque.
            // Fuera de la edición no hay nada que interceptar y no se instala.
            .then(
                if (editing) Modifier.pointerInput(Unit) {
                    val pinch = PinchTracker(MIN_CONTROL_SCALE, MAX_CONTROL_SCALE)

                    awaitEachGesture {
                        // El tamaño lo acumula [PinchTracker] sobre una base que
                        // se re-ancla sobre lo dibujado cada vez que cambia el
                        // juego de dedos (sale uno y entra otro, o el sistema lo
                        // reengancha): así esos accidentes ya no multiplican el
                        // zoom contra una base vieja y el botón no vuelve de
                        // golpe a su tamaño de antes. El objetivo es el control
                        // ya elegido, que lo elige el dedo que está sobre él.
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            when {
                                pressed.size < 2 -> {
                                    // Con un solo dedo el arrastre lo lleva el
                                    // botón que se pulsó; el pellizco espera sin
                                    // cancelarse, y si el segundo dedo vuelve se
                                    // re-ancla y sigue desde donde estaba. Con
                                    // todos los dedos fuera, la secuencia acaba.
                                    if (pressed.isEmpty() && pinch.target != null) {
                                        pinch.end()
                                        return@awaitEachGesture
                                    }
                                    pinch.singleFinger()
                                }
                                pressed.size > 2 -> {
                                    // Tercer dedo o palma: ruido. Se ignora para
                                    // que no pueda saltar el tamaño.
                                }
                                else -> {
                                    // Un evento con un dedo que sale (y otro que
                                    // entra) mezcla posiciones de momentos
                                    // distintos: no produce tamaño.
                                    if (event.changes.any { !it.pressed }) continue
                                    val ids = pressed.map { it.id.value }.toSet()
                                    if (pinch.target == null || !pinch.sameFingers(ids)) {
                                        val control = liveSelected
                                        val start = control?.let { livePlacements[it] }
                                        if (start != null) {
                                            pinch.anchor(control, start.scale, ids)
                                        } else if (pinch.target == null) {
                                            // Todavía no hay control elegido.
                                            continue
                                        } else {
                                            pinch.reanchor(
                                                livePlacements[pinch.target!!]?.scale ?: 1f,
                                                ids
                                            )
                                        }
                                        // El evento que cambió los dedos mezcla
                                        // posiciones de dedos viejos y nuevos: su
                                        // zoom no es fiable, se retoma después.
                                        continue
                                    }
                                    val current = livePlacements[pinch.target!!]
                                        ?: continue
                                    val nextScale = pinch.event(event.calculateZoom())
                                    liveChange(
                                        pinch.target!!,
                                        current.copy(scale = nextScale)
                                    )
                                    event.changes.forEach { if (it.pressed) it.consume() }
                                }
                            }
                        }
                    }
                } else Modifier
            )
    ) {
        if (areaWidth <= 0 || areaHeight <= 0) return@Box

        // La zona en dp, que es donde están escritas las medidas de fábrica.
        val areaWidthDp = areaWidth / d
        val areaHeightDp = areaHeight / d

        // Dónde cae el área de mandos de vertical, y de qué tamaño. Es la
        // segunda mitad del encuadre: el juego arriba, el mando abajo. Sus
        // coordenadas vienen ya medidas por quien compone.
        val portraitLeftDp = if (portraitArea.width > 0) portraitArea.left / d else 0f
        val portraitTopDp = if (portraitArea.height > 0) portraitArea.top / d else 0f
        val portraitWidthDp = if (portraitArea.width > 0) portraitArea.width / d else areaWidthDp
        val portraitHeightDp = if (portraitArea.height > 0) portraitArea.height / d else areaHeightDp

        // Origen y tamaño de cada modo, para poder mezclar los dos. En
        // pantalla completa el área es el overlay entero y su origen, el (0,0).
        val lerp = { full: Float, portrait: Float -> portrait + (full - portrait) * modeT }

        for (control in TouchControl.entries) {
            // L y R sólo existen en la GBA y en la DS: en Game Boy no hay dónde
            // ponerlos. X e Y, al revés, sólo existen en la DS.
            if (!showShoulderButtons && (control == TouchControl.L || control == TouchControl.R)) continue
            if (!showFaceButtons && (control == TouchControl.X || control == TouchControl.Y)) continue
            val fullPlacement = targets?.full?.get(control) ?: continue
            val portraitPlacement = targets?.portrait?.get(control) ?: continue

            // El ajuste global de Ajustes y el de este control se multiplican: uno
            // agranda todos a la vez y el otro toca sólo este.
            val factor = scale * lerp(fullPlacement.scale, portraitPlacement.scale)
            val spec = specFor(control, compactDsPortrait = isNintendoDs && !isLandscape)
            val hitWidthDp = max(spec.visualWidthDp * factor, spec.minTouchWidthDp)
            val hitHeightDp = max(spec.visualHeightDp * factor, spec.minTouchHeightDp)

            // El centro de cada destino, ya en píxeles del overlay, y el punto
            // intermedio del viaje. Se mezcla en píxeles y no en fracciones: cada
            // modo mide sobre una zona distinta, así que una fracción no es
            // comparable entre los dos.
            val originLeft = lerp(0f, portraitLeftDp)
            val originTop = lerp(0f, portraitTopDp)
            val zoneWidth = lerp(areaWidthDp, portraitWidthDp)
            val zoneHeight = lerp(areaHeightDp, portraitHeightDp)

            // El margen del tope es la mitad del control en cada zona, para que
            // un botón pegado al borde siga entero y no se pierda bajo la muesca
            // ni tras la barra de gestos. En horizontal ese margen lo pone la
            // propia [TouchPlacement] (`clamped`), y en vertical se aplica aquí.
            val clamped = TouchPlacement(
                x = (originLeft / areaWidthDp) + lerp(fullPlacement.x, portraitPlacement.x) * (zoneWidth / areaWidthDp),
                y = (originTop / areaHeightDp) + lerp(fullPlacement.y, portraitPlacement.y) * (zoneHeight / areaHeightDp),
                scale = factor
            ).clamped(
                halfWidthFraction = hitWidthDp / 2f / areaWidthDp,
                halfHeightFraction = hitHeightDp / 2f / areaHeightDp
            )

            val hitWidthPx = with(density) { hitWidthDp.dp.toPx() }
            val hitHeightPx = with(density) { hitHeightDp.dp.toPx() }
            val centerXPx = clamped.x * areaWidth
            val centerYPx = clamped.y * areaHeight

            val isEditingThis = editing && selectedControl == control

            Box(
                modifier = Modifier
                    // El desplazamiento va en lambda a propósito: mientras se
                    // arrastra, la posición se lee en la fase de layout y el
                    // contenido del botón no se recompone en cada fotograma.
                    .offset {
                        IntOffset(
                            (centerXPx - hitWidthPx / 2f).roundToInt(),
                            (centerYPx - hitHeightPx / 2f).roundToInt()
                        )
                    }
                    .size(
                        width = with(density) { hitWidthDp.dp },
                        height = with(density) { hitHeightDp.dp }
                    )
                    // En mitad del viaje el botón se infla un poco y se
                    // transparente: leerlo como algo que pasa por delante y se
                    // aleja, en vez de como algo que aparece y desaparece. En los
                    // extremos esto vale 1 y 0, así que quieto no se toca.
                    .graphicsLayer {
                        scaleX = 1f + 0.06f * travel
                        scaleY = 1f + 0.06f * travel
                        alpha = 1f - 0.22f * travel
                    }
                    .then(
                        if (!editing) Modifier
                        else Modifier.pointerInput(control) {
                            // Gesto propio en lugar de `detectTransformGestures`, porque
                            // hace falta una base nueva en cada dedo: con la de la última vez,
                            // el botón habría saltado de vuelta al sitio anterior.
                            // `calculatePan` y `calculateZoom` son las mismas cuentas
                            // que hace el detector oficial.
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val start = livePlacements[control]
                                    ?: return@awaitEachGesture
                                var totalPan = Offset.Zero
                                // Si en algún momento hay dos dedos, éste era el
                                // dedo que sujetaba el botón mientras se pellizca,
                                // no uno que lo arrastra. Al volver a quedar uno,
                                // no se debe reapuntar a la posición del inicio:
                                // el botón saltaría de vuelta a donde estaba antes
                                // del pellizco. Tras un pellizco, este gesto no
                                // vuelve a arrastrar hasta soltar y pulsar otra vez.
                                var pinchSeen = false
                                down.consume()

                                while (true) {
                                    val event = awaitPointerEvent()
                                    val pressedCount = event.changes.count { it.pressed }
                                    if (pressedCount > 1) {
                                        pinchSeen = true
                                    } else if (!pinchSeen) {
                                        // `calculatePan` ya toma el centroide del
                                        // propio evento, que es lo que hace el
                                        // detector oficial. El tamaño lo lleva el
                                        // gesto de zona de arriba: aquí sólo se
                                        // mueve el botón.
                                        totalPan += event.calculatePan()
                                        liveChange(
                                            control,
                                            start.copy(
                                                x = (start.x + totalPan.x / activeZoneWidthPx).coerceIn(0f, 1f),
                                                y = (start.y + totalPan.y / activeZoneHeightPx).coerceIn(0f, 1f)
                                            )
                                        )
                                    }
                                    if (event.calculateCentroid(useCurrent = true) != Offset.Unspecified) {
                                        liveSelect(control)
                                    }

                                    val change = event.changes.firstOrNull { it.id == down.id }
                                    if (change == null || !change.pressed) break
                                    change.consume()
                                }
                            }
                        }
                    )
                    // Un nodo por control para TalkBack. `clearAndSetSemantics`
                    // retira lo que declare el dibujo interior ("A", "START"…),
                    // que si no se anunciaría dos veces —una por hijo—, y deja en
                    // su sitio la etiqueta, el rol y las acciones. El `testTag`
                    // se vuelve a fijar aquí porque limpiar los descendentes
                    // limpia también el suyo, y las pruebas lo buscan.
                    .clearAndSetSemantics {
                        // La clave se fija por su `SemanticsPropertyKey` y no
                        // con la extensión `testTag`: ésta existe también como
                        // función de `Modifier` en otro paquete, y el nombre
                        // quedaría ambiguo.
                        set(SemanticsProperties.TestTag, tagFor(control))
                        role = Role.Button
                        contentDescription = control.a11yName()
                        if (editing) {
                            // En edición el control no se pulsa sino se recoloca:
                            // lo accionable es elegirlo para moverlo.
                            onClick(label = "Seleccionar ${control.a11yName()}") {
                                liveSelect(control)
                                true
                            }
                        } else {
                            val key = control.emulationKey()
                            if (key != null) {
                                // Doble toque de TalkBack = pulsación de 400 ms.
                                onClick(label = "Pulsar ${control.a11yName()}") {
                                    accessiblePress(key)
                                    true
                                }
                            } else {
                                // La cruceta es un solo control con cuatro
                                // acciones: así cada dirección se ofrece por
                                // separado sin partirla en cuatro nodos, que
                                // cambiaría cómo se leen las diagonales.
                                customActions = listOf(
                                    CustomAccessibilityAction("Cruceta arriba") {
                                        accessiblePress(GBACore.KEY_UP)
                                        true
                                    },
                                    CustomAccessibilityAction("Cruceta abajo") {
                                        accessiblePress(GBACore.KEY_DOWN)
                                        true
                                    },
                                    CustomAccessibilityAction("Cruceta izquierda") {
                                        accessiblePress(GBACore.KEY_LEFT)
                                        true
                                    },
                                    CustomAccessibilityAction("Cruceta derecha") {
                                        accessiblePress(GBACore.KEY_RIGHT)
                                        true
                                    }
                                )
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                if (editing) {
                    EditHighlight(
                        isSelected = isEditingThis,
                        modifier  = Modifier.fillMaxSize()
                    )
                }

                when (control) {
                    TouchControl.DPAD -> MinimalDPad(
                        opacity    = opacity,
                        scale      = factor,
                        interactive = !editing,
                        onDirectionChange = { up, down, left, right ->
                            core.setKey(GBACore.KEY_UP, up)
                            core.setKey(GBACore.KEY_DOWN, down)
                            core.setKey(GBACore.KEY_LEFT, left)
                            core.setKey(GBACore.KEY_RIGHT, right)
                            if (up || down || left || right) triggerHaptic()
                        }
                    )
                    TouchControl.A -> MinimalActionButton(
                        text        = "A",
                        opacity     = opacity,
                        scale       = factor,
                        accentColor = SteamCyan,
                        testTag     = tagFor(control),
                        interactive = !editing,
                        onPressChange = { pressed ->
                            core.setKey(GBACore.KEY_A, pressed)
                            if (pressed) triggerHaptic()
                        }
                    )
                    TouchControl.B -> MinimalActionButton(
                        text        = "B",
                        opacity     = opacity,
                        scale       = factor,
                        accentColor = SteamPink,
                        testTag     = tagFor(control),
                        interactive = !editing,
                        onPressChange = { pressed ->
                            core.setKey(GBACore.KEY_B, pressed)
                            if (pressed) triggerHaptic()
                        }
                    )
                    TouchControl.L -> if (showShoulderButtons) MinimalShoulderButton(
                        text = "L",
                        opacity = opacity,
                        scale = factor,
                        testTag = tagFor(control),
                        interactive = !editing,
                        onPressChange = { pressed ->
                            core.setKey(GBACore.KEY_L, pressed)
                            if (pressed) triggerHaptic()
                        }
                    )
                    TouchControl.R -> if (showShoulderButtons) MinimalShoulderButton(
                        text = "R",
                        opacity = opacity,
                        scale = factor,
                        testTag = tagFor(control),
                        interactive = !editing,
                        onPressChange = { pressed ->
                            core.setKey(GBACore.KEY_R, pressed)
                            if (pressed) triggerHaptic()
                        }
                    )
                    TouchControl.SELECT -> MinimalPillButton(
                        text = "SELECT",
                        opacity = opacity,
                        scale = factor,
                        testTag = tagFor(control),
                        interactive = !editing,
                        onPressChange = { pressed ->
                            core.setKey(GBACore.KEY_SELECT, pressed)
                            if (pressed) triggerHaptic()
                        }
                    )
                    TouchControl.START -> MinimalPillButton(
                        text = "START",
                        opacity = opacity,
                        scale = factor,
                        testTag = tagFor(control),
                        interactive = !editing,
                        onPressChange = { pressed ->
                            core.setKey(GBACore.KEY_START, pressed)
                            if (pressed) triggerHaptic()
                        }
                    )
                    // X e Y sólo se dibujan si la consola los trae en el
                    // cartucho. En Game Boy y GBA no existen, y un botón que no
                    // hace nada invita a pulsarlo sin motivo.
                    TouchControl.X -> if (showFaceButtons) MinimalActionButton(
                        text        = "X",
                        opacity     = opacity,
                        scale       = factor,
                        accentColor = SteamGreen,
                        testTag     = tagFor(control),
                        interactive = !editing,
                        onPressChange = { pressed ->
                            core.setKey(GBACore.KEY_X, pressed)
                            if (pressed) triggerHaptic()
                        }
                    )
                    TouchControl.Y -> if (showFaceButtons) MinimalActionButton(
                        text        = "Y",
                        opacity     = opacity,
                        scale       = factor,
                        accentColor = SteamGreen,
                        testTag     = tagFor(control),
                        interactive = !editing,
                        onPressChange = { pressed ->
                            core.setKey(GBACore.KEY_Y, pressed)
                            if (pressed) triggerHaptic()
                        }
                    )
                }
            }
        }
    }
}

// Tamaño de fábrica de cada control

/**
 * Tamaño visual y zona táctil de un control, en dp a escala 1.
 *
 * [minTouch] es el suelo de la zona que recibe el dedo, y suele ser mayor que el
 * dibujo: SELECT y START se pulsan con el pulgar mientras se mira la pantalla, no
 * mientras se apunta. Android pide 48 dp, así que encogerlos de más no sirve de
 * nada aunque el usuario lo intente con el pellizco.
 */
private data class ControlSpec(
    val visualWidthDp: Float,
    val visualHeightDp: Float,
    val minTouchWidthDp: Float,
    val minTouchHeightDp: Float
)

private fun specFor(control: TouchControl, compactDsPortrait: Boolean = false): ControlSpec = when (control) {
    TouchControl.DPAD   -> if (compactDsPortrait) ControlSpec(104f, 104f, 104f, 104f)
        else ControlSpec(144f, 144f, 144f, 144f)
    TouchControl.A,
    TouchControl.B,
    // X e Y miden como A y B: en la Nintendo DS los cuatro botones de cara
    // son del mismo tamaño y el dedo tiene que acertarlos igual.
    TouchControl.X,
    TouchControl.Y      -> if (compactDsPortrait) ControlSpec(48f, 48f, 48f, 48f)
        else ControlSpec(60f, 60f, 60f, 60f)
    TouchControl.L,
    TouchControl.R      -> ControlSpec(74f, 34f, 74f, 44f)
    TouchControl.START,
    TouchControl.SELECT -> ControlSpec(64f, 28f, 96f, 48f)
}

/**
 * Etiqueta de prueba de cada control.
 *
 * Vive en una sola función porque se escribe dos veces: en el nodo accesible y
 * en el componente que dibuja el botón. Con los literales sueltos, cambiar un
 * nombre rompía las pruebas en silencio.
 */
private fun tagFor(control: TouchControl): String = when (control) {
    TouchControl.DPAD   -> "btn_dpad"
    TouchControl.A      -> "btn_action_a"
    TouchControl.B      -> "btn_action_b"
    TouchControl.X      -> "btn_action_x"
    TouchControl.Y      -> "btn_action_y"
    TouchControl.L      -> "btn_shoulder_l"
    TouchControl.R      -> "btn_shoulder_r"
    TouchControl.START  -> "btn_start"
    TouchControl.SELECT -> "btn_select"
}

/**
 * Nombre que anuncia un lector de pantalla.
 *
 * `label` de [TouchControl] es "A" o "Start", que a secas no dicen qué es un
 * objeto suelto en pantalla: TalkBack necesita "Botón A" para que la navegación
 * por elementos tenga sentido sin ver la interfaz.
 */
private fun TouchControl.a11yName(): String = when (this) {
    TouchControl.DPAD   -> "Cruceta"
    TouchControl.A      -> "Botón A"
    TouchControl.B      -> "Botón B"
    TouchControl.X      -> "Botón X"
    TouchControl.Y      -> "Botón Y"
    TouchControl.L      -> "Botón L"
    TouchControl.R      -> "Botón R"
    TouchControl.START  -> "Botón Start"
    TouchControl.SELECT -> "Botón Select"
}

/**
 * Tecla de emulación que pulsa este control, o `null` si no es una tecla sola.
 *
 * La cruceta es la excepción: aprieta una distinta según la dirección, y esa
 * decisión vive en sus acciones de accesibilidad, no aquí.
 */
private fun TouchControl.emulationKey(): Int? = when (this) {
    TouchControl.DPAD   -> null
    TouchControl.A      -> GBACore.KEY_A
    TouchControl.B      -> GBACore.KEY_B
    TouchControl.X      -> GBACore.KEY_X
    TouchControl.Y      -> GBACore.KEY_Y
    TouchControl.L      -> GBACore.KEY_L
    TouchControl.R      -> GBACore.KEY_R
    TouchControl.START  -> GBACore.KEY_START
    TouchControl.SELECT -> GBACore.KEY_SELECT
}

/** Cuánto se puede encoger o agrandar un control con el pellizco. */
private const val MIN_CONTROL_SCALE = 0.45f
private const val MAX_CONTROL_SCALE = 2.6f

/**
 * Progreso del cambio de modo, de 0 (vertical) a 1 (pantalla completa).
 *
 * No es un `animateFloatAsState` porque su muelle nunca acaba de asentarse del
 * todo y los mandos se quedarían respirando en el destino. Con un tiempo fijo y
 * una curva suave, el viaje tiene principio y final, y el reposo es de verdad un
 * reposo: es lo que permite que el abombamiento del trayecto valga cero al
 * final.
 */
@Composable
private fun animateModeProgress(isLandscape: Boolean): Float {
    var target by remember { mutableStateOf(if (isLandscape) 1f else 0f) }
    LaunchedEffect(isLandscape) { target = if (isLandscape) 1f else 0f }

    val progress = remember { androidx.compose.animation.core.Animatable(target) }
    LaunchedEffect(target) {
        progress.animateTo(
            targetValue = target,
            animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing)
        )
    }
    return progress.value
}

/** Marcador visual de qué control está seleccionado al recolocar. */
@Composable
private fun EditHighlight(isSelected: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val radius = CornerRadius(14f, 14f)
        drawRoundRect(
            color = if (isSelected) SteamCyan else Color(0x33FFFFFF),
            cornerRadius = radius,
            style = Stroke(width = if (isSelected) 2.5f else 1.2f)
        )
    }
}

// Controles

@Composable
private fun MinimalShoulderButton(
    text: String,
    opacity: Float,
    scale: Float,
    testTag: String,
    interactive: Boolean,
    onPressChange: (Boolean) -> Unit
) {
    var isPressed by remember { mutableStateOf(false) }
    val pulse = rememberPressPulse()
    val pressScale = rememberPressScale(isPressed)
    val skin = rememberFrostSkin(ControlFamily.SHOULDER, isPressed, opacity)

    FrostSurface(
        shape = RoundedCornerShape(12.dp),
        skin = skin,
        pulse = pulse,
        modifier = Modifier
            .size((74 * scale).dp, (34 * scale).dp)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .testTag(testTag)
            .then(
                frostPressGesture(
                    interactive = interactive,
                    pulse = pulse,
                    onDown = { offset ->
                        isPressed = true
                        pulse.fire(offset)
                    },
                    onUp = { isPressed = false },
                    onPressChange = onPressChange
                )
            )
    ) {
        Text(
            text = text,
            color = skin.label,
            fontWeight = FontWeight.Bold,
            fontSize = (15 * scale).sp,
            letterSpacing = 1.sp
        )
    }
}

/**
 * Las cuatro direcciones que la cruceta puede estar pulsando a la vez.
 *
 * Sin parámetros es la cruceta en reposo: la usan el gesto al soltar y el
 * painter cuando ninguna dirección está tocada.
 */
private data class DPadDirection(
    val up: Boolean,
    val down: Boolean,
    val left: Boolean,
    val right: Boolean
)

@Composable
private fun MinimalDPad(
    opacity: Float,
    scale: Float,
    interactive: Boolean,
    onDirectionChange: (up: Boolean, down: Boolean, left: Boolean, right: Boolean) -> Unit
) {
    val sizePx = (144 * scale).dp

    // El dedo deja rastro: cada dirección se queda encendida mientras dure y se
    // apaga al salir. Sin esto, bajar media cruceta no da ninguna señal de hacia
    // dónde va, que es justo lo que uno no quiere en un juego de plataformas.
    var held by remember { mutableStateOf(DPadDirection(false, false, false, false)) }
    val skin = rememberFrostSkin(
        ControlFamily.DPAD,
        interactive && (held.up || held.down || held.left || held.right),
        opacity
    )

    Box(
        modifier = Modifier
            .size(sizePx)
            .testTag("dpad_control")
            // Gesto propio en vez de `detectDragGestures`: aquel no informa hasta que el
            // dedo supera el umbral de arrastre, así que un toque breve, el más natural
            // al pulsar una dirección, se quedaba sin registrar. Éste responde desde
            // el mismo contacto y mantiene la dirección mientras el dedo se desliza
            // dentro de la cruz.
            .then(
                if (interactive) Modifier.pointerInput(Unit) {
                    awaitPointerEventScope {
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val deadZone = min(size.width, size.height) * DEAD_ZONE_FRACTION
                        while (true) {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var current = directionAt(down.position, center, deadZone)
                            held = current ?: DPadDirection(false, false, false, false)
                            current?.let { onDirectionChange(it.up, it.down, it.left, it.right) }
                            down.consume()

                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (change == null || !change.pressed) {
                                    held = DPadDirection(false, false, false, false)
                                    onDirectionChange(false, false, false, false)
                                    break
                                }
                                val next = directionAt(change.position, center, deadZone)
                                if (next != current) {
                                    current = next
                                    held = next ?: DPadDirection(false, false, false, false)
                                    if (next != null) {
                                        onDirectionChange(next.up, next.down, next.left, next.right)
                                    } else {
                                        onDirectionChange(false, false, false, false)
                                    }
                                }
                                change.consume()
                            }
                        }
                    }
                } else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val armW = size.width * 0.32f
            val armH = size.height
            val radius = CornerRadius(18f, 18f)

            val verticalArm = Offset((size.width - armW) / 2f, 0f) to Size(armW, armH)
            val horizontalArm = Offset(0f, (size.height - armW) / 2f) to Size(armH, armW)

            // El vidrio va primero en los dos brazos, y la dirección que tiene el
            // dedo encima se enciende encima: es un relleno de media pieza, no
            // otro botón, y así la diagonal se ve como las dos mitades encendidas
            // a la vez sin dibujar una forma nueva para ella.
            fun glass(at: Offset, size: Size) {
                drawRoundRect(
                    brush = skin.face,
                    topLeft = at,
                    size = size,
                    cornerRadius = radius
                )
                drawRoundRect(
                    brush = skin.sheen,
                    topLeft = at,
                    size = size,
                    cornerRadius = radius
                )
            }

            fun edge(at: Offset, size: Size) {
                drawRoundRect(
                    brush = skin.edge,
                    topLeft = at,
                    size = size,
                    cornerRadius = radius,
                    style = Stroke(width = 1.5f)
                )
            }

            fun lit(at: Offset, size: Size) {
                if (size.width <= 0f || size.height <= 0f) return
                drawRoundRect(
                    color = skin.glow.copy(alpha = 0.26f * opacity),
                    topLeft = at,
                    size = size,
                    cornerRadius = radius
                )
            }

            glass(verticalArm.first, verticalArm.second)
            glass(horizontalArm.first, horizontalArm.second)

            if (held.up) lit(verticalArm.first, Size(armW, armH / 2f))
            if (held.down) lit(Offset(verticalArm.first.x, armH / 2f), Size(armW, armH / 2f))
            if (held.left) lit(horizontalArm.first, Size(armH / 2f, armW))
            if (held.right) lit(Offset(armH / 2f, horizontalArm.first.y), Size(armH / 2f, armW))

            edge(verticalArm.first, verticalArm.second)
            edge(horizontalArm.first, horizontalArm.second)

            // Centro: se hunde y no se ilumina nunca, para que la cruz se lea
            // como cuatro direcciones y no como un botón más en el medio.
            drawCircle(
                color = Color.Black.copy(alpha = 0.45f * opacity),
                radius = armW * 0.35f,
                center = center
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.10f * opacity),
                radius = armW * 0.35f,
                center = center,
                style = Stroke(width = 1.5f)
            )
        }
    }
}

/**
 * Dirección señalada por un punto de la cruz, o `null` dentro del punto muerto.
 *
 * Las diagonales se permiten: una GBA real reconoce arriba-derecha y
 * abajo-izquierda igual que los cuatro ejes sueltos.
 */
private fun directionAt(
    position: Offset,
    center: Offset,
    deadZone: Float
): DPadDirection? {
    val dx = position.x - center.x
    val dy = position.y - center.y
    if (hypot(dx, dy) < deadZone) return null

    val angle = Math.toDegrees(atan2(dy, dx).toDouble())
    return DPadDirection(
        up = angle in -157.5..-22.5,
        down = angle in 22.5..157.5,
        left = angle !in -112.5..112.5,
        right = angle in -67.5..67.5
    )
}

/** Fracción del lado menor de la cruz que se considera punto muerto. */
private const val DEAD_ZONE_FRACTION = 0.13f

@Composable
private fun MinimalActionButton(
    text: String,
    opacity: Float,
    scale: Float,
    accentColor: Color,
    testTag: String,
    interactive: Boolean,
    onPressChange: (Boolean) -> Unit
) {
    var isPressed by remember { mutableStateOf(false) }
    val pulse = rememberPressPulse()
    val pressScale = rememberPressScale(isPressed)
    // La familia decide el color de marca, y [accentColor] deja que quien llama
    // lo desvíe: el editor pinta las miniaturas con su propio tono y así la
    // pieza tiene un solo material.
    val skin = rememberFrostSkin(ControlFamily.ACTION, isPressed, opacity, tint = accentColor)
    // 60 en vez de 54: a escala 1 da 49 dp, por encima del mínimo cómodo de
    // 48 dp; con 54 quedaban en 44 y se pulsaban con dificultad.
    val size = (60 * scale).dp

    FrostSurface(
        shape = CircleShape,
        skin = skin,
        pulse = pulse,
        modifier = Modifier
            .size(size)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .testTag(testTag)
            .then(
                frostPressGesture(
                    interactive = interactive,
                    pulse = pulse,
                    onDown = { offset ->
                        isPressed = true
                        pulse.fire(offset)
                    },
                    onUp = { isPressed = false },
                    onPressChange = onPressChange
                )
            )
    ) {
        Text(
            text = text,
            color = skin.label,
            fontWeight = FontWeight.Bold,
            fontSize = (19 * scale).sp
        )
    }
}

@Composable
private fun MinimalPillButton(
    text: String,
    opacity: Float,
    scale: Float,
    testTag: String,
    interactive: Boolean,
    onPressChange: (Boolean) -> Unit
) {
    var isPressed by remember { mutableStateOf(false) }
    val pulse = rememberPressPulse()
    val pressScale = rememberPressScale(isPressed)
    val skin = rememberFrostSkin(ControlFamily.SYSTEM, isPressed, opacity)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag(testTag)
            .then(
                frostPressGesture(
                    interactive = interactive,
                    pulse = pulse,
                    onDown = { offset ->
                        isPressed = true
                        pulse.fire(offset)
                    },
                    onUp = { isPressed = false },
                    onPressChange = onPressChange
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        // La onda se pinta en la cápsula, no en la zona táctil entera: la zona
        // es más ancha que el botón (Android pide 48 dp de alto) y si la luz
        // saliera de ella se vería el halo flotando fuera del vidrio.
        FrostSurface(
            shape = RoundedCornerShape(12.dp),
            skin = skin,
            pulse = pulse,
            modifier = Modifier
                .size((64 * scale).dp, (28 * scale).dp)
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                }
        ) {
            Text(
                text = text,
                color = skin.label,
                fontWeight = FontWeight.SemiBold,
                fontSize = (10 * scale).sp,
                letterSpacing = 0.5.sp
            )
        }
    }
}
