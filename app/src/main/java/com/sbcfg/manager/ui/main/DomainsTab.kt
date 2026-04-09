package com.sbcfg.manager.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sbcfg.manager.data.local.entity.CustomDomainEntity
import com.sbcfg.manager.domain.model.DomainMode

@Composable
fun DomainsTab(
    viewModel: DomainsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedFilter by remember { mutableStateOf<String?>(null) }

    val filteredDomains = if (selectedFilter != null) {
        state.domains.filter { it.mode == selectedFilter }
    } else {
        state.domains
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Filter chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedFilter == null,
                    onClick = { selectedFilter = null },
                    label = { Text("Все") }
                )
                FilterChip(
                    selected = selectedFilter == "direct",
                    onClick = { selectedFilter = if (selectedFilter == "direct") null else "direct" },
                    label = { Text("Direct") }
                )
                FilterChip(
                    selected = selectedFilter == "proxy",
                    onClick = { selectedFilter = if (selectedFilter == "proxy") null else "proxy" },
                    label = { Text("Proxy") }
                )
            }

            // Domain list
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(
                    items = filteredDomains,
                    key = { it.id }
                ) { domain ->
                    DomainItem(
                        domain = domain,
                        onDelete = {
                            if (!domain.isFromServer) {
                                viewModel.onDeleteDomain(domain)
                            }
                        }
                    )
                }
            }
        }

        // FAB
        FloatingActionButton(
            onClick = viewModel::onShowAddDialog,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            Icon(Icons.Filled.Add, contentDescription = "Добавить домен")
        }

        // Add dialog
        if (state.showAddDialog) {
            AddDomainDialog(
                validationError = state.validationError,
                onDismiss = viewModel::onDismissAddDialog,
                onAdd = { domain, mode -> viewModel.onAddDomain(domain, mode) }
            )
        }
    }
}

@Composable
private fun DomainItem(
    domain: CustomDomainEntity,
    onDelete: () -> Unit
) {
    ListItem(
        headlineContent = { Text(domain.domain) },
        supportingContent = {
            Text(
                text = buildString {
                    append(domain.mode.uppercase())
                    if (domain.isFromServer) append(" \u2022 серверный")
                }
            )
        },
        leadingContent = {
            if (domain.isFromServer) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = "Серверный домен",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        trailingContent = {
            if (!domain.isFromServer) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Удалить")
                }
            }
        }
    )
}

@Composable
private fun AddDomainDialog(
    validationError: String?,
    onDismiss: () -> Unit,
    onAdd: (String, DomainMode) -> Unit
) {
    var domain by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(DomainMode.DIRECT) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Добавить домен") },
        text = {
            Column {
                OutlinedTextField(
                    value = domain,
                    onValueChange = { domain = it },
                    label = { Text("Домен") },
                    placeholder = { Text("example.com") },
                    singleLine = true,
                    isError = validationError != null,
                    supportingText = validationError?.let { error -> { Text(error) } },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = mode == DomainMode.DIRECT,
                        onClick = { mode = DomainMode.DIRECT },
                        label = { Text("Direct") }
                    )
                    FilterChip(
                        selected = mode == DomainMode.PROXY,
                        onClick = { mode = DomainMode.PROXY },
                        label = { Text("Proxy") }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(domain.trim(), mode) }) {
                Text("Добавить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}
