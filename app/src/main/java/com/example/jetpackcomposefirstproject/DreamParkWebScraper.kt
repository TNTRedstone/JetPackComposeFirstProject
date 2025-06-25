package com.example.jetpackcomposefirstproject

import io.ktor.client.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jsoup.Jsoup
import kotlin.time.Duration.Companion.seconds
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import com.example.jetpackcomposefirstproject.ui.DreamParkEvent

// DataStore for caching last page count
val Context.lastPageDataStore: DataStore<Preferences> by preferencesDataStore(name = "lastPageCache")
val LAST_PAGE_COUNT_KEY = intPreferencesKey("last_page_count")

// Get cached last page count
suspend fun getLastPageCount(context: Context): Int? {
    return context.lastPageDataStore.data.map { preferences ->
        preferences[LAST_PAGE_COUNT_KEY]
    }.firstOrNull()
}

// Save last page count to cache
suspend fun saveLastPageCount(context: Context, pageCount: Int) {
    context.lastPageDataStore.edit { preferences ->
        preferences[LAST_PAGE_COUNT_KEY] = pageCount
    }
}

// Optimized web scraper using cached last page count, then concurrent processing with cached responses
suspend fun scrapeDreamParkEvents(context: Context): List<DreamParkEvent> =
    coroutineScope {
        val totalStartTime = System.currentTimeMillis()


        val events = mutableListOf<DreamParkEvent>()

        // Create a single HTTP client with optimized configuration
        System.currentTimeMillis()
        val client = HttpClient(Android) {
            install(HttpTimeout) {
                requestTimeoutMillis = 30.seconds.inWholeMilliseconds
                connectTimeoutMillis = 15.seconds.inWholeMilliseconds
                socketTimeoutMillis = 30.seconds.inWholeMilliseconds
            }

            // Enable connection pooling and keep-alive
            engine {
                connectTimeout = 15_000
                socketTimeout = 30_000
            }
        }


        try {
            // Step 1: Try to use cached last page count, or fall back to binary search
            val binarySearchStartTime = System.currentTimeMillis()
            val cachedResponses = mutableMapOf<Int, String>()
            val cachedLastPage = getLastPageCount(context)

            val lastPageWithEvents = if (cachedLastPage != null) {
                // Quick verification: check if the cached page still has events
                val verificationStart = System.currentTimeMillis()
                val htmlContent = scrapePageContent(client, cachedLastPage)
                cachedResponses[cachedLastPage] = htmlContent

                if (hasEvents(htmlContent)) {
                    // Check if there's a next page with events (in case site added more)
                    val nextPageContent = scrapePageContent(client, cachedLastPage + 1)
                    cachedResponses[cachedLastPage + 1] = nextPageContent

                    val actualLastPage = if (hasEvents(nextPageContent)) {
                        // There are more pages, need to do a limited binary search from cached page

                        findLastPageWithBinarySearch(client, cachedResponses, cachedLastPage)
                    } else {
                        cachedLastPage
                    }

                    System.currentTimeMillis() - verificationStart

                    actualLastPage
                } else {
                    // Cached page no longer has events, do full binary search

                    findLastPageWithBinarySearch(client, cachedResponses)
                }
            } else {

                findLastPageWithBinarySearch(client, cachedResponses)
            }

            // Save the discovered last page count for next time
            saveLastPageCount(context, lastPageWithEvents)

            System.currentTimeMillis() - binarySearchStartTime


            // Step 2: Concurrently process all pages from 1 to lastPageWithEvents
            val concurrentStartTime = System.currentTimeMillis()
            val semaphore =
                Semaphore(5) // Limit concurrent requests to avoid overwhelming the server

            val allPageEvents = (1..lastPageWithEvents).map { pageIndex ->
                async {
                    semaphore.withPermit {
                        processPageConcurrently(client, pageIndex, cachedResponses)
                    }
                }
            }.awaitAll()
            System.currentTimeMillis() - concurrentStartTime

            // Flatten all events from all pages
            val flattenStartTime = System.currentTimeMillis()
            allPageEvents.forEach { pageEvents ->
                if (pageEvents != null) {
                    events.addAll(pageEvents)
                }
            }
            System.currentTimeMillis() - flattenStartTime

            System.currentTimeMillis() - totalStartTime


        } finally {
            client.close()
        }

        return@coroutineScope events
    }

// Binary search to find the last page with events, caching responses for optimization
private suspend fun findLastPageWithBinarySearch(
    client: HttpClient, cachedResponses: MutableMap<Int, String>, startFrom: Int = 1
): Int {
    var left = startFrom
    var right = if (startFrom > 1) startFrom + 5 else 10 // Start from cached value or default
    var lastValidPage = startFrom

    // First, find an upper bound by doubling until we find a page with no events
    // Skip this phase if we already have responses cached for the range

    var upperBoundRequests = 0

    if (!cachedResponses.containsKey(right)) {
        while (true) {
            try {
                val requestStart = System.currentTimeMillis()

                val htmlContent = scrapePageContent(client, right)
                System.currentTimeMillis() - requestStart
                upperBoundRequests++

                cachedResponses[right] = htmlContent


                if (hasEvents(htmlContent)) {
                    lastValidPage = right
                    left = right
                    right *= 2 // Double the search range
                } else {
                    // Found a page with no events, this is our upper bound

                    break
                }
            } catch (e: Exception) {

                break
            }
        }
    } else {

    }


    // Now perform binary search between left and right

    var binarySearchRequests = 0
    while (left <= right) {
        val mid = (left + right) / 2

        try {
            val requestStart = System.currentTimeMillis()

            val htmlContent = if (cachedResponses.containsKey(mid)) {

                cachedResponses[mid]!!
            } else {
                val content = scrapePageContent(client, mid)
                System.currentTimeMillis() - requestStart
                binarySearchRequests++

                cachedResponses[mid] = content
                content
            }

            if (hasEvents(htmlContent)) {
                lastValidPage = mid
                left = mid + 1

            } else {
                right = mid - 1

            }
        } catch (e: Exception) {

            right = mid - 1
        }
    }



    return lastValidPage
}

// Process a single page concurrently, using cache if available or fetching if needed
private suspend fun processPageConcurrently(
    client: HttpClient, pageIndex: Int, cachedResponses: Map<Int, String>
): List<DreamParkEvent>? {
    return try {
        val pageStartTime = System.currentTimeMillis()


        val fetchStartTime = System.currentTimeMillis()
        val htmlContent = if (cachedResponses.containsKey(pageIndex)) {
            // Use cached response from binary search

            cachedResponses[pageIndex]!!
        } else {
            // Fetch page if not cached

            val content = scrapePageContent(client, pageIndex)
            System.currentTimeMillis() - fetchStartTime

            content
        }

        val parseStartTime = System.currentTimeMillis()
        val pageEvents = parsePage(htmlContent)
        System.currentTimeMillis() - parseStartTime
        System.currentTimeMillis() - pageStartTime


        pageEvents

    } catch (e: Exception) {

        null
    }
}

// Helper function to fetch page content
private suspend fun scrapePageContent(client: HttpClient, pageIndex: Int): String {
    val response = client.get("https://dreamparknj.com/events/list/page/$pageIndex")
    return response.bodyAsText()
}

// Helper function to check if a page has events
private fun hasEvents(htmlContent: String): Boolean {
    val document = Jsoup.parse(htmlContent)

    // Check for end of events message
    val endOfEventsElements = document.select(".tribe-events-c-messages__message--notice")
    if (endOfEventsElements.isNotEmpty()) {
        return false
    }

    // Check if page has event containers
    val eventContainers = document.select(".tribe-events-calendar-list__event")
    return eventContainers.isNotEmpty()
}

// Parse page content and extract events with title, link, and dateOnly
private fun parsePage(htmlContent: String): List<DreamParkEvent> {
    val document = Jsoup.parse(htmlContent)
    val events = mutableListOf<DreamParkEvent>()

    // Get all event containers in one query for better performance
    val eventContainers = document.select(".tribe-events-calendar-list__event")

    for (eventContainer in eventContainers) {
        try {
            val titleElement =
                eventContainer.selectFirst(".tribe-events-calendar-list__event-title")
            val linkElement =
                eventContainer.selectFirst(".tribe-events-calendar-list__event-title-link")
            val dateElement =
                eventContainer.selectFirst(".tribe-events-calendar-list__event-datetime")

            if (titleElement != null && linkElement != null && dateElement != null) {
                val fullDateTime = dateElement.text()
                val dateOnly = fullDateTime.replace(Regex("@.*"), "").trim()

                val event = DreamParkEvent(
                    title = titleElement.text(),
                    link = linkElement.attr("href"),
                    date = dateOnly
                )
                events.add(event)
            }
        } catch (e: Exception) {
            // Skip malformed events but continue processing

        }
    }

    return events
}