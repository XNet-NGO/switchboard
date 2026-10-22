package org.xnet.switchboard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
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
import org.xnet.switchboard.Proto
import org.xnet.switchboard.Route

@Composable
fun AddRouteDialog(onDismiss: () -> Unit, onAdd: (Route) -> Unit) {
    var name by remember { mutableStateOf("") }
    var proto by remember { mutableStateOf(Proto.TCP) }
    var listenAddr by remember { mutableStateOf("127.0.0.1") }
    var listenPort by remember { mutableStateOf("") }
    var targetAddr by remember { mutableStateOf("127.0.0.1") }
    var targetPort by remember { mutableStateOf("") }

    val valid = listenPort.toIntOrNull() != null && targetPort.toIntOrNull() != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add route") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onAdd(
                        Route(
                            name = name,
                            proto = proto,
                            listenAddr = listenAddr.ifBlank { "127.0.0.1" },
                            listenPort = listenPort.toInt(),
                            targetAddr = targetAddr.ifBlank { "127.0.0.1" },
                            targetPort = targetPort.toInt(),
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
