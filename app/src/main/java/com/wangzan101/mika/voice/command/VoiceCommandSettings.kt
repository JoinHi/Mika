package com.wangzan101.mika.voice.command

import android.content.Context
import android.content.SharedPreferences

/** Independent opt-in: changing this never changes the normal hold-to-talk selection. */
internal class VoiceCommandSettings(private val prefs: SharedPreferences) {
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) { prefs.edit().putBoolean("enabled", value).apply() }

    companion object {
        fun get(context: Context) = VoiceCommandSettings(
            context.applicationContext.getSharedPreferences("voice_command", Context.MODE_PRIVATE),
        )
    }
}
