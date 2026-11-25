package com.projector.remote.adb

/**
 * Monta a tabela "keycode Android -> (dispositivo /dev/input/eventX, scancode Linux)"
 * usada pelo modo rapido (sendevent).
 *
 * O `input keyevent` sobe uma JVM no projetor a cada tecla (centenas de ms).
 * O `sendevent` e nativo e escreve direto no dispositivo de entrada, mas fala
 * em scancodes do Linux: o Android so traduz para keycode pelo arquivo .kl do
 * dispositivo, e o kernel descarta teclas que o dispositivo nao declara.
 * Por isso a tabela cruza tres saidas do projetor:
 *  - `getevent -p`: dispositivos e as teclas (EV_KEY) que cada um aceita;
 *  - `dumpsys input` (linhas Path/KeyLayoutFile): o .kl de cada dispositivo;
 *  - as linhas `key <scancode> <NOME>` desses .kl.
 */
object SendEventMapper {

    data class Target(val device: String, val scanCode: Int)

    data class Device(val path: String, val name: String, val keyCodes: Set<Int>)

    /** Lê a saida de `getevent -p` (numerica, sem -l). */
    fun parseGetevent(out: String): List<Device> {
        val devices = mutableListOf<Device>()
        var path: String? = null
        var name = ""
        var keys = mutableSetOf<Int>()
        var inKeySection = false
        val header = Regex("^\\s*[A-Z_]+ \\(([0-9a-fA-F]{4})\\):(.*)$")
        val hex = Regex("\\b([0-9a-fA-F]{4})\\b")

        fun flush() {
            path?.let { devices += Device(it, name, keys) }
            path = null; name = ""; keys = mutableSetOf(); inKeySection = false
        }

        for (raw in out.lines()) {
            val line = raw.trimEnd()
            val add = Regex("add device \\d+:\\s*(/dev/input/\\S+)").find(line)
            if (add != null) {
                flush()
                path = add.groupValues[1]
                continue
            }
            if (path == null) continue
            val nm = Regex("^\\s*name:\\s*\"(.*)\"").find(line)
            if (nm != null) { name = nm.groupValues[1]; continue }

            val h = header.find(line)
            if (h != null) {
                inKeySection = h.groupValues[1] == "0001"
                if (inKeySection) hex.findAll(h.groupValues[2]).forEach { keys += it.groupValues[1].toInt(16) }
                continue
            }
            if (line.trimStart().startsWith("input props") || line.trimStart().startsWith("events:")) {
                inKeySection = false
                continue
            }
            if (inKeySection) hex.findAll(line).forEach { keys += it.groupValues[1].toInt(16) }
        }
        flush()
        return devices
    }

    /** Pares Path -> KeyLayoutFile vindos de `dumpsys input | grep -E 'Path:|KeyLayoutFile:'`. */
    fun parseLayoutFiles(out: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var path: String? = null
        for (raw in out.lines()) {
            val t = raw.trim()
            when {
                t.startsWith("Path:") -> path = t.removePrefix("Path:").trim()
                t.startsWith("KeyLayoutFile:") -> {
                    val kl = t.removePrefix("KeyLayoutFile:").trim()
                    val p = path
                    if (p != null && kl.startsWith("/")) result[p] = kl
                    path = null
                }
            }
        }
        return result
    }

    /** Linhas `arquivo.kl:key 103 DPAD_UP [FLAGS]` de `grep -H`. Retorna arquivo -> [(scancode, NOME)]. */
    fun parseKeyLayouts(out: String): Map<String, List<Pair<Int, String>>> {
        val result = mutableMapOf<String, MutableList<Pair<Int, String>>>()
        val re = Regex("^(\\S+?\\.kl):\\s*key\\s+(\\d+)\\s+([A-Z0-9_]+)")
        for (line in out.lines()) {
            val m = re.find(line.trim()) ?: continue
            result.getOrPut(m.groupValues[1]) { mutableListOf() } +=
                m.groupValues[2].toInt() to m.groupValues[3]
        }
        return result
    }

    /**
     * Cruza tudo. [writable] sao os dispositivos em que o `sendevent` funcionou.
     * Dispositivos com cara de controle remoto (IR) tem prioridade.
     */
    fun buildMap(
        devices: List<Device>,
        layoutFiles: Map<String, String>,
        layouts: Map<String, List<Pair<Int, String>>>,
        writable: Set<String>
    ): Map<String, Target> {
        val remoteLike = Regex("ir|remote|rc|keypad|key", RegexOption.IGNORE_CASE)
        val ordered = devices
            .filter { it.path in writable && it.keyCodes.isNotEmpty() }
            .sortedByDescending { remoteLike.containsMatchIn(it.name) }

        val map = mutableMapOf<String, Target>()
        for (dev in ordered) {
            val kl = layoutFiles[dev.path] ?: continue
            for ((scan, name) in layouts[kl].orEmpty()) {
                if (scan !in dev.keyCodes) continue
                map.putIfAbsent("KEYCODE_$name", Target(dev.path, scan))
            }
        }
        return map
    }

    /** Comando de uma tecla completa: aperta, sincroniza, solta, sincroniza. */
    fun keyPressCommand(t: Target): String {
        val d = t.device
        val s = t.scanCode
        return "sendevent $d 1 $s 1;sendevent $d 0 0 0;sendevent $d 1 $s 0;sendevent $d 0 0 0"
    }
}
