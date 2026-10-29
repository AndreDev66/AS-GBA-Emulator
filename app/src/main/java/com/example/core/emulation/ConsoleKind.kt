package com.example.core.emulation

/**
 * Consola que un cartucho representa y que el núcleo nativo debe emular.
 *
 * El `storageId` es el identificador fijo que espera `NativeCoreBridge.create`
 * y coincide con el `ravenemu::Console` del núcleo: 0 para Game Boy (también
 * Game Boy Color, que es el mismo motor en otro modo de hardware) y 2 para
 * Game Boy Advance. Los identificadores son un contrato persistido —una ROM se
 * lanza por ellos— y no el orden de declaración del enum, así que no se
 * reordenan ni se reasignan.
 *
 * Nintendo DS tiene el identificador 3, que es el que reservaba melonDS y que
 * aquí no se usa para llamar a `NativeCoreBridge`: su núcleo no es RavenEmu y
 * viaja por [com.example.core.MelonDsCoreBridge]. El identificador se conserva
 * de todos modos para que el número signifique lo mismo en toda la aplicación,
 * incluida la biblioteca y las partidas guardadas.
 */
enum class ConsoleKind(
    val storageId: Int,
    /** Nombre corto para registros y mensajes. */
    val shortName: String,
    /** Ancho del área de pantalla en píxeles: 160 en Game Boy, 240 en GBA. */
    val screenWidth: Int,
    /** Alto del área de pantalla en píxeles: 144 en Game Boy, 160 en GBA. */
    val screenHeight: Int,
) {
    GAME_BOY(0, "GB", 160, 144),
    GAME_BOY_ADVANCE(2, "GBA", 240, 160),

    /**
     * Las dos pantallas de la Nintendo DS, de 256×192 cada una, en horizontal y
     * con la táctil a la izquierda: es como se sostiene la consola.
     *
     * El ancho declarado es el del conjunto —512— y no el de una pantalla, a
     * propósito. Así el renderizador de AS sigue recibiendo un rectángulo con un
     * único origen, y quien dibuja encima —la capa de entrada del puntero— sólo
     * tiene que saber que la mitad izquierda es la táctil.
     */
    NINTENDO_DS(3, "NDS", 512, 192);

    /** Píxeles del fotograma completo, que es el tamaño del framebuffer. */
    val screenPixelCount: Int
        get() = screenWidth * screenHeight

    /**
     * Ancho de una de las pantallas, útil cuando la consola tiene dos.
     * En Game Boy y GBA es el ancho completo.
     */
    val panelWidth: Int
        get() = if (this == NINTENDO_DS) screenWidth / 2 else screenWidth

    /** true si la consola tiene dos pantallas. */
    val hasTwoScreens: Boolean
        get() = this == NINTENDO_DS

    /**
     * Pantalla táctil bajo la principal.
     *
     * La entrada no va por los botones del mando sino por un puntero que se
     * posa sobre la imagen, de modo que AS necesita saber cuál de las dos
     * pantallas es la que lo recibe.
     */
    val hasStylus: Boolean
        get() = this == NINTENDO_DS

    /**
     * Mitad del fotograma que recibe el puntero: 0 es la izquierda.
     *
     * En las consolas de una sola pantalla vale -1, que es como se dice «aquí
     * no hay nada táctil» sin necesidad de una bandera aparte.
     */
    val stylusPanelIndex: Int
        get() = if (this == NINTENDO_DS) 0 else -1

    /**
     * Botones de hombro en el cartucho.
     *
     * La Game Boy no los tiene: su joypad sólo conoce cruceta, A, B, Start y
     * Select, y el núcleo los descarta aunque se le manden. Mostrar unos
     * botones que no hacen nada invita a pulsarlos sin razón.
     */
    val supportsShoulderButtons: Boolean
        get() = this == GAME_BOY_ADVANCE || this == NINTENDO_DS

    /**
     * Botones de cara además de A y B.
     *
     * La Nintendo DS añade X e Y. La Game Boy y la GBA no los tienen.
     */
    val supportsFaceButtonsXY: Boolean
        get() = this == NINTENDO_DS

    /**
     * Trucos de memoria.
     *
     * El núcleo de la GBA sabe escribir en la RAM del juego y resolver códigos
     * CheatCode; el de Game Boy no implementa esa vía y descarta la lista, y el
     * de Nintendo DS expone otro tipo de memoria, que no es la que AS edita.
     */
    val supportsMemoryCheats: Boolean
        get() = this == GAME_BOY_ADVANCE

    /**
     * Enlace entre dos dispositivos.
     *
     * El enlace de AS es el de la Game Boy Advance, un puerto que la Nintendo DS
     * no tiene: el multibajo de Nintendo es un cable distinto y el wifi del
     * melonDS va por otra vía, desactivada en esta integración.
     */
    val supportsLinkCable: Boolean
        get() = this == GAME_BOY_ADVANCE

    /** La Nintendo DS inicia en horizontal; el usuario también puede elegir vertical. */
    val prefersLandscape: Boolean
        get() = this == NINTENDO_DS

    /**
     * Detección de consola y lectura de cartuchos Game Boy.
     *
     * Vive en el companion porque no depende de ninguna consola en concreto:
     * la detección tiene que poder responder antes de saber cuál se emula, que
     * es justamente lo que la decide.
     */
    companion object {

        /**
         * Detecta la consola de una ROM a partir de su nombre y sus bytes.
         *
         * El nombre manda cuando la extensión es explícita (`.gb`, `.gbc`,
         * `.gba`): es lo que el usuario eligió y no admite discusión. Para lo
         * demás —ROM extraída de un `.zip` o `.7z`, extensión ambigua como
         * `.bin`— se mira la propia ROM: la Game Boy guarda el logotipo de
         * Nintendo en 0x104 y su suma de comprobación de cabecera en 0x14D, y una
         * GBA de esas mismas posiciones tiene código del juego, no aquellas dos
         * marcas a la vez.
         *
         * Sin nombre reconocible y sin ROM que inspeccionar se resuelve a GBA,
         * que es la consola histórica de esta aplicación y el caso del que ya
         * había bibliotecas.
         *
         * @param fileName nombre del archivo tal y como lo guarda la biblioteca
         *   (con ruta y extensión), o cadena vacía si no se conoce.
         * @param rom bytes completos de la ROM, o `null` si no se pueden leer.
         */
        fun detect(
            fileName: String,
            rom: ByteArray?,
            romSize: Long = rom?.size?.toLong() ?: 0L,
        ): ConsoleKind {
            val base = fileName.substringAfterLast('/').substringAfterLast('\\').lowercase()
            when {
                base.endsWith(".gb") || base.endsWith(".gbc") -> return GAME_BOY
                base.endsWith(".gba") || base.endsWith(".agb") -> return GAME_BOY_ADVANCE
                base.endsWith(".nds") || base.endsWith(".dsi") -> return NINTENDO_DS
            }
            if (rom != null) {
                if (looksLikeNintendoDs(rom, romSize)) return NINTENDO_DS
                if (looksLikeGameBoy(rom)) return GAME_BOY
            }
            return GAME_BOY_ADVANCE
        }

        /**
         * ¿Estos bytes son un cartucho de Nintendo DS?
         *
         * La cabecera de la DS es de 4096 bytes y en ella el desplazamiento y el
         * tamaño del ejecutable ARM9 son el rasgo que ningún otro formato
         * comparte: el código principal está en 0x20 y 0x2C, y tiene que caber
         * dentro del archivo. La comprobación es aritmética y no una marca
         * mágica, porque Nintendo no pone logotipo en una cabecera de DS (el
         * logotipo vive en el juego, y en el banner).
         */
        private fun looksLikeNintendoDs(rom: ByteArray, romSize: Long): Boolean {
            if (rom.size < 0x200) return false
            val arm9Offset = readLittleEndianInt(rom, 0x20)
            val arm9Size = readLittleEndianInt(rom, 0x2C)
            if (arm9Size <= 0) return false
            val arm9End = arm9Offset.toLong() + arm9Size.toLong()
            // El código principal casi siempre arranca tras la cabecera y antes
            // de que el archivo se agote; un desplazamiento absurdo o un tamaño
            // que no cabe son señales de que no es una DS.
            if (arm9Offset < 0x200 || arm9End > romSize) return false
            // El título va en 0x00 y el código de juego en 0x0C, en ASCII
            // imprimible. Un homebrew puede no llevarlos, pero entonces
            // el resto de la cabecera tampoco se parece a nada.
            return isPrintableAscii(rom, 0x0C, 4)
        }

        private fun readLittleEndianInt(rom: ByteArray, offset: Int): Int {
            return (rom[offset].toInt() and 0xFF) or
                ((rom[offset + 1].toInt() and 0xFF) shl 8) or
                ((rom[offset + 2].toInt() and 0xFF) shl 16) or
                ((rom[offset + 3].toInt() and 0xFF) shl 24)
        }

        private fun isPrintableAscii(rom: ByteArray, offset: Int, length: Int): Boolean {
            for (index in offset until offset + length) {
                val value = rom[index].toInt() and 0xFF
                if (value < 0x20 || value > 0x7E) return false
            }
            return true
        }

        /**
         * ¿Estos bytes son un cartucho de Game Boy o Game Boy Color?
         *
         * Dos marcas que ninguna ROM de otra consola lleva a la vez: el arranque
         * del logotipo de Nintendo (`CE ED`, 0x104-0x105) y la suma de
         * comprobación que la propia ROM calcula sobre su cabecera (0x134-0x14D,
         * el mismo algoritmo que usa el arranque real). Una ROM de GBA grande
         * podría cumplir una de las dos por azar; las dos a la vez, no.
         */
        private fun looksLikeGameBoy(rom: ByteArray): Boolean {
            if (rom.size < 0x150) return false
            // `and 0xFF` en los dos logos: un Byte negativo se extiende con
            // signo y `0xCE` nunca llegaría a compararse bien.
            if ((rom[0x104].toInt() and 0xFF) != 0xCE) return false
            if ((rom[0x105].toInt() and 0xFF) != 0xED) return false
            var checksum = 0
            for (index in 0x134..0x14C) {
                checksum = (checksum - (rom[index].toInt() and 0xFF) - 1) and 0xFF
            }
            return checksum == (rom[0x14D].toInt() and 0xFF)
        }
    }
}
