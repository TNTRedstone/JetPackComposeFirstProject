package com.calvinsteck.equineevents.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.navigation.NavController
import com.calvinsteck.equineevents.MainActivity
import com.calvinsteck.equineevents.data.DreamParkEvent
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

val USER_EVENTS_KEY = stringSetPreferencesKey("user_events")

suspend fun saveUserEvent(context: MainActivity, event: DreamParkEvent) {
    val json =
            "${event.uniqueId}|||${event.title}|||${event.rawDateString}|||${event.startDate.time}|||${event.endDate?.time ?: ""}|||${event.link ?: ""}"
    context.dreamParkDataStore.edit { prefs ->
        val current = prefs[USER_EVENTS_KEY] ?: emptySet()
        prefs[USER_EVENTS_KEY] = current + json
    }
}

suspend fun loadUserEvents(context: MainActivity): List<DreamParkEvent> {
    val prefs = context.dreamParkDataStore.data.firstOrNull() as? Preferences ?: return emptyList()
    val set = prefs[USER_EVENTS_KEY] ?: emptySet()
    return set.mapNotNull { str ->
        val parts = str.split("|||")
        if (parts.size >= 6) {
            val title = parts[1]
            val rawDateString = parts[2]
            val startMillis = parts[3]
            val endMillis = parts[4]
            val link = parts[5]
            DreamParkEvent(
                    title = title,
                    rawDateString = rawDateString,
                    startDate = Date(startMillis.toLong()),
                    endDate =
                            endMillis.takeIf { it.isNotEmpty() }?.toLongOrNull()?.let { Date(it) },
                    link = link.ifBlank { "" },
                    isStarred = false
            )
        } else null
    }
}

fun millisToLocalDate(millis: Long): Date {
    val calendar = Calendar.getInstance()
    calendar.timeInMillis = millis
    calendar.set(Calendar.HOUR_OF_DAY, 0)
    calendar.set(Calendar.MINUTE, 0)
    calendar.set(Calendar.SECOND, 0)
    calendar.set(Calendar.MILLISECOND, 0)
    calendar.add(Calendar.DAY_OF_MONTH, 1)
    return calendar.time
}

suspend fun deleteUserEvent(context: MainActivity, event: DreamParkEvent) {
    context.dreamParkDataStore.edit { prefs ->
        val current = prefs[USER_EVENTS_KEY] ?: emptySet()
        val filtered = current.filterNot { it.startsWith(event.uniqueId + "|||") }.toSet()
        prefs[USER_EVENTS_KEY] = filtered
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEventScreen(navController: NavController, context: MainActivity) {
    var title by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    var isMultiDay by remember { mutableStateOf(false) }
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }
    var startDate by remember { mutableStateOf<Date?>(null) }
    var endDate by remember { mutableStateOf<Date?>(null) }
    val today = remember { Date() }
    val canSave = title.isNotBlank() && startDate != null && (!isMultiDay || endDate != null)
    val scope = rememberCoroutineScope()

    Scaffold(
            topBar = {
                TopAppBar(
                        title = { Text("Add Event") },
                        navigationIcon = {
                            IconButton(onClick = { navController.popBackStack() }) {
                                Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Back"
                                )
                            }
                        }
                )
            }
    ) { innerPadding ->
        Column(
                modifier = Modifier.fillMaxSize().padding(innerPadding).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title*") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                    value = link,
                    onValueChange = { link = it },
                    label = { Text("Link (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Multi-day event")
                Spacer(Modifier.width(8.dp))
                Switch(
                        checked = isMultiDay,
                        onCheckedChange = { isMultiDay = it },
                        colors = SwitchDefaults.colors()
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Start date: ")
                TextButton(onClick = { showStartPicker = true }) {
                    Text(
                            startDate?.let {
                                SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(it)
                            }
                                    ?: "Pick date"
                    )
                }
            }
            if (isMultiDay) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("End date: ")
                    TextButton(onClick = { showEndPicker = true }) {
                        Text(
                                endDate?.let {
                                    SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(it)
                                }
                                        ?: "Pick date"
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Button(
                    onClick = {
                        val event =
                                DreamParkEvent(
                                        title = title,
                                        rawDateString =
                                                if (isMultiDay && endDate != null) {
                                                    SimpleDateFormat(
                                                                    "MMMM d, yyyy",
                                                                    Locale.getDefault()
                                                            )
                                                            .format(startDate!!) +
                                                            " - " +
                                                            SimpleDateFormat(
                                                                            "MMMM d, yyyy",
                                                                            Locale.getDefault()
                                                                    )
                                                                    .format(endDate!!)
                                                } else {
                                                    SimpleDateFormat(
                                                                    "MMMM d, yyyy",
                                                                    Locale.getDefault()
                                                            )
                                                            .format(startDate!!)
                                                },
                                        startDate = startDate!!,
                                        endDate = if (isMultiDay) endDate else null,
                                        link = link.ifBlank { "" },
                                        isStarred = false
                                )
                        scope.launch {
                            saveUserEvent(context, event)
                            navController.popBackStack()
                        }
                    },
                    enabled = canSave,
                    modifier = Modifier.fillMaxWidth()
            ) { Text("Save Event") }
        }
        if (showStartPicker) {
            val state =
                    rememberDatePickerState(
                            initialSelectedDateMillis = startDate?.time ?: today.time
                    )
            DatePickerDialog(
                    onDismissRequest = { showStartPicker = false },
                    confirmButton = {
                        TextButton(
                                onClick = {
                                    showStartPicker = false
                                    state.selectedDateMillis?.let {
                                        startDate = millisToLocalDate(it)
                                    }
                                }
                        ) { Text("OK") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showStartPicker = false }) { Text("Cancel") }
                    }
            ) { DatePicker(state = state, showModeToggle = false) }
        }
        if (showEndPicker) {
            val state =
                    rememberDatePickerState(initialSelectedDateMillis = endDate?.time ?: today.time)
            DatePickerDialog(
                    onDismissRequest = { showEndPicker = false },
                    confirmButton = {
                        TextButton(
                                onClick = {
                                    showEndPicker = false
                                    state.selectedDateMillis?.let {
                                        endDate = millisToLocalDate(it)
                                    }
                                }
                        ) { Text("OK") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showEndPicker = false }) { Text("Cancel") }
                    }
            ) { DatePicker(state = state, showModeToggle = false) }
        }
    }
}
