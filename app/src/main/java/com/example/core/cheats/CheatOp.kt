package com.example.core.cheats

/**
 * Tipos de operación de truco que entiende el núcleo nativo.
 *
 * El orden de este enum es el contrato numérico con `asgba_jni.cpp` y con el
 * bucle de aplicación de `core.cpp` (el ordinal viaja como el campo `kind` de
 * [CheatOp]): no se reordena ni se inserta en medio sin cambiar los tres
 * archivos a la vez —misma regla que para `GbaDebugSnapshot`.
 *
 * Las variantes `IF_*` son condiciones: gobiernan las operaciones que siguen y
 * se saltan [CheatOp.skipOnFail] de ellas cuando no se cumplen (1 en los
 * códigos publicados, más en los rangos GameShark). `IF_AND` comprueba que
 * algún bit en común esté encendido —el "y bit a bit" de CodeBreaker—, y
 * `IF_NAND` la condición de botón sobre KEYINPUT: ningún bit del modificador
 * está pulsado significa "se están pulsando todos los bits pedidos", porque
 * KEYINPUT es activo a nivel bajo.
 */
enum class CheatKind {
    WRITE,
    OR,
    AND,
    ADD,
    IF_EQ,
    IF_NE,
    IF_LT,
    IF_GT,
    IF_LE,
    IF_GE,
    IF_AND,
    IF_NAND,
}

/**
 * Una operación de truco ya decodificada, en la forma en que cruza la frontera
 * JNI.
 *
 * [address] es la dirección GBA absoluta (con byte de región), [value] el dato
 * a escribir o contra el que comparar, [width] el ancho en bytes de la
 * escritura (1, 2 o 4; siempre 2 en condiciones) y [skipOnFail] cuántas
 * operaciones salta la condición al fallar. La memoria se escribe en little
 * endian, como la GBA.
 */
data class CheatOp(
    val address: Int,
    val value: Int,
    val kind: CheatKind,
    val width: Int,
    val skipOnFail: Int,
)

/**
 * Empaqueta la lista en el arreglo plano que espera `NativeCoreBridge.setCheats`.
 *
 * El orden —dirección, valor, tipo, ancho, salto— es el mismo contrato que se
 * documenta en `NativeCoreBridge.java` y que `asgba_jni.cpp` vuelve a validar;
 * empaquetarlo aquí una sola vez evita que cada llamador lo reimplemente.
 */
fun List<CheatOp>.packForNative(): IntArray {
    val packed = IntArray(size * NATIVE_OP_STRIDE)
    forEachIndexed { index, op ->
        val base = index * NATIVE_OP_STRIDE
        packed[base] = op.address
        packed[base + 1] = op.value
        packed[base + 2] = op.kind.ordinal
        packed[base + 3] = op.width
        packed[base + 4] = op.skipOnFail
    }
    return packed
}

/** Número de enteros que ocupa cada operación empaquetada. */
private const val NATIVE_OP_STRIDE = 5
