package org.xnet.switchboard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.xnet.switchboard.MainViewModel
import org.xnet.switchboard.Route

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwitchboardApp(vm: MainViewModel) {
    MaterialTheme {
        val routes by vm.routes.collectAsStateWithLifecycle()
        val running by vm.running.collectAsStateWithLifecycle()
        val networks by vm.networks.collectAsStateWithLifecycle()
        var showAdd by remember { mutableStateOf(false) }

        Scaffold(
            topBar = { TopAppBar(title = { Text("Switchboard") }) },
            floatingActionButton = {
                FloatingActionButton(onClick = { showAdd = true }) {
                    Text("+", style = MaterialTheme.typography.headlineSmall)
                }
            },
        ) { pad ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(pad)
                    .padding(16.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = { if (running) vm.stop() else vm.start() },
                        enabled = routes.isNotEmpty(),
                    ) { Text(if (running) "Stop" else "Start") }
                    Text(
                        if (running) "Forwarding ${routes.size} route(s)"
                        else "Stopped",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                Spacer(Modifier.padding(8.dp))

                if (routes.isEmpty()) {
                    Text(
                        "No routes yet. Tap + to add a port forward.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(routes, key = { it.id }) { r ->
                            RouteCard(r, onDelete = { vm.removeRoute(r.id) })
                        }
                    }
                }
            }
        }

        if (showAdd) {
            AddRouteDialog(
                networks = networks,
                onRefreshNetworks = { vm.refreshNetworks() },
                onDismiss = { showAdd = false },
                onAdd = { route ->
                    vm.addRoute(route)
                    showAdd = false
                },
            )
        }
    }
}

@Composable
private fun RouteCard(r: Route, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                if (r.name.isNotBlank()) {
                    Text(r.name, style = MaterialTheme.typography.titleMedium)
                }
                Text(r.summary(), style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(onClick = onDelete) {
                Text("Delete")
            }
        }
    }
}
