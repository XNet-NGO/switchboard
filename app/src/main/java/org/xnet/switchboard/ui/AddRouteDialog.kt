package org.xnet.switchboard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.xnet.switchboard.EgressMode
import org.xnet.switchboard.EgressNetwork
import org.xnet.switchboard.Proto
import org.xnet.switchboard.Route

@Composable
fun AddRouteDialog(
    networks: List<EgressNetwork>,
    onRefreshNetworks: () -> Unit,
    onDismiss: () -> Unit,
    onAdd: (Route) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var proto by remember { mutableStateOf(Proto.TCP) }
    var listenAddr by remember { mutableStateOf("127.0.0.1") }
    var listenPort by remember { mutableStateOf("") }
    var targetAddr by remember { mutableStateOf("127.0.0.1") }
    var targetPort by remember { mutableStateOf("") }
    var egress by remember { mutableStateOf(EgressMode.NONE) }
    var egressArg by remember { mutableStateOf("") }
    var selectedNetwork by remember { mutableStateOf<EgressNetwork?>(null) }

    val portsValid = listenPort.toIntOrNull() != null && targetPort.toIntOrNull() != null
    val egressValid = when (egress) {
        EgressMode.SOURCE, EgressMode.DEVICE -> egressArg.isNotBlank()
        EgressMode.NETWORK -> selectedNetwork != null
        EgressMode.NONE -> true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add route") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Name (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = proto == Proto.TCP,
                        onClick = { proto = Proto.TCP },
                        label = { Text("TCP") },
                    )
                    FilterChip(
                        selected = proto == Proto.UDP,
                        onClick = { proto = Proto.UDP },
                        label = { Text("UDP") },
                    )
                }
                OutlinedTextField(
                    value = listenAddr, onValueChange = { listenAddr = it },
                    label = { Text("Listen address") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = listenPort, onValueChange = { listenPort = it.filter(Char::isDigit) },
                    label = { Text("Listen port") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = targetAddr, onValueChange = { targetAddr = it },
                    label = { Text("Target address") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = targetPort, onValueChange = { targetPort = it.filter(Char::isDigit) },
                    label = { Text("Target port") },
                    modifier = Modifier.fillMaxWidth(),
                )

                Text("Egress", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    EgressChip("Default", egress == EgressMode.NONE) {
                        egress = EgressMode.NONE
                    }
                    EgressChip("Network", egress == EgressMode.NETWORK) {
                        egress = EgressMode.NETWORK
                        onRefreshNetworks()
                    }
                    EgressChip("Source IP", egress == EgressMode.SOURCE) {
                        egress = EgressMode.SOURCE
                    }
                }

                when (egress) {
                    EgressMode.NETWORK -> {
                        if (networks.isEmpty()) {
                            Text(
                                "No networks available.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        } else {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                networks.forEach { net ->
                                    FilterChip(
                                        selected = selectedNetwork?.handle == net.handle,
                                        onClick = { selectedNetwork = net },
                                        label = { Text(net.label) },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
                    }
                    EgressMode.SOURCE -> {
                        OutlinedTextField(
                            value = egressArg, onValueChange = { egressArg = it },
                            label = { Text("Source IP (e.g. 10.0.0.2)") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    else -> {}
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = portsValid && egressValid,
                onClick = {
                    onAdd(
                        Route(
                            name = name,
                            proto = proto,
                            listenAddr = listenAddr.ifBlank { "127.0.0.1" },
                            listenPort = listenPort.toInt(),
                            targetAddr = targetAddr.ifBlank { "127.0.0.1" },
                            targetPort = targetPort.toInt(),
                            egress = egress,
                            egressArg = if (egress == EgressMode.SOURCE) egressArg else "",
                            networkHandle = if (egress == EgressMode.NETWORK)
                                selectedNetwork?.handle ?: 0L else 0L,
                            transport = if (egress == EgressMode.NETWORK)
                                selectedNetwork?.transport ?: org.xnet.switchboard.NetTransport.ANY
                            else org.xnet.switchboard.NetTransport.ANY,
                        ),
                    )
                },
            ) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun EgressChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}
