package com.example.core.cheats

/**
 * Resultado de analizar una lista de códigos de trucos.
 *
 * [ops] sólo se usa si [accepted] es `true`: cuando hay [errors] la lista se
 * abortó a mitad y aplicar el prefijo entendido dejaría escrituras sin la
 * condición que las gobernaba —peor que no aplicar nada—. Los [warnings], en
 * cambio, son líneas concretas que se omitieron (parches de ROM, hooks,
 * escrituras en zona no escribible): el resto de la lista sigue siendo válido.
 */
data class CheatParseResult(
    val ops: List<CheatOp>,
    val warnings: List<String>,
    val errors: List<String>,
) {
    val accepted: Boolean get() = errors.isEmpty()

    /** Sin operaciones ni errores: la lista estaba vacía o sólo tenía avisos. */
    val isEmpty: Boolean get() = ops.isEmpty() && errors.isEmpty()
}

/**
 * Decodificador de listas de códigos de trucos (CodeBreaker / GameShark) a
 * [CheatOp], en la forma que espera el núcleo nativo.
 *
 * ## Por qué es un puerto de mGBA y no una implementación libre
 *
 * La semántica de una lista de trucos no es obvia: un "if" no es una línea
 * aislada sino que gobierna las siguientes, y cuántas depende del propio código
 * (un `repeat` de GameShark viaja en los datos). Reescribirlo a ojo produce
 * códigos que no hacen nada o que se rompen a los pocos frames. Por eso el
 * decodificador replica `gameshark.c`, `codebreaker.c`, `parv3.c` y la parte de
 * puntuación de direcciones de `cheats.c` de mGBA, incluida la autodetección
 * (`GBACheatAddAutodetect`) con su orden exacto de candidatos: es esa función la
 * que decide si una lista viene cifrada con TEA o va en claro.
 *
 * ## Formatos aceptados
 *
 * - `XXXXXXXX XXXX` → CodeBreaker (8 + 4 dígitos hexadecimales)
 * - `XXXXXXXX XXXXXXXX` → GameShark (8 + 8), cifrado o en claro, autodetectado
 *
 * ## Qué se rechaza y por qué
 *
 * - **Action Replay PARv3**: se detecta (su formato es indistinguible del de
 *   GameShark salvo por puntuación) pero no se implementa; se avisa en vez de
 *   adivinar, porque un PAR mal interpretado escribe en direcciones falsas.
 * - **Reseed** (`DEADFACE`, tipo 9 de CodeBreaker): cambia las semillas de las
 *   líneas siguientes; sin implementarlo, el resto de la lista se descifraría
 *   con semillas equivocadas y fallaría en silencio.
 * - **FILL / FILL_LIST / GSA_ASSIGN_LIST**: consumen un número variable de
 *   líneas siguientes declarado en el propio código. Saltárselas dejaría
 *   escrituras huérfanas, así que se aborta la lista entera.
 * - **Hooks, parches de ROM y códigos por botón**: requieren modificar el ROM o
 *   el flujo de ejecución; se omiten con aviso y el resto sigue aplicándose.
 */
object CheatParser {

    /**
     * Analiza [text] (una lista de códigos, uno por línea) y devuelve las
     * operaciones a enviar al núcleo.
     *
     * No lanza excepciones: cualquier problema se reporta en
     * [CheatParseResult.errors] / [CheatParseResult.warnings] con el número de
     * línea, porque quien muestra el resultado es la UI y necesita texto.
     */
    fun parse(text: String): CheatParseResult {
        val builder = Builder()
        builder.parse(text)
        return builder.result()
    }

    // --- Tipos de código --------------------------------------------------
    // Constantes enteras, no enum: el tipo es el nibble alto de op1 y el `when`
    // de decodificación lo necesita como número.

    // CodeBreaker (enum GBACodeBreakerType de mGBA).
    private const val CB_GAME_ID = 0x0
    private const val CB_HOOK = 0x1
    private const val CB_OR_2 = 0x2
    private const val CB_ASSIGN_1 = 0x3
    private const val CB_FILL = 0x4
    private const val CB_FILL_LIST = 0x5
    private const val CB_AND_2 = 0x6
    private const val CB_IF_EQ = 0x7
    private const val CB_ASSIGN_2 = 0x8
    private const val CB_ENCRYPT = 0x9
    private const val CB_IF_NE = 0xA
    private const val CB_IF_GT = 0xB
    private const val CB_IF_LT = 0xC
    private const val CB_IF_SPECIAL = 0xD
    private const val CB_ADD_2 = 0xE
    private const val CB_IF_AND = 0xF

    // GameShark (enum GBAGameSharkType de mGBA).
    private const val GSA_ASSIGN_1 = 0x0
    private const val GSA_ASSIGN_2 = 0x1
    private const val GSA_ASSIGN_4 = 0x2
    private const val GSA_ASSIGN_LIST = 0x3
    private const val GSA_PATCH = 0x6
    private const val GSA_BUTTON = 0x8
    private const val GSA_IF = 0xD
    private const val GSA_IF_RANGE = 0xE
    private const val GSA_HOOK = 0xF

    /** Subtipos de comparación de GSA_IF (`GBAGameSharkIfType`), dentro de op2. */
    private const val GSA_IF_EQ = 0
    private const val GSA_IF_NE = 1
    private const val GSA_IF_LE = 2
    private const val GSA_IF_GE = 3

    /**
     * Regiones donde el bus de la GBA admite escritura: EWRAM, IWRAM, OAM,
     * VRAM, paletas y SRAM. Quedan fuera IO (0x04, escribir ahí dispararía
     * efectos colaterales en registros), ROM y BIOS — y también la región 0x01,
     * que no existe en la GBA real y sólo aparece en códigos truchados.
     */
    private val WRITABLE_REGIONS = setOf(0x02, 0x03, 0x05, 0x06, 0x07, 0x0E, 0x0F)

    /** KEYINPUT: registro de botones, de lectura y con lógica activa a nivel bajo. */
    private const val KEYINPUT = 0x04000130

    /**
     * Marcador de cabecera de lista (`0x001DC0DE` en op2). mGBA le da la
     * puntuación máxima —0x100— porque en la práctica sólo aparece en listas
     * bien formadas; aquí se acepta como línea sin efecto.
     */
    private const val CODE_MARK = 0x001DC0DE

    /** Semillas TEA de GameShark (`GBACheatGameSharkSeeds`). */
    private val GSA_SEEDS = intArrayOf(
        0x09F4FBBD.toInt(),
        0x9681884A.toInt(),
        0x352027E9.toInt(),
        0xF3DEE5A7.toInt(),
    )

    /** Semillas TEA de Action Replay v3 (`GBACheatProActionReplaySeeds`). */
    private val PAR_SEEDS = intArrayOf(
        0x7AA9648F.toInt(),
        0x7FAE6994.toInt(),
        0xC0EFAAD5.toInt(),
        0x42712C57.toInt(),
    )

    /**
     * Candidatos de la autodetección, en el orden en que se puntúan. El orden
     * importa: mGBA se queda con el primer máximo y usa comparación estricta,
     * así que un empate favorece a GameShark cifrado sobre todo lo demás.
     */
    private enum class GsaCandidate {
        /** Descifrado con semillas de GameShark. */
        GSA_ENCRYPTED,

        /** Descifrado con semillas de Action Replay v3. */
        PAR_ENCRYPTED,

        /** En claro, interpretado como GameShark. */
        GSA_RAW,

        /** En claro, interpretado como Action Replay v3. */
        PAR_RAW,
    }

    /** Acumulador de una llamada a [parse]; mantiene el estado entre líneas. */
    private class Builder {
        private val ops = mutableListOf<CheatOp>()
        private val warnings = mutableListOf<String>()
        private val errors = mutableListOf<String>()

        /**
         * `true` en cuanto una línea obliga a abandonar la lista; el resto de
         * líneas deja de interpretarse, porque seguir daría un prefijo sin
         * sentido (ver [CheatParseResult]).
         */
        private var aborted = false

        /**
         * Variante de la lista GameShark, decidida por su primera línea. Se
         * recuerda porque el descifrado es el mismo para todas las líneas de la
         * lista (los reseeds, que lo cambiarían, abortan).
         */
        private var gsaCandidate: GsaCandidate? = null

        fun parse(text: String) {
            var lineNumber = 0
            for (rawLine in text.lineSequence()) {
                ++lineNumber
                if (aborted) return
                parseLine(lineNumber, rawLine)
            }
        }

        fun result() = CheatParseResult(ops.toList(), warnings.toList(), errors.toList())

        private fun parseLine(lineNumber: Int, rawLine: String) {
            val line = rawLine.trim()
            if (line.isEmpty()) return
            // Comentarios: el pegado de códigos suele traer cabecera y notas.
            if (line.startsWith("#") || line.startsWith("//")) return

            val tokens = line.split(WHITESPACE)
            val first = tokens[0]
            val second = if (tokens.size > 1) tokens[1] else ""
            if (!HEX8.matches(first) || (!HEX4.matches(second) && !HEX8.matches(second))) {
                // Una línea que no contiene ningún código (título de la lista,
                // nota del autor) se salta; una línea que parece un código
                // cortado o corrupto aborta, porque no se sabe qué parte de la
                // estructura de control se ha perdido.
                if (containsCodeToken(line)) {
                    abort(
                        lineNumber,
                        "línea con formato de código incompleto: «$line» (se espera " +
                            "«XXXXXXXX XXXX» para CodeBreaker o «XXXXXXXX XXXXXXXX» " +
                            "para GameShark)",
                    )
                } else {
                    warn(lineNumber, "línea ignorada, no parece un código: «$line»")
                }
                return
            }

            val op1 = first.toLong(16).toInt()
            if (HEX4.matches(second)) {
                // 4 dígitos ⇒ CodeBreaker; op2 cabe en 16 bits.
                decodeCodeBreaker(lineNumber, op1, second.toInt(16))
            } else {
                decodeGameShark(lineNumber, op1, second.toLong(16).toInt())
            }
        }

        // --- CodeBreaker ---------------------------------------------------

        /**
         * Decodifica una línea `XXXXXXXX XXXX`.
         *
         * En CodeBreaker todas las condiciones son de 16 bits sobre
         * `op1 and 0x0FFFFFFF` y saltan una operación cuando fallan, así que
         * `skipOnFail` vale 1 en todos los casos, incluido el de botones.
         */
        private fun decodeCodeBreaker(lineNumber: Int, op1: Int, op2: Int) {
            val address = op1 and 0x0FFFFFFF
            when (op1 ushr 28) {
                CB_GAME_ID, CB_HOOK -> warn(
                    lineNumber,
                    "código de identificación de juego o de hook ignorado " +
                        "(requiere modificar el ROM)",
                )

                CB_OR_2 -> emitWrite(lineNumber, address, op2, CheatKind.OR, 2)
                CB_ASSIGN_1 -> emitWrite(lineNumber, address, op2, CheatKind.WRITE, 1)
                CB_ASSIGN_2 -> emitWrite(lineNumber, address, op2, CheatKind.WRITE, 2)
                CB_AND_2 -> emitWrite(lineNumber, address, op2, CheatKind.AND, 2)
                CB_ADD_2 -> emitWrite(lineNumber, address, op2, CheatKind.ADD, 2)

                CB_IF_EQ -> emitCondition(address, op2, CheatKind.IF_EQ, 1)
                CB_IF_NE -> emitCondition(address, op2, CheatKind.IF_NE, 1)
                CB_IF_GT -> emitCondition(address, op2, CheatKind.IF_GT, 1)
                CB_IF_LT -> emitCondition(address, op2, CheatKind.IF_LT, 1)
                CB_IF_AND -> emitCondition(address, op2, CheatKind.IF_AND, 1)

                // El único "if especial" que mGBA implementa es la espera por
                // botones: dirección fija KEYINPUT y máscara en el operando.
                CB_IF_SPECIAL -> if (address == 0x20) {
                    emitCondition(KEYINPUT, op2, CheatKind.IF_NAND, 1)
                } else {
                    warn(
                        lineNumber,
                        "condición especial de CodeBreaker desconocida (operando " +
                            "0x%08X): línea omitida".format(address),
                    )
                }

                CB_FILL, CB_FILL_LIST -> abort(
                    lineNumber,
                    "los códigos FILL/FILL_LIST de CodeBreaker consumen un número " +
                        "variable de líneas siguientes y no se soportan; divide la " +
                        "lista en códigos sueltos.",
                )

                CB_ENCRYPT -> abort(
                    lineNumber,
                    "el cifrado de CodeBreaker (tipo 9) cambia las semillas de las " +
                        "líneas siguientes y no se soporta.",
                )

                else -> abort(lineNumber, "tipo de código CodeBreaker desconocido.")
            }
        }

        // --- GameShark -----------------------------------------------------

        /**
         * Decodifica una línea `XXXXXXXX XXXXXXXX`.
         *
         * La variante (cifrada o en claro) se decide con la primera línea de
         * este formato y se reutiliza para el resto, igual que en mGBA.
         */
        private fun decodeGameShark(lineNumber: Int, op1: Int, op2: Int) {
            // `DEADFACE` es un reseed: cambia las semillas de todo lo que venga
            // después. Se comprueba antes que la variante para poder dar el
            // mensaje útil en vez de "esto es un Action Replay".
            if (op1 == DEAD_FACE) {
                abort(lineNumber, RESEED_MESSAGE)
                return
            }

            val candidate = gsaCandidate ?: detectCandidate(lineNumber, op1, op2) ?: return
            gsaCandidate = candidate
            val (plainOp1, plainOp2) = when (candidate) {
                GsaCandidate.GSA_ENCRYPTED -> teaDecrypt(op1, op2, GSA_SEEDS)
                else -> op1 to op2
            }
            if (plainOp1 == DEAD_FACE) {
                abort(lineNumber, RESEED_MESSAGE)
                return
            }

            val address = plainOp1 and 0x0FFFFFFF
            when (plainOp1 ushr 28) {
                GSA_ASSIGN_1 -> emitWrite(lineNumber, address, plainOp2, CheatKind.WRITE, 1)
                GSA_ASSIGN_2 -> emitWrite(lineNumber, address, plainOp2, CheatKind.WRITE, 2)
                GSA_ASSIGN_4 -> emitWrite(lineNumber, address, plainOp2, CheatKind.WRITE, 4)

                // El tipo de comparación va en los bits 20-23 de op2. La
                // comparación es de 16 bits con cero a la izquierda, así que
                // `<` y `<=` coinciden con sus equivalentes con signo.
                GSA_IF -> {
                    val kind = when (plainOp2 ushr 20) {
                        GSA_IF_EQ -> CheatKind.IF_EQ
                        GSA_IF_NE -> CheatKind.IF_NE
                        GSA_IF_LE -> CheatKind.IF_LE
                        GSA_IF_GE -> CheatKind.IF_GE
                        else -> null
                    }
                    if (kind == null) {
                        warn(
                            lineNumber,
                            "condición GameShark de tipo %X desconocido: línea omitida"
                                .format(plainOp2 ushr 20),
                        )
                    } else {
                        emitCondition(address, plainOp2 and 0xFFFF, kind, 1)
                    }
                }

                // Rango: la dirección está en op2 y el número de operaciones que
                // gobierna la condición viaja en los bits 16-23 de op1. Con
                // repeat = 0 no gobierna nada, que es lo mismo que no tenerla;
                // se emite igualmente y el núcleo no salta ninguna.
                GSA_IF_RANGE -> emitCondition(
                    plainOp2 and 0x0FFFFFFF,
                    plainOp1 and 0xFFFF,
                    CheatKind.IF_EQ,
                    (plainOp1 ushr 16) and 0xFF,
                )

                GSA_ASSIGN_LIST -> abort(
                    lineNumber,
                    "los códigos en lista de GameShark (tipo 3) consumen un número " +
                        "variable de líneas siguientes y no se soportan; divide la " +
                        "lista en códigos sueltos.",
                )

                GSA_PATCH -> warn(
                    lineNumber,
                    "parche de ROM de GameShark ignorado (requiere modificar el ROM).",
                )

                GSA_BUTTON -> warn(
                    lineNumber,
                    "código por botón de GameShark ignorado: la línea siguiente es " +
                        "su dato y no se sabe interpretarla sin él.",
                )

                GSA_HOOK -> warn(
                    lineNumber,
                    "hook de GameShark ignorado (requiere modificar el ROM).",
                )

                else -> abort(
                    lineNumber,
                    "tipo de código GameShark %X desconocido.".format(plainOp1 ushr 28),
                )
            }
        }

        /**
         * Decide la variante de la lista, o `null` (registrando el error) si lo
         * que parecía una lista GameShark es en realidad un Action Replay v3.
         *
         * Réplica de `GBACheatAddAutodetect`: se puntúa la línea descifrada con
         * semillas de GameShark, descifrada con semillas de Action Replay, y en
         * claro de ambas formas, quedándose con la puntuación mayor.
         */
        private fun detectCandidate(lineNumber: Int, op1: Int, op2: Int): GsaCandidate? {
            val gameSharkDecrypted = teaDecrypt(op1, op2, GSA_SEEDS)
            val actionReplayDecrypted = teaDecrypt(op1, op2, PAR_SEEDS)

            var best = GsaCandidate.GSA_ENCRYPTED
            var bestScore = gsaScore(gameSharkDecrypted.first, gameSharkDecrypted.second)

            val parEncrypted = parScore(actionReplayDecrypted.first, actionReplayDecrypted.second)
            if (parEncrypted > bestScore) {
                best = GsaCandidate.PAR_ENCRYPTED
                bestScore = parEncrypted
            }

            val gameSharkRaw = gsaScore(op1, op2)
            if (gameSharkRaw > bestScore) {
                best = GsaCandidate.GSA_RAW
                bestScore = gameSharkRaw
            }

            val parRaw = parScore(op1, op2)
            if (parRaw > bestScore) {
                best = GsaCandidate.PAR_RAW
            }

            return when (best) {
                GsaCandidate.PAR_ENCRYPTED, GsaCandidate.PAR_RAW -> {
                    abort(
                        lineNumber,
                        "esta lista parece Action Replay v3 (PAR3), un formato que no " +
                            "se soporta; usa códigos GameShark o CodeBreaker.",
                    )
                    null
                }

                else -> best
            }
        }

        // --- Emisión de operaciones -----------------------------------------

        /**
         * Añade una escritura tras comprobar que el destino es escribible.
         *
         * Se omite con aviso en vez de abortar porque el resto de la lista no
         * depende de esta línea: sobrar una escritura no desalinea la estructura
         * de control. Al revés que una condición, que sí lo gobierna todo: ésas
         * se emiten siempre (ver [emitCondition]).
         */
        private fun emitWrite(
            lineNumber: Int,
            address: Int,
            value: Int,
            kind: CheatKind,
            width: Int,
        ) {
            val region = address ushr 24
            if (region !in WRITABLE_REGIONS) {
                warn(
                    lineNumber,
                    "escritura en 0x%08X ignorada: la región %02X no es memoria " +
                        "escribible.".format(address, region),
                )
                return
            }
            ops += CheatOp(address, value, kind, width, 0)
        }

        /**
         * Añade una condición de 16 bits.
         *
         * Se emite incluso si la dirección no cae en una región válida: el
         * núcleo la lee, falla (no es memoria real) y por tanto la condición no
         * se cumple, con lo que se salta exactamente `skipOnFail` operaciones.
         * Omitirla sería peor: dejaría pasar las escrituras que dependían de
         * ella, justo lo contrario de lo que el código pide.
         */
        private fun emitCondition(address: Int, value: Int, kind: CheatKind, skipOnFail: Int) {
            ops += CheatOp(address, value, kind, 2, skipOnFail)
        }

        private fun warn(lineNumber: Int, message: String) {
            warnings += "Línea $lineNumber: $message"
        }

        private fun abort(lineNumber: Int, message: String) {
            errors += "Línea $lineNumber: $message"
            aborted = true
        }
    }

    // --- Puntuaciones (puerto de `cheats.c` y `parv3.c` de mGBA) -----------

    /**
     * Cuánto se parece una pareja de operandos a un código GameShark real.
     *
     * No es una heurística propia: es `GBACheatGameSharkProbability`, la misma
     * que usa mGBA para autodetectar el formato. Se conserva tal cual,
     * incluidos los `+0x20` por tipo y los castigos por direcciones imposibles,
     * porque cambiarla cambiaría qué listas se detectan.
     */
    private fun gsaScore(op1: Int, op2: Int): Int {
        if (op2 == CODE_MARK) return 0x100
        val address = op1 and 0x0FFFFFFF
        var score = 0
        when (op1 ushr 28) {
            GSA_ASSIGN_1 -> {
                score += 0x20
                if (op2 and MASK_FFFFFF00 != 0) score -= 0x10
                score += addressScore(address)
            }

            GSA_ASSIGN_2 -> {
                score += 0x20
                if (op2 and MASK_FFFF0000 != 0) score -= 0x10
                score += addressScore(address)
            }

            GSA_ASSIGN_4 -> {
                score += 0x20
                score += addressScore(address)
            }

            GSA_PATCH -> {
                score += 0x20
                if (op2 and MASK_CFFF0000 != 0) score -= 0x10
            }

            GSA_BUTTON -> score += 0x10

            GSA_IF -> {
                score += 0x20
                if (op2 and MASK_FFCF0000 != 0) score -= 0x10
                score += addressScore(address)
            }

            // Aquí la dirección está en op2, sin máscara, igual que en mGBA.
            GSA_IF_RANGE -> {
                score += 0x20
                score += addressScore(op2)
                if (op1 and 0x0F000000 != 0) score -= 0x10
            }

            GSA_HOOK -> {
                score += 0x20
                if (op2 and MASK_FFFF0000 != 0) score -= 0x10
            }

            else -> score -= 0x40
        }
        return score
    }

    /**
     * Versión para Action Replay v3 (`GBACheatProActionReplayProbability`).
     *
     * Sólo sirve para distinguir GameShark de Action Replay: si gana, la lista
     * se rechaza con un mensaje claro, así que no hace falta replicar la
     * decodificación de PAR línea a línea.
     */
    private fun parScore(op1: Int, op2: Int): Int {
        if (op2 == CODE_MARK) return 0x100
        if (op1 == DEAD_FACE && op2 and MASK_FFFF0000 == 0) return 0x100

        // op1 == 0: línea de servicio (fill, parche, endif...). El nibble alto
        // de op2 dice cuál y la dirección se saca de op2.
        if (op1 == 0) {
            var score = 0x40
            val address = parAddress(op2)
            when (op2 and MASK_FE000000) {
                PAR_FILL_1, PAR_FILL_2, PAR_FILL_4 -> score += addressScore(address)
                PAR_PATCH_1, PAR_PATCH_2, PAR_PATCH_3, PAR_PATCH_4 -> Unit
                PAR_OTHER_END, PAR_OTHER_SLOWDOWN, PAR_BUTTON_1, PAR_BUTTON_2,
                PAR_BUTTON_4, PAR_ENDIF, PAR_ELSE,
                -> if (op2 and 0x01000000 != 0) score -= 0x40

                else -> score -= 0x40
            }
            return score
        }

        // Ancho en bits (8, 16, 24 o 32) según los bits 25-26 de op1, y si el
        // valor no cabe en ese ancho.
        val width = (op1 and 0x06000000) ushr 22
        val tooWide = op2 and ((1 shl width) - 1).inv() != 0

        var score = 0
        if (op1 and 0x38000000 != 0) {
            score += 0x20
            if (width >= 24) return 0
            if (tooWide) score -= 0x10
        } else {
            val address = parAddress(op1)
            score += 0x20
            when (op1 ushr 30) {
                // ADD puntúa como ASSIGN, con el mismo castigo por valor ancho.
                0x2 -> {
                    if (tooWide) score -= 0x10
                    score += addressScore(address)
                    if (op1 and 0x01000000 != 0) return 0
                }

                // 0x0 ASSIGN, 0x1 INDIRECT.
                0x0, 0x1 -> {
                    score += addressScore(address)
                    if (op1 and 0x01000000 != 0) return 0
                }

                // 0x3: IO/hook, sin dirección que puntuar.
                else -> Unit
            }
        }
        return score
    }

    /**
     * Qué tan plausible es una dirección (`GBACheatAddressIsReal`).
     *
     * No devuelve un booleano: una dirección de EWRAM o IWRAM suma, una de ROM
     * resta poco y una de BIOS o inexistente resta mucho. Esa graduación es la
     * que permite decir "esto es un GameShark normal" en vez de sólo
     * "algo encaja".
     */
    private fun addressScore(address: Int): Int {
        val offset = address and 0x00FFFFFF
        return when (address ushr 24) {
            0x00 -> -0x80 // BIOS: nunca escribible
            0x02 -> if (offset > SIZE_EWRAM) -0x40 else 0x20
            0x03 -> if (offset > SIZE_IWRAM) -0x40 else 0x20
            0x04 -> if (offset > SIZE_IO) -0x80 else 0x10
            0x05 -> if (offset > SIZE_OAM) -0x80 else -0x08
            0x06 -> if (offset > SIZE_VRAM) -0x80 else -0x08
            0x07 -> if (offset > SIZE_PALETTE) -0x80 else -0x08
            0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D -> -0x08 // ROM: existe, no se escribe
            0x0E, 0x0F -> if (offset > SIZE_FLASH512) -0x80 else -0x08 // SRAM
            else -> -0xC0 // 0x01 y cualquier otra región: inválida
        }
    }

    /**
     * Traduce una dirección de Action Replay al formato real de la GBA: 20 bits
     * de offset más 4 de región, en vez de los 28 de GameShark.
     */
    private fun parAddress(value: Int): Int =
        (value and 0x000FFFFF) or ((value shl 4) and 0x0F000000)

    /**
     * Descifrado TEA de GameShark (`GBACheatDecryptGameShark`).
     *
     * 32 rondas de TEA con `sum` empezando en `0xC6EF3720` y bajando en
     * `0x9E3779B9`. El orden importa (op2 se descifra usando op1 todavía
     * cifrado) y los desplazamientos son lógicos, como en C.
     */
    private fun teaDecrypt(op1: Int, op2: Int, seeds: IntArray): Pair<Int, Int> {
        var a = op1
        var b = op2
        var sum = 0xC6EF3720.toInt()
        repeat(32) {
            b -= ((a shl 4) + seeds[2]) xor (a + sum) xor ((a ushr 5) + seeds[3])
            a -= ((b shl 4) + seeds[0]) xor (b + sum) xor ((b ushr 5) + seeds[1])
            sum -= 0x9E3779B9.toInt()
        }
        return a to b
    }

    private val WHITESPACE = Regex("\\s+")
    private val HEX4 = Regex("[0-9A-Fa-f]{4}")
    private val HEX8 = Regex("[0-9A-Fa-f]{8}")

    /** ¿Contiene la línea algún token con forma de código? */
    private fun containsCodeToken(line: String): Boolean =
        line.split(WHITESPACE).any { HEX8.matches(it) }

    // Máscaras grandes: en Kotlin un literal ≥ 0x80000000 es `Long`, así que
    // hay que forzar `Int` para poder combinarlas con `and`.
    private val MASK_FFFFFF00 = 0xFFFFFF00.toInt()
    private val MASK_FFFF0000 = 0xFFFF0000.toInt()
    private val MASK_CFFF0000 = 0xCFFF0000.toInt()
    private val MASK_FFCF0000 = 0xFFCF0000.toInt()
    private val MASK_FE000000 = 0xFE000000.toInt()

    private val DEAD_FACE = 0xDEADFACE.toInt()

    private const val RESEED_MESSAGE =
        "el reseed DEADFACE de GameShark cambia las semillas de las líneas " +
            "siguientes y no se soporta"

    private const val SIZE_EWRAM = 0x40000
    private const val SIZE_IWRAM = 0x8000
    private const val SIZE_IO = 0x400
    private const val SIZE_OAM = 0x400
    private const val SIZE_VRAM = 0x18000
    private const val SIZE_PALETTE = 0x400
    private const val SIZE_FLASH512 = 0x10000

    // Subtipos de las líneas de servicio de Action Replay (`GBAActionReplay3Other`).
    private const val PAR_OTHER_END = 0x00000000
    private const val PAR_OTHER_SLOWDOWN = 0x08000000
    private const val PAR_BUTTON_1 = 0x10000000
    private const val PAR_BUTTON_2 = 0x12000000
    private const val PAR_BUTTON_4 = 0x14000000
    private const val PAR_PATCH_1 = 0x18000000
    private const val PAR_PATCH_2 = 0x1A000000
    private const val PAR_PATCH_3 = 0x1C000000
    private const val PAR_PATCH_4 = 0x1E000000
    private const val PAR_ENDIF = 0x40000000
    private const val PAR_ELSE = 0x60000000
    private val PAR_FILL_1 = 0x80000000.toInt()
    private val PAR_FILL_2 = 0x82000000.toInt()
    private val PAR_FILL_4 = 0x84000000.toInt()
}
