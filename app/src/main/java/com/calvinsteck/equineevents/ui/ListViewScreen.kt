package com.calvinsteck.equineevents.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.calvinsteck.equineevents.MainActivity
import com.calvinsteck.equineevents.scrapeDreamParkEvents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import android.content.Intent
import androidx.core.net.toUri
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import com.beust.klaxon.Klaxon
import com.beust.klaxon.JsonArray
import com.beust.klaxon.JsonObject
import com.beust.klaxon.Parser
import com.calvinsteck.equineevents.data.DreamParkEvent
import com.calvinsteck.equineevents.data.StarredEventsManager
import com.calvinsteck.equineevents.data.EventDateParser

suspend fun getLastRefreshTimeString(context: Context): String? =
    context.dataStore.data.map { it[LAST_REFRESH_TIME_KEY] }.firstOrNull()

suspend fun getLastScrapeResult(context: Context): List<DreamParkEvent>? {
    val jsonString = context.dreamParkDataStore.data
        .map { it[LAST_SCRAPE_RESULT_KEY] }
        .firstOrNull() ?: return null

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

val Context.dreamParkDataStore: DataStore<Preferences> by preferencesDataStore(name = "dreamParkData")
val LAST_SCRAPE_RESULT_KEY = stringPreferencesKey("last_scrape_result")


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListViewScreen(navController: NavController, context: MainActivity) {
    val scrollState = rememberScrollState()
    val localContext = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val starredEventsManager = remember { StarredEventsManager(localContext) }
    var dreamParkEvents by remember { mutableStateOf<List<DreamParkEvent>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var refreshTrigger by remember { mutableStateOf(0) }

    fun forceRefresh() { refreshTrigger++ }

    suspend fun toggleEventStar(event: DreamParkEvent) {
        val newStarredStatus = starredEventsManager.toggleEventStar(event.uniqueId)
        dreamParkEvents = dreamParkEvents.map {
            if (it.uniqueId == event.uniqueId) it.copy(isStarred = newStarredStatus) else it
        }
    }

    LaunchedEffect(refreshTrigger) {
        isLoading = true
        errorMessage = null

        try {
            val lastRefreshTimeStr = getLastRefreshTimeString(localContext)
            val lastRefreshMillis = lastRefreshTimeStr?.toLongOrNull()
            val oneWeekInMillis = 7 * 24 * 60 * 60 * 1000L

            val needsFreshScrape = refreshTrigger > 0 ||
                lastRefreshMillis == null ||
                (System.currentTimeMillis() - lastRefreshMillis) >= oneWeekInMillis

            val cachedScrapeResult = getLastScrapeResult(localContext)

            if (needsFreshScrape || cachedScrapeResult == null) {
                val scrapedEvents = withContext(Dispatchers.IO) {
                    scrapeDreamParkEvents(localContext)
                }

                val eventsWithStarredStatus = withContext(Dispatchers.IO) {
                    starredEventsManager.applyStarredStatus(scrapedEvents)
                }
                dreamParkEvents = eventsWithStarredStatus

                withContext(Dispatchers.IO) {
                    val currentTime = System.currentTimeMillis().toString()
                    localContext.dataStore.edit { it[LAST_REFRESH_TIME_KEY] = currentTime }

                    val eventsForJson = scrapedEvents.map { event ->
                        mapOf(
                            "title" to event.title,
                            "dateOnly" to event.rawDateString,
                            "link" to event.link
                        )
                    }
                    val jsonScrapedEvents = Klaxon().toJsonString(eventsForJson)
                    localContext.dreamParkDataStore.edit { it[LAST_SCRAPE_RESULT_KEY] = jsonScrapedEvents }
                }
            } else {
                val cachedEventsWithStarredStatus = withContext(Dispatchers.IO) {
                    starredEventsManager.applyStarredStatus(cachedScrapeResult)
                }
                dreamParkEvents = cachedEventsWithStarredStatus
            }
        } catch (e: Exception) {
            errorMessage = "Failed to load events: ${e.message}"
            val fallbackCache = getLastScrapeResult(localContext)
            if (fallbackCache != null) {
                val fallbackEventsWithStarredStatus = withContext(Dispatchers.IO) {
                    starredEventsManager.applyStarredStatus(fallbackCache)
                }
                dreamParkEvents = fallbackEventsWithStarredStatus
                errorMessage = "Showing cached data. ${e.message}"
            }
        } finally {
            isLoading = false
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
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh events"
                        )
                    }
                },
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { innerPadding ->
        when {
            isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(48.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 4.dp
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Loading events...",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            errorMessage != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.Event,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Oops! Something went wrong",
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = errorMessage!!,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }
            else -> {
                val onEventClick = remember {
                    { link: String ->
                        if (link.isNotEmpty()) {
                            val intent = Intent(Intent.ACTION_VIEW, link.toUri())
                            localContext.startActivity(intent)
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(innerPadding)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    dreamParkEvents.forEach { dreamParkEvent ->
                        EventCard(
                            event = dreamParkEvent,
                            onEventClick = onEventClick,
                            onStarClick = { event ->
                                coroutineScope.launch { toggleEventStar(event) }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EventCard(
    event: DreamParkEvent,
    onEventClick: (String) -> Unit,
    onStarClick: (DreamParkEvent) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEventClick(event.link) },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 4.dp,
            pressedElevation = 8.dp
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            // Title row with star button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
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
                        imageVector = if (event.isStarred) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = if (event.isStarred) "Unstar event" else "Star event",
                        modifier = Modifier.size(28.dp),
                        tint = if (event.isStarred) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
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
                    imageVector = Icons.Default.CalendarMonth,
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
                if (event.link.isNotEmpty()) {
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
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
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
