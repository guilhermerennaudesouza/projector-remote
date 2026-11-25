package com.projector.remote

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.projector.remote.adb.AdbManager
import com.projector.remote.adb.Keycodes
import com.projector.remote.databinding.ActivityMainBinding
import com.projector.remote.ui.RepeatTouchListener
import com.projector.remote.ui.TouchpadView

class MainActivity : AppCompatActivity(), AdbManager.StateListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adbManager: AdbManager
    private var vibrator: Vibrator? = null
    private lateinit var keyConfig: KeyConfig
    private var detectDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        keyConfig = KeyConfig(this)
        initVibrator()
        initAdb()
        setupButtons()
    }

    private fun initVibrator() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private fun hapticFeedback() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(30)
            }
        } catch (_: Exception) {}
    }

    private fun initAdb() {
        adbManager = AdbManager(applicationContext)
        adbManager.setStateListener(this)
    }

    override fun onResume() {
        super.onResume()
        if (adbManager.currentState != AdbManager.State.CONNECTED &&
            adbManager.currentState != AdbManager.State.CONNECTING) {
            adbManager.connect()
        }
    }

    private fun setupButtons() {
        // Status & Reconnect
        binding.btnReconnect.setOnClickListener {
            hapticFeedback()
            adbManager.connect()
        }

        // Power
        binding.btnPower.setOnClickListener {
            hapticFeedback()
            adbManager.sendKeyEvent(Keycodes.KEYCODE_POWER)
        }

        // D-Pad
        binding.btnUp.setOnTouchListener(repeatKey { adbManager.sendKeyEvent(Keycodes.KEYCODE_DPAD_UP) })
        binding.btnDown.setOnTouchListener(repeatKey { adbManager.sendKeyEvent(Keycodes.KEYCODE_DPAD_DOWN) })
        binding.btnLeft.setOnTouchListener(repeatKey { adbManager.sendKeyEvent(Keycodes.KEYCODE_DPAD_LEFT) })
        binding.btnRight.setOnTouchListener(repeatKey { adbManager.sendKeyEvent(Keycodes.KEYCODE_DPAD_RIGHT) })
        binding.btnOk.setOnClickListener {
            hapticFeedback()
            adbManager.sendKeyEvent(Keycodes.KEYCODE_DPAD_CENTER)
        }

        // Navigation
        binding.btnBack.setOnClickListener {
            hapticFeedback()
            adbManager.sendKeyEvent(Keycodes.KEYCODE_BACK)
        }
        binding.btnHome.setOnClickListener {
            hapticFeedback()
            adbManager.sendKeyEvent(Keycodes.KEYCODE_HOME)
        }
        binding.btnMenu.setOnClickListener {
            hapticFeedback()
            adbManager.sendKeyEvent(Keycodes.KEYCODE_MENU)
        }

        // Volume
        binding.btnVolUp.setOnTouchListener(repeatKey { adbManager.sendKeyEvent(Keycodes.KEYCODE_VOLUME_UP) })
        binding.btnVolDown.setOnTouchListener(repeatKey { adbManager.sendKeyEvent(Keycodes.KEYCODE_VOLUME_DOWN) })
        binding.btnMute.setOnClickListener {
            hapticFeedback()
            adbManager.sendKeyEvent(Keycodes.KEYCODE_MUTE)
        }

        // Entrada / Source (botao ao lado do Power no controle fisico)
        binding.btnInput.setOnClickListener {
            hapticFeedback()
            sendConfiguredKey(KeyConfig.Key.INPUT)
        }
        binding.btnInput.setOnLongClickListener {
            showKeyEditDialog(KeyConfig.Key.INPUT)
            true
        }

        // Foco F+ / F- (repete enquanto segura, como no controle fisico)
        binding.btnFocusPlus.setOnTouchListener(repeatKey { sendConfiguredKey(KeyConfig.Key.FOCUS_PLUS) })
        binding.btnFocusMinus.setOnTouchListener(repeatKey { sendConfiguredKey(KeyConfig.Key.FOCUS_MINUS) })

        // Mouse -> modo touchpad
        binding.btnMouse.setOnClickListener {
            hapticFeedback()
            showTouchpadDialog()
        }

        // Apps
        binding.btnProjectivy.setOnClickListener {
            hapticFeedback()
            adbManager.launchProjectivy()
            Toast.makeText(this, "Abrindo Projectivy Launcher...", Toast.LENGTH_SHORT).show()
        }
        binding.btnYouTube.setOnClickListener {
            hapticFeedback()
            adbManager.launchYouTube()
            Toast.makeText(this, "Abrindo YouTube...", Toast.LENGTH_SHORT).show()
        }

        // Keyboard / Text Dialog
        binding.btnKeyboard.setOnClickListener {
            hapticFeedback()
            showKeyboardDialog()
        }

        // Settings / Change IP Dialog
        binding.btnSettings.setOnClickListener {
            hapticFeedback()
            showSettingsDialog()
        }
    }

    private fun repeatKey(action: () -> Unit) = RepeatTouchListener(
        canRepeat = { adbManager.isIdle() && detectDialog == null }
    ) {
        hapticFeedback()
        action()
    }

    /** Envia a tecla configurada; se ainda nao houver uma, pede para detectar. */
    private fun sendConfiguredKey(key: KeyConfig.Key) {
        val code = keyConfig.get(key)
        if (code != null) {
            adbManager.sendKeyEvent(code)
        } else if (detectDialog == null) {
            showDetectKeyDialog(key, firstUse = true)
        }
    }

    // ---------- Configuracao / deteccao de teclas ----------

    private fun showKeyConfigDialog() {
        val keys = KeyConfig.Key.values()
        val items = keys.map { "${it.label}: ${keyConfig.get(it) ?: "não configurado"}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Teclas do controle físico")
            .setItems(items) { _, which -> showKeyEditDialog(keys[which]) }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun showKeyEditDialog(key: KeyConfig.Key) {
        val input = EditText(this).apply {
            setText(keyConfig.get(key) ?: "")
            hint = "Número ou nome (ex.: 178 ou KEYCODE_TV_INPUT)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setSingleLine(true)
            setPadding(40, 30, 40, 30)
        }
        AlertDialog.Builder(this)
            .setTitle(key.label)
            .setMessage("Use \"Detectar\" e aperte o mesmo botão no controle físico do projetor para descobrir o código automaticamente.")
            .setView(input)
            .setPositiveButton("Salvar") { _, _ ->
                val v = input.text.toString().trim()
                if (v.isEmpty() || KeyConfig.isValid(v)) {
                    keyConfig.set(key, v.ifEmpty { null })
                    Toast.makeText(this, "Tecla salva", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Valor inválido", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("Detectar") { _, _ -> showDetectKeyDialog(key, firstUse = false) }
            .setNegativeButton("Restaurar padrão") { _, _ -> keyConfig.set(key, null) }
            .show()
    }

    private fun showDetectKeyDialog(key: KeyConfig.Key, firstUse: Boolean) {
        if (adbManager.currentState != AdbManager.State.CONNECTED) {
            Toast.makeText(this, "Conecte ao projetor primeiro", Toast.LENGTH_SHORT).show()
            return
        }
        val intro = if (firstUse) "Este botão ainda não foi configurado.\n\n" else ""
        val dialog = AlertDialog.Builder(this)
            .setTitle("Detectar: ${key.label}")
            .setMessage("${intro}Aperte agora o botão ${key.label} no controle físico do projetor (15 s)…")
            .setNegativeButton("Cancelar", null)
            .setOnDismissListener { detectDialog = null }
            .create()
        detectDialog = dialog
        dialog.show()

        adbManager.detectPhysicalKey(15000) { code, info ->
            if (detectDialog !== dialog) return@detectPhysicalKey
            dialog.dismiss()
            if (code != null) {
                keyConfig.set(key, code)
                Toast.makeText(this, "${key.label} configurado: $code", Toast.LENGTH_LONG).show()
                hapticFeedback()
            } else {
                AlertDialog.Builder(this)
                    .setTitle("Não foi possível detectar")
                    .setMessage("$info\n\nVocê pode tentar de novo ou digitar o código manualmente em Ajustes → Teclas do controle.")
                    .setPositiveButton("Tentar de novo") { _, _ -> showDetectKeyDialog(key, false) }
                    .setNegativeButton("Fechar", null)
                    .show()
            }
        }
    }

    // ---------- Touchpad (botao Mouse) ----------

    private fun showTouchpadDialog() {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val pad = TouchpadView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320))
            listener = object : TouchpadView.Listener {
                override fun onTap(x: Int, y: Int) {
                    hapticFeedback(); adbManager.tap(x, y)
                }
                override fun onLongPress(x: Int, y: Int) {
                    adbManager.swipe(x, y, x, y, 800)
                }
                override fun onScroll(x: Int, y: Int, dx: Int, dy: Int) {
                    val w = screenWidth; val h = screenHeight
                    adbManager.swipe(
                        x, y,
                        (x + dx).coerceIn(0, w - 1), (y + dy).coerceIn(0, h - 1),
                        250
                    )
                }
            }
        }
        adbManager.fetchScreenSize { w, h -> pad.setScreenSize(w, h) }

        fun navButton(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_tool_btn)
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginStart = dp(4); marginEnd = dp(4)
            }
            setOnClickListener { hapticFeedback(); onClick() }
        }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
            addView(navButton("Voltar") { adbManager.sendKeyEvent(Keycodes.KEYCODE_BACK) })
            addView(navButton("Home") { adbManager.sendKeyEvent(Keycodes.KEYCODE_HOME) })
            addView(navButton("OK") { adbManager.sendKeyEvent(Keycodes.KEYCODE_DPAD_CENTER) })
        }

        val note = TextView(this).apply {
            text = "O cursor não aparece no projetor: acompanhe a posição pela miniatura acima."
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            textSize = 11f
            setPadding(dp(4), dp(10), dp(4), 0)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
            addView(pad)
            addView(buttons)
            addView(note)
        }

        AlertDialog.Builder(this)
            .setTitle("Modo Mouse")
            .setView(root)
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun showKeyboardDialog() {
        val input = EditText(this).apply {
            hint = "Digite o texto para enviar ao projetor..."
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(false)
            setPadding(40, 30, 40, 30)
        }

        AlertDialog.Builder(this)
            .setTitle("Digitar no Projetor")
            .setView(input)
            .setPositiveButton("Enviar") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    adbManager.sendText(text)
                    Toast.makeText(this, "Texto enviado!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showSettingsDialog() {
        val density = resources.displayMetrics.density
        val input = EditText(this).apply {
            setText(adbManager.currentIp)
            hint = "IP do projetor (ex: 192.168.2.41)"
            inputType = InputType.TYPE_CLASS_PHONE
            setPadding(40, 30, 40, 30)
        }
        var dialog: AlertDialog? = null
        val keysButton = Button(this).apply {
            text = "Teclas do controle (Entrada / Foco)"
            isAllCaps = false
            setOnClickListener {
                dialog?.dismiss()
                showKeyConfigDialog()
            }
        }
        val fastKeys = CheckBox(this).apply {
            text = "Modo rapido (sendevent)\n${adbManager.fastKeysStatus}"
            isChecked = adbManager.fastKeysEnabled
            setOnCheckedChangeListener { _, checked -> adbManager.fastKeysEnabled = checked }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
            addView(keysButton)
            addView(fastKeys)
        }

        dialog = AlertDialog.Builder(this)
            .setTitle("Configurar IP do Projetor")
            .setView(root)
            .setPositiveButton("Salvar e Conectar") { _, _ ->
                val newIp = input.text.toString().trim()
                if (newIp.isNotEmpty()) {
                    adbManager.connect(newIp, adbManager.currentPort)
                }
            }
            .setNeutralButton("Abrir Ajustes do Projetor") { _, _ ->
                adbManager.openSettings()
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    override fun onStateChanged(state: AdbManager.State, message: String?) {
        val (colorRes, textRes) = when (state) {
            AdbManager.State.CONNECTED -> Pair(R.color.status_green, R.string.status_connected)
            AdbManager.State.CONNECTING -> Pair(R.color.status_yellow, R.string.status_connecting)
            AdbManager.State.DISCONNECTED -> Pair(R.color.accent_red, R.string.status_disconnected)
            AdbManager.State.UNAUTHORIZED -> Pair(R.color.status_yellow, R.string.status_connecting)
            AdbManager.State.ERROR -> Pair(R.color.accent_red, R.string.status_error)
        }

        binding.viewStatusDot.backgroundTintList =
            ContextCompat.getColorStateList(this, colorRes)

        binding.tvStatus.text = message ?: getString(textRes)
    }

    override fun onDestroy() {
        super.onDestroy()
        adbManager.disconnect()
    }
}
