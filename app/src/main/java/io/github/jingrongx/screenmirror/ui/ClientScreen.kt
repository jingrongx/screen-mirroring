package io.github.jingrongx.screenmirror.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.jingrongx.screenmirror.client.ClientViewModel
import io.github.jingrongx.screenmirror.client.ControllerSession
import io.github.jingrongx.screenmirror.discovery.NsdDiscovery

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientScreen(
    onBack: () -> Unit,
    vm: ClientViewModel = viewModel(),
) {
    val sessionUi = vm.sessionState

    // 会话进行中（连接中/已连接）直接进入远程画面
    if (sessionUi !is ControllerSession.UiState.Idle) {
        RemoteScreen(vm)
        return
    }

    DisposableDiscovery(vm)

    val failure = vm.failure
    if (failure != null) {
        AlertDialog(
            onDismissRequest = { vm.clearFailure() },
            title = { Text("连接已断开") },
            text = { Text(failure) },
            confirmButton = {
                TextButton(onClick = { vm.clearFailure() }) { Text("好的") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("控制端") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        vm.stopDiscovery()
                        vm.startDiscovery()
                    }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            Text(
                "同一 Wi-Fi 下的受控设备",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))

            if (vm.discoveryError != null) {
                Text(
                    vm.discoveryError ?: "",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
            }

            if (vm.devices.isEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.height(20.dp))
                    Spacer(Modifier.height(0.dp))
                    Text(
                        "  正在搜索设备…请确认受控端已开始共享",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(vm.devices, key = { it.host + ":" + it.port }) { device ->
                        Card(onClick = { vm.connect(device) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text(device.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${device.host}:${device.port}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            ManualConnectRow(vm)
        }
    }
}

@Composable
private fun DisposableDiscovery(vm: ClientViewModel) {
    androidx.compose.runtime.DisposableEffect(vm) {
        vm.startDiscovery()
        onDispose { vm.stopDiscovery() }
    }
}

@Composable
private fun ManualConnectRow(vm: ClientViewModel) {
    var manualIp by remember { mutableStateOf("") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("手动连接（搜不到设备时）", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = manualIp,
                onValueChange = { manualIp = it },
                label = { Text("受控端 IP（如 192.168.1.23）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = {
                    val input = manualIp.trim()
                    if (input.isEmpty()) return@OutlinedButton
                    val (host, port) = if (input.contains(':')) {
                        val idx = input.lastIndexOf(':')
                        input.substring(0, idx) to input.substring(idx + 1).toIntOrNull()
                    } else {
                        input to null
                    }
                    vm.connect(
                        NsdDiscovery.DiscoveredDevice(host, host, port ?: 40000)
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("连接") }
        }
    }
}
