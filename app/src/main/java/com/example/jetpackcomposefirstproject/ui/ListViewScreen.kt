package com.example.jetpackcomposefirstproject.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.jetpackcomposefirstproject.MainActivity
import com.example.jetpackcomposefirstproject.scrapeDreamParkEvents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import java.io.StringReader

// Data class for better performance and type safety
data class DreamParkEvent(
    val title: String,
    val date: String,
    val link: String
) {
    // Generate a stable key for LazyColumn using hashCode for better performance
    val key: String by lazy { "${title.hashCode()}_${date.hashCode()}" }
}

suspend fun getLastRefreshTimeString(context: Context): String? {
    return context.dataStore.data
        .map { preferences ->
            preferences[LAST_REFRESH_TIME_KEY]
        }
        .firstOrNull()
}

suspend fun getLastScrapeResult(context: Context): List<DreamParkEvent>? {
    val jsonString = context.dreamParkDataStore.data
        .map { preferences ->
            preferences[LAST_SCRAPE_RESULT_KEY]
        }
        .firstOrNull()

    return jsonString?.let {
        try {
            val parser = Parser.default()
            val stringBuilder = StringBuilder(it)
            val json = parser.parse(stringBuilder) as? JsonArray<*>

            json?.mapNotNull { item ->
                (item as? JsonObject)?.let { jsonObject ->
                    val eventMap = jsonObject.mapValues { entry -> entry.value.toString() }
                    DreamParkEvent(
                        title = eventMap["title"] ?: "Unknown Event",
                        date = eventMap["dateOnly"] ?: "Date TBD",
                        link = eventMap["link"] ?: ""
                    )
                }
            }
        } catch (e: Exception) {
            null
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
    val localContext = LocalContext.current
    var dreamParkEvents by remember { mutableStateOf<List<DreamParkEvent>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) } // Start as true
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var lastRefreshTime by remember { mutableStateOf<String?>(null) }
    var refreshTrigger by remember { mutableStateOf(0) }

    fun forceRefresh() {
        refreshTrigger++
    }

    LaunchedEffect(refreshTrigger) { // Triggers on initial load and manual refresh
        isLoading = true
        errorMessage = null // Reset error message at the start of a load

        try {
            val lastRefreshTimeStr = getLastRefreshTimeString(localContext)
            lastRefreshTime = lastRefreshTimeStr

            val needsFreshScrape: Boolean
            val lastRefreshMillis = lastRefreshTimeStr?.toLongOrNull()
            // Using a shorter duration for easier testing of refresh logic if needed, e.g., 1 minute
            // val oneWeekInMillis = 1 * 60 * 1000L // 1 minute for testing
            val oneWeekInMillis = 7 * 24 * 60 * 60 * 1000L // 7 days

            // Force fresh scrape if this is a manual refresh (refreshTrigger > 0)
            if (refreshTrigger > 0) {
                needsFreshScrape = true
            } else if (lastRefreshMillis == null) {
                needsFreshScrape = true
            } else {
                val timeSinceLastRefresh = System.currentTimeMillis() - lastRefreshMillis
                needsFreshScrape = timeSinceLastRefresh >= oneWeekInMillis
            }

            var loadedFromCache = false // Reset this flag
            val cachedScrapeResult = getLastScrapeResult(localContext) // Uses Klaxon

            if (needsFreshScrape || cachedScrapeResult == null) { // Modified condition: always scrape if cache is null
                try {
                    println("🔄 UI: Starting fresh scrape...")
                    val scrapeStartTime = System.currentTimeMillis()

                    val scrapedEvents: List<DreamParkEvent> = withContext(Dispatchers.IO) {
                        scrapeDreamParkEvents(localContext)
                    }
                    val scrapeTime = System.currentTimeMillis() - scrapeStartTime
                    println("✅ UI: Scraping completed in ${scrapeTime}ms")

                    dreamParkEvents = scrapedEvents
                    errorMessage = null // Clear error if scrape is successful

                    val cacheStartTime = System.currentTimeMillis()
                    withContext(Dispatchers.IO) {
                        val currentTime = System.currentTimeMillis().toString()
                        localContext.dataStore.edit { preferences ->
                            preferences[LAST_REFRESH_TIME_KEY] = currentTime
                        }

                        println("💾 UI: Starting JSON serialization of ${scrapedEvents.size} events...")
                        val jsonStartTime = System.currentTimeMillis()
                        // Convert back to map format for JSON serialization
                        val eventsForJson = scrapedEvents.map { event ->
                            mapOf(
                                "title" to event.title,
                                "dateOnly" to event.date,
                                "link" to event.link
                            )
                        }
                        val jsonScrapedEvents = Klaxon().toJsonString(eventsForJson)
                        val jsonTime = System.currentTimeMillis() - jsonStartTime
                        println("📄 UI: JSON serialization completed in ${jsonTime}ms")

                        localContext.dreamParkDataStore.edit { preferences ->
                            preferences[LAST_SCRAPE_RESULT_KEY] = jsonScrapedEvents
                        }
                        lastRefreshTime = currentTime // Update UI state for last refresh time
                    }
                    val cacheTime = System.currentTimeMillis() - cacheStartTime
                    println("💾 UI: Caching completed in ${cacheTime}ms")

                    val totalRefreshTime = System.currentTimeMillis() - scrapeStartTime
                    println("🎉 UI: TOTAL REFRESH TIME: ${totalRefreshTime}ms (${totalRefreshTime/1000.0}s)")
                    println("📊 UI Performance breakdown:")
                    println("   - Web scraping: ${scrapeTime}ms (${(scrapeTime.toDouble()/totalRefreshTime*100).toInt()}%)")
                    println("   - Caching (JSON + DataStore): ${cacheTime}ms (${(cacheTime.toDouble()/totalRefreshTime*100).toInt()}%)")
                } catch (e: Exception) {
                    errorMessage = "Failed to load fresh events: ${e.message}"
                    if (cachedScrapeResult != null) {
                        dreamParkEvents = cachedScrapeResult
                        loadedFromCache = true
                        errorMessage = "Showing older data. Failed to refresh: ${e.message}"
                    } else {
                        errorMessage = "Failed to load events and no cached data available: ${e.message}"
                        dreamParkEvents = emptyList() // Ensure events list is empty
                    }
                }
            } else { // This else means: needsFreshScrape is false AND cachedScrapeResult is NOT null
                dreamParkEvents = cachedScrapeResult
                loadedFromCache = true
                // Optionally clear error if successfully loaded from cache
                // errorMessage = null
            }
        } catch (e: Exception) {
            // This is a global catch block for any unhandled exceptions in the LaunchedEffect's try block.
            errorMessage = "An critical error occurred: ${e.message}"
            if (dreamParkEvents.isEmpty()) { // If events are still empty, try to load from cache one last time
                val fallbackCache = getLastScrapeResult(localContext)
                if (fallbackCache != null) {
                    dreamParkEvents = fallbackCache
                    errorMessage += " Displaying cached data if possible."
                } else {
                    dreamParkEvents = emptyList()
                }
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
                        text = "Dream Park Events",
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
                // Memoize the click handler to prevent unnecessary recompositions
                val onEventClick = remember {
                    { link: String ->
                        if (link.isNotEmpty()) {
                            val intent = Intent(Intent.ACTION_VIEW, link.toUri())
                            localContext.startActivity(intent)
                        }
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(
                        items = dreamParkEvents,
                        key = { event -> event.key },
                        contentType = { "event_card" }
                    ) { dreamParkEvent ->
                        EventCard(
                            event = dreamParkEvent,
                            onEventClick = onEventClick
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
    onEventClick: (String) -> Unit
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = Icons.Default.Event,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = event.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
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
                            text = event.date,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (event.link.isNotEmpty()) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = "Open event link",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}