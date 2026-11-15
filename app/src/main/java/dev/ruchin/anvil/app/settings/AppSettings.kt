package dev.ruchin.anvil.app.settings

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** The few user settings Anvil has, in SharedPreferences (DataStore can take over when there are more). */
@Singleton
class AppSettings @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _compatibilityMode = MutableStateFlow(prefs.getBoolean(KEY_COMPAT, false))

    /** Small (16 KiB) USB transfers for fussy phones, hubs and drives. Applies to the next drive opened. */
    val compatibilityMode: StateFlow<Boolean> = _compatibilityMode.asStateFlow()

    fun setCompatibilityMode(on: Boolean) {
        prefs.edit().putBoolean(KEY_COMPAT, on).apply()
        _compatibilityMode.value = on
    }

    private companion object { const val KEY_COMPAT = "compatibility_mode" }
}
