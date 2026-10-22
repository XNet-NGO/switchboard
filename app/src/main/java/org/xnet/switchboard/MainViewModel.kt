package org.xnet.switchboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val _routes = MutableStateFlow(RouteStore.load(app))
    val routes: StateFlow<List<Route>> = _routes.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

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
}
