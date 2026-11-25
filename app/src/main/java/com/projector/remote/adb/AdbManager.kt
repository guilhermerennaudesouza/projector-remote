package com.projector.remote.adb

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import com.tananaev.adblib.AdbConnection
import com.tananaev.adblib.AdbCrypto
import com.tananaev.adblib.AdbStream
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class AdbManager(private val context: Context) {

    companion object {
        private const val TAG = "AdbManager"
        const val DEFAULT_IP = "192.168.2.41"
        const val DEFAULT_PORT = 5555
        private const val CONNECT_TIMEOUT_MS = 4000
    }

    enum class State {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        UNAUTHORIZED,
        ERROR
    }

    interface StateListener {
        fun onStateChanged(state: State, message: String? = null)
    }

    private val executor = Executors.newSingleThreadExecutor()
    // Comandos que retornam saida (ex.: getevent, wm size) rodam em paralelo
    // para nao travar os comandos de teclas.
    private val queryExecutor = Executors.newCachedThreadPool()
    private val pendingCommands = AtomicInteger(0)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var stateListener: StateListener? = null

    private var socket: Socket? = null
    private var connection: AdbConnection? = null
    private var crypto: AdbCrypto? = null

    var currentIp: String = DEFAULT_IP
        private set
    var currentPort: Int = DEFAULT_PORT
        private set

    var currentState: State = State.DISCONNECTED
        private set

    // --- Modo rapido (sendevent) ---
    private val prefs = context.getSharedPreferences("adb_settings", Context.MODE_PRIVATE)

    /** Usa sendevent nas teclas que o projetor permite; o resto continua via `input keyevent`. */
    var fastKeysEnabled: Boolean
        get() = prefs.getBoolean("fast_keys", true)
        set(value) { prefs.edit().putBoolean("fast_keys", value).apply() }

    @Volatile private var fastKeyMap: Map<String, SendEventMapper.Target> = emptyMap()
    @Volatile private var fastShell: AdbStream? = null
    @Volatile private var connectionGeneration = 0

    /** Texto curto para a tela de ajustes. */
    @Volatile var fastKeysStatus: String = "Aguardando conexao"
        private set

    fun setStateListener(listener: StateListener?) {
        this.stateListener = listener
    }

    private fun updateState(newState: State, message: String? = null) {
        currentState = newState
        mainHandler.post {
            stateListener?.onStateChanged(newState, message)
        }
    }

    private fun getOrCreateCrypto(): AdbCrypto {
        crypto?.let { return it }

        val privFile = File(context.filesDir, "adbkey")
        val pubFile = File(context.filesDir, "adbkey.pub")
        val base64 = AndroidBase64()

        val c = if (privFile.exists() && pubFile.exists()) {
            try {
                AdbCrypto.loadAdbKeyPair(base64, privFile, pubFile)
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao carregar chave existente, gerando nova...", e)
                val newCrypto = AdbCrypto.generateAdbKeyPair(base64)
                newCrypto.saveAdbKeyPair(privFile, pubFile)
                newCrypto
            }
        } else {
            Log.i(TAG, "Gerando novo par de chaves RSA para ADB...")
            val newCrypto = AdbCrypto.generateAdbKeyPair(base64)
            newCrypto.saveAdbKeyPair(privFile, pubFile)
            newCrypto
        }

        crypto = c
        return c
    }

    fun connect(ip: String = currentIp, port: Int = currentPort) {
        currentIp = ip
        currentPort = port

        executor.execute {
            if (currentState == State.CONNECTING) return@execute

            disconnectInternal()
            updateState(State.CONNECTING, "Conectando ao projetor $ip:$port...")

            try {
                val cr = getOrCreateCrypto()
                val s = Socket()
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS)
                socket = s

                val conn = AdbConnection.create(s, cr)
                connection = conn

                conn.connect()
                updateState(State.CONNECTED, "Conectado ao projetor!")
                probeFastKeys(++connectionGeneration)
            } catch (e: Exception) {
                Log.e(TAG, "Erro de conexao ADB", e)
                disconnectInternal()
                val msg = if (e.message?.contains("refused", ignoreCase = true) == true) {
                    "Conexao recusada. Verifique se a Depuracao de Rede esta ligada no projetor."
                } else if (e.message?.contains("timeout", ignoreCase = true) == true) {
                    "Tempo esgotado. Verifique se o projetor esta ligado e no IP $ip."
                } else {
                    "Erro: ${e.localizedMessage ?: "Falha ao conectar"}"
                }
                updateState(State.ERROR, msg)
            }
        }
    }

    fun disconnect() {
        executor.execute {
            disconnectInternal()
            updateState(State.DISCONNECTED, "Desconectado")
        }
    }

    private fun disconnectInternal() {
        connectionGeneration++
        fastKeyMap = emptyMap()
        fastKeysStatus = "Aguardando conexao"
        closeFastShell()
        try {
            connection?.close()
        } catch (_: Exception) {}
        try {
            socket?.close()
        } catch (_: Exception) {}
        connection = null
        socket = null
    }

    fun sendKeyEvent(keycode: Int) {
        sendKeyEvent(keycode.toString())
    }

    /** Aceita numero ("178") ou nome ("KEYCODE_TV_INPUT"), como o `input keyevent`. */
    fun sendKeyEvent(key: String) {
        val safe = key.trim()
        if (!safe.matches(Regex("[A-Za-z0-9_]+"))) return

        val target = if (fastKeysEnabled) fastKeyMap[keyName(safe)] else null
        if (target == null) {
            sendCommand("input keyevent $safe")
            return
        }
        pendingCommands.incrementAndGet()
        executor.execute {
            try {
                if (!sendFast(SendEventMapper.keyPressCommand(target))) {
                    sendCommandInternal("input keyevent $safe")
                }
            } finally {
                pendingCommands.decrementAndGet()
            }
        }
    }

    /** "19" -> "KEYCODE_DPAD_UP"; nomes ja prefixados passam direto. */
    private fun keyName(key: String): String {
        val code = key.toIntOrNull() ?: return if (key.startsWith("KEYCODE_")) key else "KEYCODE_$key"
        return KeyEvent.keyCodeToString(code)
    }

    /**
     * Escreve no shell interativo que fica aberto, sem abrir um stream ADB novo
     * por tecla. Roda no [executor]. Retorna false se o shell caiu.
     */
    private fun sendFast(command: String): Boolean {
        val conn = connection ?: return false
        if (currentState != State.CONNECTED) return false
        return try {
            val shell = fastShell?.takeIf { !it.isClosed } ?: openFastShell(conn)
            shell.write((command + "\n").toByteArray(Charsets.UTF_8))
            true
        } catch (e: Exception) {
            Log.w(TAG, "Shell rapido caiu, usando input keyevent", e)
            closeFastShell()
            false
        }
    }

    private fun openFastShell(conn: AdbConnection): AdbStream {
        val shell = conn.open("shell:")
        fastShell = shell
        // Descarta eco e prompt; sem leitura o buffer do adblib so cresce.
        Thread {
            try {
                while (!shell.isClosed) {
                    shell.read() ?: break
                }
            } catch (_: Exception) {}
        }.apply { isDaemon = true; name = "adb-fast-shell-drain" }.start()
        return shell
    }

    private fun closeFastShell() {
        val s = fastShell
        fastShell = null
        try { s?.close() } catch (_: Exception) {}
    }

    /**
     * Descobre quais teclas podem ir por sendevent. Roda uma vez por conexao,
     * em segundo plano; enquanto nao termina, tudo vai por `input keyevent`.
     */
    private fun probeFastKeys(generation: Int) {
        queryExecutor.execute {
            try {
                fastKeysStatus = "Verificando..."
                val getevent = execForOutput("getevent -p", 5000) ?: return@execute
                val devices = SendEventMapper.parseGetevent(getevent).filter { it.keyCodes.isNotEmpty() }
                if (devices.isEmpty()) {
                    fastKeysStatus = "Indisponivel (nenhum dispositivo de teclas)"
                    return@execute
                }

                // SYN_REPORT sozinho nao gera tecla: so testa se o sendevent consegue escrever.
                val probe = devices.joinToString(";") {
                    "sendevent ${it.path} 0 0 0 2>/dev/null && echo \"W ${it.path}\""
                }
                val writable = Regex("^W (\\S+)", RegexOption.MULTILINE)
                    .findAll(execForOutput(probe, 5000) ?: "")
                    .map { it.groupValues[1] }.toSet()
                if (writable.isEmpty()) {
                    fastKeysStatus = "Indisponivel (projetor bloqueia o sendevent)"
                    return@execute
                }

                val layoutFiles = SendEventMapper.parseLayoutFiles(
                    execForOutput("dumpsys input | grep -E 'Path:|KeyLayoutFile:'", 8000) ?: ""
                )
                val klFiles = devices.filter { it.path in writable }
                    .mapNotNull { layoutFiles[it.path] }.distinct()
                if (klFiles.isEmpty()) {
                    fastKeysStatus = "Indisponivel (sem arquivo .kl)"
                    return@execute
                }
                val layouts = SendEventMapper.parseKeyLayouts(
                    execForOutput(
                        "grep -H -E '^[[:space:]]*key[[:space:]]+[0-9]+' " +
                            klFiles.joinToString(" ") { "'$it'" },
                        6000
                    ) ?: ""
                )

                val map = SendEventMapper.buildMap(devices, layoutFiles, layouts, writable)
                if (generation != connectionGeneration) return@execute
                fastKeyMap = map
                fastKeysStatus = if (map.isEmpty()) "Indisponivel (nenhuma tecla mapeada)"
                else "Ativo: ${map.size} teclas via sendevent"
                Log.i(TAG, "sendevent: $fastKeysStatus -> ${map.keys.sorted()}")
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao preparar sendevent", e)
                fastKeysStatus = "Indisponivel (erro na verificacao)"
            }
        }
    }

    /** True quando nao ha comandos na fila (usado pela repeticao ao segurar botoes). */
    fun isIdle(): Boolean = pendingCommands.get() == 0

    fun tap(x: Int, y: Int) {
        sendCommand("input tap $x $y")
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) {
        sendCommand("input swipe $x1 $y1 $x2 $y2 $durationMs")
    }

    fun sendText(text: String) {
        // Escapar caracteres para input text no shell android
        val escaped = text.replace(" ", "%s").replace("\"", "\\\"").replace("'", "\\'")
        sendCommand("input text \"$escaped\"")
    }

    fun launchProjectivy() {
        sendCommand("monkey -p com.spocky.projengmenu -c android.intent.category.LAUNCHER 1")
    }

    fun launchYouTube() {
        sendCommand("monkey -p com.google.android.youtube.tv -c android.intent.category.LAUNCHER 1")
    }

    fun openSettings() {
        sendCommand("am start -a android.settings.SETTINGS")
    }

    fun sendCommand(command: String) {
        pendingCommands.incrementAndGet()
        executor.execute {
            try {
                sendCommandInternal(command)
            } finally {
                pendingCommands.decrementAndGet()
            }
        }
    }

    private fun sendCommandInternal(command: String) {
        val conn = connection
        if (conn == null || currentState != State.CONNECTED) {
            // Tenta reconectar automaticamente
            connect(currentIp, currentPort)
            return
        }

        try {
            val stream = conn.open("shell:$command")
            // Le ate fechar ou esvaziar
            try {
                while (!stream.isClosed) {
                    val buf = stream.read() ?: break
                    if (buf.isEmpty()) break
                }
            } catch (_: Exception) {}
            try {
                stream.close()
            } catch (_: Exception) {}
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao enviar comando shell: $command", e)
            // Se falhou ao enviar comando, marca desconectado
            disconnectInternal()
            updateState(State.DISCONNECTED, "Conexao perdida. Reconectando...")
            connect(currentIp, currentPort)
        }
    }

    /**
     * Executa um comando shell e entrega a saida no main thread.
     * [stopWhen] permite encerrar a leitura antes do fim (ex.: getevent).
     * Retorna null se nao estiver conectado ou em caso de erro/timeout sem saida.
     */
    fun runForOutput(
        command: String,
        timeoutMs: Long = 5000,
        stopWhen: ((String) -> Boolean)? = null,
        callback: (String?) -> Unit
    ) {
        queryExecutor.execute {
            val result = execForOutput(command, timeoutMs, stopWhen)
            mainHandler.post { callback(result) }
        }
    }

    /** Versao bloqueante de [runForOutput]; chamar fora do main thread. */
    private fun execForOutput(
        command: String,
        timeoutMs: Long,
        stopWhen: ((String) -> Boolean)? = null
    ): String? {
        run {
            val conn = connection
            if (conn == null || currentState != State.CONNECTED) {
                return null
            }
            val out = StringBuilder()
            var ok = false
            try {
                val stream = conn.open("shell:$command")
                val watchdog = Thread {
                    try {
                        Thread.sleep(timeoutMs)
                        stream.close()
                    } catch (_: Exception) {}
                }
                watchdog.isDaemon = true
                watchdog.start()
                try {
                    while (!stream.isClosed) {
                        val buf = stream.read() ?: break
                        if (buf.isEmpty()) break
                        out.append(String(buf, Charsets.UTF_8))
                        if (stopWhen != null && stopWhen(out.toString())) break
                    }
                } catch (_: Exception) {}
                watchdog.interrupt()
                try { stream.close() } catch (_: Exception) {}
                ok = true
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao executar: $command", e)
            }
            return if (ok || out.isNotEmpty()) out.toString() else null
        }
    }

    /** Resolucao da tela do projetor via `wm size` (ex.: 1920x1080). */
    fun fetchScreenSize(callback: (Int, Int) -> Unit) {
        runForOutput("wm size") { out ->
            val sizes = Regex("(\\d+)x(\\d+)").findAll(out ?: "").toList()
            // "Override size" (se existir) vem depois de "Physical size" e e o que vale.
            val m = sizes.lastOrNull()
            if (m != null) {
                callback(m.groupValues[1].toInt(), m.groupValues[2].toInt())
            } else {
                callback(1920, 1080)
            }
        }
    }

    /**
     * Aguarda o usuario apertar uma tecla no controle fisico do projetor
     * e descobre qual keycode Android ela gera.
     *
     * 1. `getevent` captura o scancode bruto e o dispositivo (/dev/input/eventX).
     * 2. `dumpsys input` informa qual arquivo .kl esse dispositivo usa.
     * 3. O .kl traduz scancode -> nome da tecla (ex.: FOCUS, TV_INPUT).
     *
     * Resultado: "KEYCODE_<NOME>" (ou o numero), ou null com mensagem de erro.
     */
    fun detectPhysicalKey(timeoutMs: Long, callback: (key: String?, info: String) -> Unit) {
        val keyDown = Regex("(/dev/input/event\\d+):\\s+0001\\s+([0-9a-fA-F]{4})\\s+00000001")
        runForOutput("getevent -q", timeoutMs, stopWhen = { keyDown.containsMatchIn(it) }) { out ->
            val m = out?.let { keyDown.find(it) }
            if (m == null) {
                callback(null, if (out == null) "Sem conexao com o projetor." else "Nenhuma tecla detectada.")
                return@runForOutput
            }
            val device = m.groupValues[1]
            val scanCode = m.groupValues[2].toInt(16)
            resolveScanCode(device, scanCode, callback)
        }
    }

    private fun resolveScanCode(device: String, scanCode: Int, callback: (String?, String) -> Unit) {
        runForOutput("dumpsys input", 6000) { dump ->
            val klFile = findKeyLayoutFile(dump ?: "", device)
            val dirs = "/odm/usr/keylayout /vendor/usr/keylayout /system/usr/keylayout /data/system/devices/keylayout"
            val grepAll = "for d in $dirs; do for f in \$d/*.kl; do " +
                "[ -f \"\$f\" ] && grep -E '^[[:space:]]*key[[:space:]]+$scanCode[[:space:]]' \"\$f\" | sed \"s|^|\$f:|\"; " +
                "done; done 2>/dev/null"
            val cmd = if (klFile != null) {
                "grep -E '^[[:space:]]*key[[:space:]]+$scanCode[[:space:]]' '$klFile' | sed 's|^|$klFile:|'; $grepAll"
            } else grepAll
            runForOutput(cmd, 6000) { out ->
                val lines = (out ?: "").lines().filter { it.contains(":") }
                // Prioriza o .kl do proprio dispositivo; depois quaisquer outros, Generic.kl por ultimo.
                val best = lines.firstOrNull { klFile != null && it.startsWith("$klFile:") }
                    ?: lines.firstOrNull { !it.contains("Generic.kl") }
                    ?: lines.firstOrNull()
                val name = best?.substringAfter(":")?.trim()
                    ?.split(Regex("\\s+"))?.getOrNull(2)
                if (name.isNullOrEmpty()) {
                    callback(null, "Tecla capturada (scancode $scanCode), mas nao ha mapeamento no projetor.")
                } else {
                    val key = if (name.all { it.isDigit() }) name else "KEYCODE_$name"
                    callback(key, "Scancode $scanCode -> $key")
                }
            }
        }
    }

    private fun findKeyLayoutFile(dump: String, device: String): String? {
        val lines = dump.lines()
        val idx = lines.indexOfFirst { it.trim() == "Path: $device" }
        if (idx < 0) return null
        for (i in idx + 1 until minOf(lines.size, idx + 30)) {
            val t = lines[i].trim()
            if (t.startsWith("KeyLayoutFile:")) {
                return t.removePrefix("KeyLayoutFile:").trim().takeIf { it.startsWith("/") }
            }
            if (t.startsWith("Path: ")) break
        }
        return null
    }
}
