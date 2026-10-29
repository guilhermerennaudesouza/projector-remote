package com.projector.remote.adb

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.tananaev.adblib.AdbConnection
import com.tananaev.adblib.AdbCrypto
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

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
        sendCommand("input keyevent $keycode")
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
        executor.execute {
            val conn = connection
            if (conn == null || currentState != State.CONNECTED) {
                // Tenta reconectar automaticamente
                connect(currentIp, currentPort)
                return@execute
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
    }
}
