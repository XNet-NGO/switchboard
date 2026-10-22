package org.xnet.switchboard

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.xnet.switchboard.ui.ThemeMode

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val _routes = MutableStateFlow(RouteStore.load(app))
    val routes: StateFlow<List<Route>> = _routes.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** Networks currently available for egress pinning. Refreshed on demand. */
    private val _networks = MutableStateFlow(NetworkRepository.available(app))
    val networks: StateFlow<List<EgressNetwork>> = _networks.asStateFlow()

    /** Light / dark / follow-system theme preference (persisted). */
    private val _themeMode = MutableStateFlow(loadThemeMode(app))
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        prefs().edit().putString(KEY_THEME, mode.name).apply()
    }

    fun refreshNetworks() {
        _networks.value = NetworkRepository.available(getApplication())
    }

    fun addRoute(r: Route) {
        _routes.value = _routes.value + r
        persist()
    }

    fun removeRoute(id: String) {
        _routes.value = _routes.value.filterNot { it.id == id }
        persist()
    }

    fun start() {
        if (_routes.value.isEmpty()) return
        ForwarderService.start(getApplication())
        _running.value = true
    }

    fun stop() {
        ForwarderService.stop(getApplication())
        _running.value = false
    }

    private fun persist() = RouteStore.save(getApplication(), _routes.value)

    private fun prefs() = getApplication<Application>()
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun loadThemeMode(ctx: Context): ThemeMode {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_THEME, ThemeMode.SYSTEM.name)
        return runCatching { ThemeMode.valueOf(raw!!) }.getOrDefault(ThemeMode.SYSTEM)
    }

    private companion object {
        const val PREFS = "switchboard.settings"
        const val KEY_THEME = "theme_mode"
    }
}
