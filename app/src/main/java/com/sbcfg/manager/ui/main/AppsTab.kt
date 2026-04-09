package com.sbcfg.manager.ui.main

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sbcfg.manager.domain.model.AppMode

@Composable
fun AppsTab(
    viewModel: AppsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            state.isLoading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            state.rules.isEmpty() -> EmptyState()
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp
                )
            ) {
                items(items = state.rules, key = { it.packageName }) { app ->
                    AppRuleItem(
                        app = app,
                        onModeChanged = { mode ->
                            viewModel.onSetAppMode(app.packageName, app.appName, mode)
                        },
                        onRemove = { viewModel.onRemoveApp(app.packageName) }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = viewModel::onShowPicker,
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text("Добавить") },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
    }

    if (state.showPicker) {
        AppPickerDialog(
            search = state.pickerSearch,
            items = state.pickerItems.filter {
                state.pickerSearch.isBlank() ||
                    it.appName.lowercase().contains(state.pickerSearch.lowercase()) ||
                    it.packageName.lowercase().contains(state.pickerSearch.lowercase())
            },
            onSearchChanged = viewModel::onPickerSearchChanged,
            onPick = { item -> viewModel.onPickApp(item.packageName, item.appName) },
            onDismiss = viewModel::onDismissPicker
        )
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Filled.Apps,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(64.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Список приложений пуст",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "По умолчанию все приложения идут мимо туннеля. Нажмите «Добавить», чтобы выбрать приложения для маршрутизации через VPN.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

private fun drawableToBitmapPainter(drawable: Drawable): BitmapPainter {
    val width = drawable.intrinsicWidth.coerceAtLeast(1)
    val height = drawable.intrinsicHeight.coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return BitmapPainter(bitmap.asImageBitmap())
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppRuleItem(
    app: AppsViewModel.AppWithRule,
    onModeChanged: (AppMode) -> Unit,
    onRemove: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(app.icon, app.appName)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.appName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = buildString {
                        append(app.packageName)
                        if (app.isFromServer) append(" • серверный")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!app.isFromServer) {
                IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Удалить",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 52.dp)
        ) {
            AppMode.entries.forEach { mode ->
                val selected = app.mode == mode
                AssistChip(
                    onClick = { onModeChanged(mode) },
                    label = { Text(mode.label()) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = if (selected)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surfaceContainerHigh,
                        labelColor = if (selected)
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else
                            MaterialTheme.colorScheme.onSurface
                    )
                )
            }
        }
    }
}

@Composable
private fun AppIcon(drawable: Drawable?, name: String) {
    if (drawable != null) {
        val painter = remember(drawable) { drawableToBitmapPainter(drawable) }
        Image(
            painter = painter,
            contentDescription = name,
            modifier = Modifier.size(40.dp)
        )
    } else {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = name.firstOrNull()?.uppercase() ?: "?",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun AppPickerDialog(
    search: String,
    items: List<AppsViewModel.PickerItem>,
    onSearchChanged: (String) -> Unit,
    onPick: (AppsViewModel.PickerItem) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Добавить приложение") },
        text = {
            Column {
                OutlinedTextField(
                    value = search,
                    onValueChange = onSearchChanged,
                    placeholder = { Text("Поиск...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                if (items.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (search.isBlank()) "Все приложения уже добавлены"
                            else "Ничего не найдено",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 240.dp, max = 480.dp)
                    ) {
                        items(items = items, key = { it.packageName }) { item ->
                            Surface(
                                onClick = { onPick(item) },
                                color = Color.Transparent,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(vertical = 10.dp)
                                ) {
                                    AppIcon(item.icon, item.appName)
                                    Spacer(Modifier.width(12.dp))
                                    Column {
                                        Text(item.appName, style = MaterialTheme.typography.bodyLarge)
                                        Text(
                                            item.packageName,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        }
    )
}

private fun AppMode.label(): String = when (this) {
    AppMode.PROXY -> "VPN"
    AppMode.DIRECT -> "Авто"
    AppMode.BYPASS -> "Без VPN"
}
