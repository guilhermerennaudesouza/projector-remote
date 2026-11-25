package com.projector.remote

import android.content.Context

/**
 * Keycodes configuraveis para botoes que variam entre fabricantes de projetor
 * (Entrada, Foco+ e Foco-). O valor pode ser numero ("178") ou nome
 * ("KEYCODE_TV_INPUT"), exatamente como o `input keyevent` aceita.
 */
class KeyConfig(context: Context) {

    enum class Key(val prefKey: String, val label: String, val default: String?) {
        INPUT("key_input", "Entrada (Input)", "KEYCODE_TV_INPUT"),
        // Nao ha keycode Android padrao para foco: cada fabricante usa um.
        // Sem valor definido, o app pede para detectar no primeiro uso.
        FOCUS_PLUS("key_focus_plus", "Foco + (F+)", null),
        FOCUS_MINUS("key_focus_minus", "Foco - (F-)", null)
    }

    private val prefs = context.getSharedPreferences("key_config", Context.MODE_PRIVATE)

    fun get(key: Key): String? = prefs.getString(key.prefKey, null) ?: key.default

    fun set(key: Key, value: String?) {
        prefs.edit().apply {
            if (value.isNullOrBlank()) remove(key.prefKey) else putString(key.prefKey, value.trim())
        }.apply()
    }

    companion object {
        fun isValid(value: String): Boolean = value.trim().matches(Regex("[A-Za-z0-9_]+"))
    }
}
