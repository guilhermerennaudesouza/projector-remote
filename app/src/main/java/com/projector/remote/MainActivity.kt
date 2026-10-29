package com.projector.remote

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.projector.remote.adb.AdbManager
import com.projector.remote.adb.Keycodes
import com.projector.remote.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity(), AdbManager.StateListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adbManager: AdbManager
    private var vibrator: Vibrator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
        binding.btnUp.setOnClickListener {
            hapticFeedback()
            adbManager.sendKeyEvent(Keycodes.KEYCODE_DPAD_UP)
        }
        binding.btnDown.setOnClickListener {
            hapticFeedback()
            adbManager.sendKeyEvent(Keycodes.KEYCODE_DPAD_DOWN)
        }
        binding.btnLeft.setOnClickListener {
            hapticFeedback()
            adbManager.sendKeyEvent(Keycodes.KEYCODE_DPAD_LEFT)
        }
        binding.btnRight.setOnClickListener {
            hapticFeedback()
            adbManager.sendKeyEvent(Keycodes.KEYCODE_DPAD_RIGHT)
        }
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
        binding.btnVolUp.setOnClickListener {
            hapticFeedback()
            adbManager.sendKeyEvent(Keycodes.KEYCODE_VOLUME_UP)
        }
        binding.btnVolDown.setOnClickListener {
            hapticFeedback()
            adbManager.sendKeyEvent(Keycodes.KEYCODE_VOLUME_DOWN)
        }
        binding.btnMute.setOnClickListener {
            hapticFeedback()
            adbManager.sendKeyEvent(Keycodes.KEYCODE_MUTE)
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
        val input = EditText(this).apply {
            setText(adbManager.currentIp)
            hint = "IP do projetor (ex: 192.168.2.41)"
            inputType = InputType.TYPE_CLASS_PHONE
            setPadding(40, 30, 40, 30)
        }

        AlertDialog.Builder(this)
            .setTitle("Configurar IP do Projetor")
            .setView(input)
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
