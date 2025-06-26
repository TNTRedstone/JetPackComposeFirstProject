package com.calvinsteck.equineevents.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.navigation.NavController
import com.beust.klaxon.JsonArray
import com.beust.klaxon.JsonObject
import com.beust.klaxon.Klaxon
import com.beust.klaxon.Parser
import com.calvinsteck.equineevents.AppDestinations
import com.calvinsteck.equineevents.MainActivity
import com.calvinsteck.equineevents.data.DreamParkEvent
import com.calvinsteck.equineevents.data.EventDateParser
import com.calvinsteck.equineevents.data.StarredEventsManager
import com.calvinsteck.equineevents.scrapeDreamParkEvents
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

suspend fun getLastRefreshTimeString(context: Context): String? =
        context.dataStore.data.map { it[LAST_REFRESH_TIME_KEY] }.firstOrNull()

suspend fun getLastScrapeResult(context: Context): List<DreamParkEvent>? {
        val jsonString =
                context.dreamParkDataStore.data.map { it[LAST_SCRAPE_RESULT_KEY] }.firstOrNull()
                        ?: return null

        val parser = Parser.default()
        val json = parser.parse(StringBuilder(jsonString)) as? JsonArray<*> ?: return null

        return json.mapNotNull { item ->
                (item as? JsonObject)?.let { jsonObject ->
                        val eventMap = jsonObject.mapValues { entry -> entry.value.toString() }
                        val rawDateString = eventMap["dateOnly"] ?: "Date TBD"
                        val (startDate, endDate) = EventDateParser.parseDateString(rawDateString)

                        DreamParkEvent(
                                title = eventMap["title"] ?: "Unknown Event",
                                rawDateString = rawDateString,
                                startDate = startDate,
                                endDate = endDate,
                                link = eventMap["link"] ?: ""
                        )
                }
        }
}

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "lastRefresh")
val LAST_REFRESH_TIME_KEY = stringPreferencesKey("last_refresh_time")

val Context.dreamParkDataStore: DataStore<Preferences> by
        preferencesDataStore(name = "dreamParkData")
val LAST_SCRAPE_RESULT_KEY = stringPreferencesKey("last_scrape_result")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListViewScreen(navController: NavController, context: MainActivity) {
        val scrollState = rememberScrollState()
        val localContext = LocalContext.current
        val coroutineScope = rememberCoroutineScope()
        val starredEventsManager = remember { StarredEventsManager(localContext) }
        var dreamParkEvents by remember { mutableStateOf<List<DreamParkEvent>>(emptyList()) }
        var userEvents by remember { mutableStateOf<List<DreamParkEvent>>(emptyList()) }
        var isLoading by remember { mutableStateOf(true) }
        var errorMessage by remember { mutableStateOf<String?>(null) }
        var refreshTrigger by remember { mutableStateOf(0) }
        var showDeleteDialog by remember { mutableStateOf(false) }
        var eventToDelete by remember { mutableStateOf<DreamParkEvent?>(null) }
        var showStarredOnly by remember { mutableStateOf(false) }
        var selectedDaysOfWeek by remember {
                mutableStateOf(mutableSetOf<Int>())
        } // 1=Sunday, ..., 7=Saturday

        fun forceRefresh() {
                refreshTrigger++
        }

        suspend fun toggleEventStar(event: DreamParkEvent) {
                val newStarredStatus = starredEventsManager.toggleEventStar(event.uniqueId)
                dreamParkEvents =
                        dreamParkEvents.map {
                                if (it.uniqueId == event.uniqueId)
                                        it.copy(isStarred = newStarredStatus)
                                else it
                        }
        }

        LaunchedEffect(refreshTrigger) {
                isLoading = true
                errorMessage = null
                userEvents = withContext(Dispatchers.IO) { loadUserEvents(context) }
                try {
                        val lastRefreshTimeStr = getLastRefreshTimeString(localContext)
                        val lastRefreshMillis = lastRefreshTimeStr?.toLongOrNull()
                        val oneWeekInMillis = 7 * 24 * 60 * 60 * 1000L

                        val needsFreshScrape =
                                refreshTrigger > 0 ||
                                        lastRefreshMillis == null ||
                                        (System.currentTimeMillis() - lastRefreshMillis) >=
                                                oneWeekInMillis

                        val cachedScrapeResult = getLastScrapeResult(localContext)

                        if (needsFreshScrape || cachedScrapeResult == null) {
                                val scrapedEvents =
                                        withContext(Dispatchers.IO) {
                                                scrapeDreamParkEvents(localContext)
                                        }

                                val eventsWithStarredStatus =
                                        withContext(Dispatchers.IO) {
                                                starredEventsManager.applyStarredStatus(
                                                        scrapedEvents
                                                )
                                        }
                                dreamParkEvents = eventsWithStarredStatus + userEvents

                                withContext(Dispatchers.IO) {
                                        val currentTime = System.currentTimeMillis().toString()
                                        localContext.dataStore.edit {
                                                it[LAST_REFRESH_TIME_KEY] = currentTime
                                        }

                                        val eventsForJson =
                                                scrapedEvents.map { event ->
                                                        mapOf(
                                                                "title" to event.title,
                                                                "dateOnly" to event.rawDateString,
                                                                "link" to event.link
                                                        )
                                                }
                                        val jsonScrapedEvents = Klaxon().toJsonString(eventsForJson)
                                        localContext.dreamParkDataStore.edit {
                                                it[LAST_SCRAPE_RESULT_KEY] = jsonScrapedEvents
                                        }
                                }
                        } else {
                                val cachedEventsWithStarredStatus =
                                        withContext(Dispatchers.IO) {
                                                starredEventsManager.applyStarredStatus(
                                                        cachedScrapeResult
                                                )
                                        }
                                dreamParkEvents = cachedEventsWithStarredStatus + userEvents
                        }
                } catch (e: Exception) {
                        errorMessage = "Failed to load events: ${e.message}"
                        val fallbackCache = getLastScrapeResult(localContext)
                        if (fallbackCache != null) {
                                val fallbackEventsWithStarredStatus =
                                        withContext(Dispatchers.IO) {
                                                starredEventsManager.applyStarredStatus(
                                                        fallbackCache
                                                )
                                        }
                                dreamParkEvents = fallbackEventsWithStarredStatus + userEvents
                                errorMessage = "Showing cached data. ${e.message}"
                        }
                } finally {
                        isLoading = false
                }
        }

        val onEventClick: (String) -> Unit = { link ->
                if (!link.isNullOrEmpty()) {
                        val url =
                                if (link.startsWith("http://") || link.startsWith("https://")) link
                                else "https://$link"
                        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
                        localContext.startActivity(intent)
                }
        }

        Scaffold(
                modifier = Modifier.fillMaxSize(),
                topBar = {
                        LargeTopAppBar(
                                title = {
                                        Text(
                                                text = "Equine Events",
                                                style = MaterialTheme.typography.headlineMedium
                                        )
                                },
                                actions = {
                                        IconButton(
                                                onClick = { forceRefresh() },
                                                enabled = !isLoading
                                        ) {
                                                Icon(
                                                        imageVector = Icons.Filled.Refresh,
                                                        contentDescription = "Refresh events"
                                                )
                                        }
                                },
                                colors =
                                        TopAppBarDefaults.largeTopAppBarColors(
                                                containerColor = MaterialTheme.colorScheme.surface,
                                                titleContentColor =
                                                        MaterialTheme.colorScheme.onSurface
                                        )
                        )
                },
                floatingActionButton = {
                        FloatingActionButton(
                                onClick = { navController.navigate(AppDestinations.ADDEVENT) }
                        ) { Icon(Icons.Filled.Add, contentDescription = "Add Event") }
                }
        ) { innerPadding ->
                Column(
                        modifier =
                                Modifier.fillMaxSize()
                                        .verticalScroll(scrollState)
                                        .padding(innerPadding)
                                        .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                        // Filter controls
                        Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Starred only")
                                Switch(
                                        checked = showStarredOnly,
                                        onCheckedChange = { showStarredOnly = it },
                                        modifier = Modifier.padding(start = 8.dp, end = 16.dp)
                                )
                                val days = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
                                val dayNums =
                                        listOf(
                                                java.util.Calendar.MONDAY,
                                                java.util.Calendar.TUESDAY,
                                                java.util.Calendar.WEDNESDAY,
                                                java.util.Calendar.THURSDAY,
                                                java.util.Calendar.FRIDAY,
                                                java.util.Calendar.SATURDAY,
                                                java.util.Calendar.SUNDAY
                                        )
                                Row(
                                        modifier =
                                                Modifier.horizontalScroll(rememberScrollState())
                                                        .padding(start = 8.dp)
                                ) {
                                        Text(
                                                "Day:",
                                                modifier =
                                                        Modifier.align(Alignment.CenterVertically)
                                                                .padding(end = 8.dp)
                                        )
                                        days.forEachIndexed { idx, day ->
                                                val dayNum = dayNums[idx]
                                                FilterChip(
                                                        selected =
                                                                selectedDaysOfWeek.contains(dayNum),
                                                        onClick = {
                                                                selectedDaysOfWeek =
                                                                        selectedDaysOfWeek
                                                                                .toMutableSet()
                                                                                .apply {
                                                                                        if (contains(
                                                                                                        dayNum
                                                                                                )
                                                                                        )
                                                                                                remove(
                                                                                                        dayNum
                                                                                                )
                                                                                        else
                                                                                                add(
                                                                                                        dayNum
                                                                                                )
                                                                                }
                                                        },
                                                        label = { Text(day) },
                                                        modifier =
                                                                Modifier.padding(horizontal = 2.dp)
                                                )
                                        }
                                }
                        }
                        Spacer(Modifier.height(8.dp))
                        // Filter events
                        val filteredEvents =
                                dreamParkEvents.filter { event ->
                                        (!showStarredOnly || event.isStarred) &&
                                                (selectedDaysOfWeek.isEmpty() ||
                                                        run {
                                                                val cal = Calendar.getInstance()
                                                                val start = event.startDate
                                                                val end =
                                                                        event.endDate
                                                                                ?: event.startDate
                                                                cal.time = start
                                                                while (!cal.time.after(end)) {
                                                                        if (selectedDaysOfWeek
                                                                                        .contains(
                                                                                                cal.get(
                                                                                                        Calendar.DAY_OF_WEEK
                                                                                                )
                                                                                        )
                                                                        )
                                                                                return@run true
                                                                        cal.add(
                                                                                Calendar.DAY_OF_MONTH,
                                                                                1
                                                                        )
                                                                }
                                                                false
                                                        })
                                }
                        // Sort and group events by month/year
                        val sortedEvents = filteredEvents.sortedBy { it.startDate }
                        val groupedEvents =
                                sortedEvents.groupBy {
                                        SimpleDateFormat("MMMM yyyy", Locale.getDefault())
                                                .format(it.startDate)
                                }
                        groupedEvents.forEach { (monthYear, events) ->
                                Text(
                                        text = monthYear,
                                        style = MaterialTheme.typography.titleLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(vertical = 8.dp)
                                )
                                events.forEach { dreamParkEvent ->
                                        val isUserEvent =
                                                userEvents.any {
                                                        it.uniqueId == dreamParkEvent.uniqueId
                                                }
                                        EventCard(
                                                event = dreamParkEvent,
                                                onEventClick = onEventClick,
                                                onStarClick = { event ->
                                                        coroutineScope.launch {
                                                                toggleEventStar(event)
                                                        }
                                                },
                                                isUserEvent = isUserEvent,
                                                onLongPress = {
                                                        if (isUserEvent) {
                                                                eventToDelete = dreamParkEvent
                                                                showDeleteDialog = true
                                                        }
                                                }
                                        )
                                }
                        }
                        if (showDeleteDialog && eventToDelete != null) {
                                AlertDialog(
                                        onDismissRequest = { showDeleteDialog = false },
                                        title = { Text("Delete Event") },
                                        text = {
                                                Text("Are you sure you want to delete this event?")
                                        },
                                        confirmButton = {
                                                TextButton(
                                                        onClick = {
                                                                coroutineScope.launch {
                                                                        deleteUserEvent(
                                                                                context,
                                                                                eventToDelete!!
                                                                        )
                                                                        userEvents =
                                                                                withContext(
                                                                                        Dispatchers
                                                                                                .IO
                                                                                ) {
                                                                                        loadUserEvents(
                                                                                                context
                                                                                        )
                                                                                }
                                                                        // Also reload
                                                                        // dreamParkEvents to
                                                                        // ensure full refresh
                                                                        val lastRefreshTimeStr =
                                                                                getLastRefreshTimeString(
                                                                                        localContext
                                                                                )
                                                                        val lastRefreshMillis =
                                                                                lastRefreshTimeStr
                                                                                        ?.toLongOrNull()
                                                                        val oneWeekInMillis =
                                                                                7 *
                                                                                        24 *
                                                                                        60 *
                                                                                        60 *
                                                                                        1000L
                                                                        val needsFreshScrape =
                                                                                refreshTrigger >
                                                                                        0 ||
                                                                                        lastRefreshMillis ==
                                                                                                null ||
                                                                                        (System.currentTimeMillis() -
                                                                                                lastRefreshMillis) >=
                                                                                                oneWeekInMillis
                                                                        val cachedScrapeResult =
                                                                                getLastScrapeResult(
                                                                                        localContext
                                                                                )
                                                                        if (needsFreshScrape ||
                                                                                        cachedScrapeResult ==
                                                                                                null
                                                                        ) {
                                                                                val scrapedEvents =
                                                                                        withContext(
                                                                                                Dispatchers
                                                                                                        .IO
                                                                                        ) {
                                                                                                scrapeDreamParkEvents(
                                                                                                        localContext
                                                                                                )
                                                                                        }
                                                                                val eventsWithStarredStatus =
                                                                                        withContext(
                                                                                                Dispatchers
                                                                                                        .IO
                                                                                        ) {
                                                                                                starredEventsManager
                                                                                                        .applyStarredStatus(
                                                                                                                scrapedEvents
                                                                                                        )
                                                                                        }
                                                                                dreamParkEvents =
                                                                                        eventsWithStarredStatus +
                                                                                                userEvents
                                                                        } else {
                                                                                val cachedEventsWithStarredStatus =
                                                                                        withContext(
                                                                                                Dispatchers
                                                                                                        .IO
                                                                                        ) {
                                                                                                starredEventsManager
                                                                                                        .applyStarredStatus(
                                                                                                                cachedScrapeResult
                                                                                                        )
                                                                                        }
                                                                                dreamParkEvents =
                                                                                        cachedEventsWithStarredStatus +
                                                                                                userEvents
                                                                        }
                                                                        showDeleteDialog = false
                                                                        eventToDelete = null
                                                                }
                                                        }
                                                ) { Text("Delete") }
                                        },
                                        dismissButton = {
                                                TextButton(
                                                        onClick = {
                                                                showDeleteDialog = false
                                                                eventToDelete = null
                                                        }
                                                ) { Text("Cancel") }
                                        }
                                )
                        }
                }
        }
}

@Composable
private fun EventCard(
        event: DreamParkEvent,
        onEventClick: (String) -> Unit,
        onStarClick: (DreamParkEvent) -> Unit,
        isUserEvent: Boolean = false,
        onLongPress: (() -> Unit)? = null
) {
        val isClickable = !event.link.isNullOrEmpty()
        val modifier =
                if (isUserEvent && onLongPress != null) {
                        Modifier.fillMaxWidth().pointerInput(Unit) {
                                detectTapGestures(
                                        onLongPress = { onLongPress() },
                                        onTap = { if (isClickable) onEventClick(event.link) }
                                )
                        }
                } else {
                        Modifier.fillMaxWidth()
                                .then(
                                        if (isClickable)
                                                Modifier.clickable { onEventClick(event.link) }
                                        else Modifier
                                )
                }
        Card(
                modifier = modifier,
                colors =
                        CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                elevation =
                        CardDefaults.cardElevation(
                                defaultElevation = 4.dp,
                                pressedElevation = 8.dp
                        ),
                shape = RoundedCornerShape(12.dp)
        ) {
                Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                        // Title row with star button
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                                Text(
                                        text = event.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                )

                                // Prominent star button aligned with title
                                IconButton(
                                        onClick = { onStarClick(event) },
                                        modifier = Modifier.size(48.dp)
                                ) {
                                        Icon(
                                                imageVector =
                                                        if (event.isStarred) Icons.Filled.Star
                                                        else Icons.Outlined.StarBorder,
                                                contentDescription =
                                                        if (event.isStarred) "Unstar event"
                                                        else "Star event",
                                                modifier = Modifier.size(28.dp),
                                                tint =
                                                        if (event.isStarred) {
                                                                MaterialTheme.colorScheme.primary
                                                        } else {
                                                                MaterialTheme.colorScheme
                                                                        .onSurfaceVariant.copy(
                                                                        alpha = 0.7f
                                                                )
                                                        }
                                        )
                                }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Date row with View Details button
                        Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                        ) {
                                Icon(
                                        imageVector = Icons.Filled.CalendarMonth,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                        text = event.formattedDateString,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f)
                                )

                                // View Details button on edge, aligned with date row
                                if (!event.link.isNullOrEmpty()) {
                                        Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.padding(end = 12.dp)
                                        ) {
                                                Text(
                                                        text = "View Details",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.padding(end = 4.dp)
                                                )
                                                Icon(
                                                        imageVector =
                                                                Icons.AutoMirrored.Filled.OpenInNew,
                                                        contentDescription = "Open event link",
                                                        modifier = Modifier.size(16.dp),
                                                        tint = MaterialTheme.colorScheme.primary
                                                )
                                        }
                                }
                        }
                }
        }
}
