package com.example.ui.components

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.GBACore
import com.example.data.TouchControl
import com.example.data.TouchLayout
import com.example.data.TouchPlacement
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Mandando en pantalla para AS GBA Emulator.
 *
 * Cada control se coloca por fracción (ver [TouchLayout]) y no con `align` ni con
 * `SpaceBetween`, porque así se pueden arrastrar sueltos y guardarlos. En modo
 * [editing] los mismos controles pasan a ser manejables con el dedo y dejan de
 * pulsarse: mover un botón y disparar ese botón a la vez no pueden ser el mismo
 * gesto.
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

    // Tamaño real de la zona de controles, en píxeles. Las fracciones se
    // convierten aquí, con las medidas de verdad, en vez de suponer un ancho de
    // referencia: así el mismo reparto encaja en cualquier pantalla.
    var areaWidth by remember { mutableStateOf(0) }
    var areaHeight by remember { mutableStateOf(0) }

    // En vertical no se consulta nada guardado: el reparto de fábrica es el
    // definitivo porque el editor no se abre en ese modo, y así una distribución
    // guardada antes de que existiera la puerta tampoco puede dejar botones
    // movidos sin forma de devolverlos.
    val placements = if (isLandscape) layout.forOrientation(true) else emptyMap()

    // El reparto de fábrica se calcula aquí, con la zona y el ajuste de tamaño reales, y
    // no como constantes: es el mismo cálculo que hacía la composición original, así que
    // un control sin tocar se dibuja igual que antes de que existiera el editor.
    val factoryPlacements = remember(areaWidth, areaHeight, isLandscape, scale, density) {
        if (areaWidth <= 0 || areaHeight <= 0) {
            emptyMap()
        } else {
            TouchControl.entries.associateWith { control ->
                TouchLayout.legacyDefaultFor(
                    control = control,
                    isLandscape = isLandscape,
                    areaWidthDp = areaWidth / density.density,
                    areaHeightDp = areaHeight / density.density,
                    globalScale = scale
                )
            }
        }
    }

    // El gesto necesita leer durante el arrastre, y el bloque de `pointerInput` no se
    // reinicia en cada recomposición: capturar aquí el valor lo congelaría en el
    // primer fotograma y el botón iría y volvería a su sitio en vez de seguir al dedo.
    val livePlacements by rememberUpdatedState(placements)
    val liveFactory by rememberUpdatedState(factoryPlacements)
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
            .pointerInput(editing) {
                if (!editing) return@pointerInput
                awaitEachGesture {
                    var target: TouchControl? = null
                    var base: TouchPlacement? = null
                    var totalZoom = 1f
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed < 2) {
                            // Con un solo dedo el arrastre lo lleva el botón que
                            // se pulsó. Al soltar uno de los dos, el pellizco se
                            // da por terminado.
                            if (target != null) break
                            continue
                        }
                        // El control a redimensionar es el que ya está elegido,
                        // que lo elige el dedo que está sobre él.
                        if (target == null) {
                            val control = liveSelected
                            val start = control?.let { livePlacements[it] ?: liveFactory[it] }
                            if (control == null || start == null) continue
                            target = control
                            base = start
                            totalZoom = 1f
                        }
                        totalZoom *= event.calculateZoom()
                        val current = base ?: continue
                        liveChange(
                            target!!,
                            current.copy(
                                scale = (current.scale * totalZoom).coerceIn(
                                    MIN_CONTROL_SCALE,
                                    MAX_CONTROL_SCALE
                                )
                            )
                        )
                        event.changes.forEach { if (it.pressed) it.consume() }
                        if (event.changes.none { it.pressed }) break
                    }
                }
            }
    ) {
        if (areaWidth <= 0 || areaHeight <= 0) return@Box

        // La zona en dp, que es donde están escritas las medidas de fábrica.
        val areaWidthDp = areaWidth / density.density
        val areaHeightDp = areaHeight / density.density

        for (control in TouchControl.entries) {
            val placement = placements[control] ?: factoryPlacements[control]
                ?: continue

            // El ajuste global de Ajustes y el de este control se multiplican: uno
            // agranda todos a la vez y el otro toca sólo este.
            val factor = scale * placement.scale
            val spec = specFor(control)
            val hitWidthDp = max(spec.visualWidthDp * factor, spec.minTouchWidthDp)
            val hitHeightDp = max(spec.visualHeightDp * factor, spec.minTouchHeightDp)

            val clamped = placement.clamped(
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
                                val start = livePlacements[control] ?: liveFactory[control]
                                    ?: return@awaitEachGesture
                                var totalPan = Offset.Zero
                                down.consume()

                                while (true) {
                                    val event = awaitPointerEvent()
                                    val hayPellizco = event.changes.count { it.pressed } > 1
                                    if (!hayPellizco) {
                                        // `calculatePan` ya toma el centroide del
                                        // propio evento, que es lo que hace el
                                        // detector oficial. El tamaño lo lleva el
                                        // gesto de zona de arriba: aquí sólo se
                                        // mueve el botón.
                                        totalPan += event.calculatePan()
                                        liveChange(
                                            control,
                                            start.copy(
                                                x = start.x + totalPan.x / areaWidth,
                                                y = start.y + totalPan.y / areaHeight
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
                    ),
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
                        accentColor = Color(0xFF8C7AE6),
                        testTag     = "btn_action_a",
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
                        accentColor = Color(0xFFE28FA8),
                        testTag     = "btn_action_b",
                        interactive = !editing,
                        onPressChange = { pressed ->
                            core.setKey(GBACore.KEY_B, pressed)
                            if (pressed) triggerHaptic()
                        }
                    )
                    TouchControl.L -> MinimalShoulderButton(
                        text = "L",
                        opacity = opacity,
                        scale = factor,
                        testTag = "btn_shoulder_l",
                        interactive = !editing,
                        onPressChange = { pressed ->
                            core.setKey(GBACore.KEY_L, pressed)
                            if (pressed) triggerHaptic()
                        }
                    )
                    TouchControl.R -> MinimalShoulderButton(
                        text = "R",
                        opacity = opacity,
                        scale = factor,
                        testTag = "btn_shoulder_r",
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
                        testTag = "btn_select",
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
                        testTag = "btn_start",
                        interactive = !editing,
                        onPressChange = { pressed ->
                            core.setKey(GBACore.KEY_START, pressed)
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

private fun specFor(control: TouchControl): ControlSpec = when (control) {
    TouchControl.DPAD   -> ControlSpec(144f, 144f, 144f, 144f)
    TouchControl.A,
    TouchControl.B      -> ControlSpec(60f, 60f, 60f, 60f)
    TouchControl.L,
    TouchControl.R      -> ControlSpec(74f, 34f, 74f, 44f)
    TouchControl.START,
    TouchControl.SELECT -> ControlSpec(64f, 28f, 96f, 48f)
}

/** Cuánto se puede encoger o agrandar un control con el pellizco. */
private const val MIN_CONTROL_SCALE = 0.45f
private const val MAX_CONTROL_SCALE = 2.6f

/** Marcador visual de qué control está seleccionado al recolocar. */
@Composable
private fun EditHighlight(isSelected: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val radius = CornerRadius(14f, 14f)
        drawRoundRect(
            color = if (isSelected) Color(0xFF66C0F4) else Color(0x33FFFFFF),
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

    Box(
        modifier = Modifier
            .size((74 * scale).dp, (34 * scale).dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isPressed) Color(0xFF8C7AE6).copy(alpha = opacity)
                else Color(0x22FFFFFF).copy(alpha = opacity * 0.4f)
            )
            .border(
                1.dp,
                if (isPressed) Color(0xFFB9AEEA).copy(alpha = opacity)
                else Color(0x35FFFFFF).copy(alpha = opacity * 0.6f),
                RoundedCornerShape(12.dp)
            )
            .testTag(testTag)
            .then(pressGesture(interactive, { isPressed = true }, { isPressed = false }, onPressChange)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = (15 * scale).sp,
            letterSpacing = 1.sp
        )
    }
}

/** Las cuatro direcciones que la cruceta puede estar pulsando a la vez. */
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
                            var held = directionAt(down.position, center, deadZone)
                            held?.let { onDirectionChange(it.up, it.down, it.left, it.right) }
                            down.consume()

                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (change == null || !change.pressed) {
                                    onDirectionChange(false, false, false, false)
                                    break
                                }
                                val next = directionAt(change.position, center, deadZone)
                                if (next != held) {
                                    held = next
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
            val glassBg = Color(0x28FFFFFF).copy(alpha = opacity * 0.4f)
            val borderCol = Color(0x55FFFFFF).copy(alpha = opacity * 0.7f)

            // Brazo vertical
            drawRoundRect(
                color = glassBg,
                topLeft = Offset((size.width - armW) / 2f, 0f),
                size = Size(armW, armH),
                cornerRadius = CornerRadius(18f, 18f)
            )
            drawRoundRect(
                color = borderCol,
                topLeft = Offset((size.width - armW) / 2f, 0f),
                size = Size(armW, armH),
                cornerRadius = CornerRadius(18f, 18f),
                style = Stroke(width = 1.5f)
            )

            // Brazo horizontal
            drawRoundRect(
                color = glassBg,
                topLeft = Offset(0f, (size.height - armW) / 2f),
                size = Size(armH, armW),
                cornerRadius = CornerRadius(18f, 18f)
            )
            drawRoundRect(
                color = borderCol,
                topLeft = Offset(0f, (size.height - armW) / 2f),
                size = Size(armH, armW),
                cornerRadius = CornerRadius(18f, 18f),
                style = Stroke(width = 1.5f)
            )

            // Centro metálico
            drawCircle(
                color = Color.Black.copy(alpha = opacity * 0.6f),
                radius = armW * 0.35f,
                center = center
            )
            drawCircle(
                color = Color.White.copy(alpha = opacity * 0.4f),
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
    // 60 en vez de 54: a escala 1 da 49 dp, por encima del mínimo cómodo de
    // 48 dp; con 54 quedaban en 44 y se pulsaban con dificultad.
    val size = (60 * scale).dp

    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(
                if (isPressed) accentColor.copy(alpha = opacity)
                else accentColor.copy(alpha = opacity * 0.3f)
            )
            .border(
                1.5.dp,
                if (isPressed) Color.White.copy(alpha = opacity)
                else accentColor.copy(alpha = opacity * 0.8f),
                CircleShape
            )
            .testTag(testTag)
            .then(pressGesture(interactive, { isPressed = true }, { isPressed = false }, onPressChange)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag(testTag)
            .then(pressGesture(interactive, { isPressed = true }, { isPressed = false }, onPressChange)),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size((64 * scale).dp, (28 * scale).dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (isPressed) Color(0xFF8C7AE6).copy(alpha = opacity)
                    else Color(0x22FFFFFF).copy(alpha = opacity * 0.4f)
                )
                .border(
                    1.dp,
                    if (isPressed) Color(0xFFB9AEEA).copy(alpha = opacity)
                    else Color(0x40FFFFFF).copy(alpha = opacity * 0.6f),
                    RoundedCornerShape(12.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                color = Color.White.copy(alpha = 0.9f),
                fontWeight = FontWeight.SemiBold,
                fontSize = (10 * scale).sp,
                letterSpacing = 0.5.sp
            )
        }
    }
}

/**
 * El gesto de pulsar, o nada si el control está en modo recolocar.
 *
 * Se construye aparte para poder dejar el `pointerInput` sin colgar: en edición
 * el dedo mueve el botón, y si además pulsara, mover el A dispararía el A.
 */
private fun pressGesture(
    interactive: Boolean,
    onDown: () -> Unit,
    onUp: () -> Unit,
    onPressChange: (Boolean) -> Unit
): Modifier = if (!interactive) Modifier else Modifier.pointerInput(Unit) {
    detectTapGestures(
        onPress = {
            onDown()
            onPressChange(true)
            tryAwaitRelease()
            onUp()
            onPressChange(false)
        }
    )
}
