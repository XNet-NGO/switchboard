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
    // Route type: 0=Forward, 1=SOCKS5 proxy, 2=Encrypted tunnel.
    var routeType by remember { mutableStateOf(0) }
    val socks = routeType == 1
    val tunnel = routeType == 2
    var proto by remember { mutableStateOf(Proto.TCP) }
    var listenAddr by remember { mutableStateOf("127.0.0.1") }
    var listenPort by remember { mutableStateOf("") }
    var targetAddr by remember { mutableStateOf("127.0.0.1") }
    var targetPort by remember { mutableStateOf("") }
    // Tunnel-specific
    var serverAddr by remember { mutableStateOf("") }
    var serverPort by remember { mutableStateOf("") }
    var psk by remember { mutableStateOf("") }
    var cipherChacha by remember { mutableStateOf(false) }
    // Reverse (v0.4.0, tunnel-only)
    var revListenPort by remember { mutableStateOf("") }
    var revTargetAddr by remember { mutableStateOf("") }
    var revTargetPort by remember { mutableStateOf("") }
    var revProto by remember { mutableStateOf(0) } // 0=TCP, 1=UDP, 2=BOTH
    var egress by remember { mutableStateOf(EgressMode.NONE) }
    var egressArg by remember { mutableStateOf("") }
    var selectedNetwork by remember { mutableStateOf<EgressNetwork?>(null) }

    // Validity by type.
    val portsValid = when {
        tunnel -> {
            val basicValid = serverPort.toIntOrNull() != null &&
                (targetPort.toIntOrNull() != null) &&
                serverAddr.isNotBlank() &&
                psk.length == 64 && psk.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
            // Reverse is optional; if any reverse field is set, validate them all.
            val revUsed = revListenPort.isNotBlank() || revTargetAddr.isNotBlank() || revTargetPort.isNotBlank()
            val revValid = !revUsed || (revListenPort.toIntOrNull() != null &&
                revTargetPort.toIntOrNull() != null && revTargetAddr.isNotBlank())
            basicValid && revValid
        }
        socks -> listenPort.toIntOrNull() != null
        else -> listenPort.toIntOrNull() != null && targetPort.toIntOrNull() != null
    }
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
                Text("Route type", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = routeType == 0,
                        onClick = { routeType = 0 },
                        label = { Text("Forward") },
                    )
                    FilterChip(
                        selected = routeType == 1,
                        onClick = { routeType = 1 },
                        label = { Text("SOCKS5 proxy") },
                    )
                    FilterChip(
                        selected = routeType == 2,
                        onClick = {
                            routeType = 2
                            // sensible default target for the adb use case
                            if (targetPort.isBlank()) targetPort = "5555"
                        },
                        label = { Text("Tunnel") },
                    )
                }
                if (routeType == 0) {
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
                }

                if (tunnel) {
                    // Encrypted tunnel client: server + PSK + cipher + target.
                    OutlinedTextField(
                        value = serverAddr, onValueChange = { serverAddr = it },
                        label = { Text("Server address (rendezvous box)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = serverPort, onValueChange = { serverPort = it.filter(Char::isDigit) },
                        label = { Text("Server port") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = psk, onValueChange = { psk = it.trim() },
                        label = { Text("Pre-shared key (64 hex chars)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = !cipherChacha,
                            onClick = { cipherChacha = false },
                            label = { Text("AES-GCM") },
                        )
                        FilterChip(
                            selected = cipherChacha,
                            onClick = { cipherChacha = true },
                            label = { Text("ChaCha20") },
                        )
                    }
                    OutlinedTextField(
                        value = targetAddr, onValueChange = { targetAddr = it },
                        label = { Text("Local target address") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = targetPort, onValueChange = { targetPort = it.filter(Char::isDigit) },
                        label = { Text("Local target port (e.g. 5555 = adbd)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // Reverse (v0.4.0): optional reverse listener on client
                    Text("Reverse listener (optional)", style = MaterialTheme.typography.labelLarge)
                    OutlinedTextField(
                        value = revListenPort, onValueChange = { revListenPort = it.filter(Char::isDigit) },
                        label = { Text("Reverse listen port (empty = no reverse)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (revListenPort.isNotBlank()) {
                        OutlinedTextField(
                            value = revTargetAddr, onValueChange = { revTargetAddr = it },
                            label = { Text("Reverse target address (on server)") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = revTargetPort, onValueChange = { revTargetPort = it.filter(Char::isDigit) },
                            label = { Text("Reverse target port") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = revProto == 0,
                                onClick = { revProto = 0 },
                                label = { Text("TCP") },
                            )
                            FilterChip(
                                selected = revProto == 1,
                                onClick = { revProto = 1 },
                                label = { Text("UDP") },
                            )
                            FilterChip(
                                selected = revProto == 2,
                                onClick = { revProto = 2 },
                                label = { Text("BOTH") },
                            )
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = listenAddr, onValueChange = { listenAddr = it },
                        label = { Text("Listen address") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = listenPort, onValueChange = { listenPort = it.filter(Char::isDigit) },
                        label = { Text(if (socks) "Listen port (SOCKS5, e.g. 1080)" else "Listen port") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (!socks) {
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
                    }
                }

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
                            listenAddr = if (tunnel) "" else listenAddr.ifBlank { "127.0.0.1" },
                            listenPort = if (tunnel || socks) {
                                if (socks) listenPort.toInt() else 0
                            } else listenPort.toInt(),
                            targetAddr = when {
                                socks -> ""
                                else -> targetAddr.ifBlank { "127.0.0.1" }
                            },
                            targetPort = when {
                                socks -> 0
                                else -> targetPort.toInt()
                            },
                            egress = egress,
                            egressArg = if (egress == EgressMode.SOURCE) egressArg else "",
                            networkHandle = if (egress == EgressMode.NETWORK)
                                selectedNetwork?.handle ?: 0L else 0L,
                            transport = if (egress == EgressMode.NETWORK)
                                selectedNetwork?.transport ?: org.xnet.switchboard.NetTransport.ANY
                            else org.xnet.switchboard.NetTransport.ANY,
                            socks = socks,
                            tunnel = tunnel,
                            serverAddr = if (tunnel) serverAddr else "",
                            serverPort = if (tunnel) serverPort.toInt() else 0,
                            psk = if (tunnel) psk else "",
                            cipher = if (tunnel && cipherChacha) 1 else 0,
                            revListenAddr = if (tunnel && revListenPort.isNotBlank()) "" else "",
                            revListenPort = if (tunnel) revListenPort.toIntOrNull() ?: 0 else 0,
                            revTargetAddr = if (tunnel && revListenPort.isNotBlank())
                                revTargetAddr.ifBlank { "127.0.0.1" } else "",
                            revTargetPort = if (tunnel) revTargetPort.toIntOrNull() ?: 0 else 0,
                            revProto = if (tunnel && revListenPort.isNotBlank()) revProto else 0,
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
