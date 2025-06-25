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

// DataStore for caching last page count
val Context.lastPageDataStore: DataStore<Preferences> by preferencesDataStore(name = "lastPageCache")
val LAST_PAGE_COUNT_KEY = intPreferencesKey("last_page_count")

// Get cached last page count
suspend fun getLastPageCount(context: Context): Int? {
    return context.lastPageDataStore.data
        .map { preferences ->
            preferences[LAST_PAGE_COUNT_KEY]
        }
        .firstOrNull()
}

// Save last page count to cache
suspend fun saveLastPageCount(context: Context, pageCount: Int) {
    context.lastPageDataStore.edit { preferences ->
        preferences[LAST_PAGE_COUNT_KEY] = pageCount
    }
}

// Optimized web scraper using cached last page count, then concurrent processing with cached responses
suspend fun scrapeDreamParkEvents(context: Context): MutableList<Map<String, String>> = coroutineScope {
    val totalStartTime = System.currentTimeMillis()
    println("🚀 Starting web scraping process...")

    val events = mutableListOf<Map<String, String>>()

    // Create a single HTTP client with optimized configuration
    val clientStartTime = System.currentTimeMillis()
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
    println("⚡ HTTP client created in ${System.currentTimeMillis() - clientStartTime}ms")

    try {
        // Step 1: Try to use cached last page count, or fall back to binary search
        val binarySearchStartTime = System.currentTimeMillis()
        val cachedResponses = mutableMapOf<Int, String>()
        val cachedLastPage = getLastPageCount(context)

        val lastPageWithEvents = if (cachedLastPage != null) {
            println("💾 Found cached last page count: $cachedLastPage")
            println("🔍 Verifying cached page count is still valid...")

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
                    println("📈 Site has more pages than cached, doing limited search...")
                    findLastPageWithBinarySearch(client, cachedResponses, cachedLastPage)
                } else {
                    cachedLastPage
                }

                val verificationTime = System.currentTimeMillis() - verificationStart
                println("✅ Cache verification completed in ${verificationTime}ms")
                actualLastPage
            } else {
                // Cached page no longer has events, do full binary search
                println("❌ Cached page no longer valid, doing full binary search...")
                findLastPageWithBinarySearch(client, cachedResponses)
            }
        } else {
            println("🔍 No cached page count found, doing full binary search...")
            findLastPageWithBinarySearch(client, cachedResponses)
        }

        // Save the discovered last page count for next time
        saveLastPageCount(context, lastPageWithEvents)

        val binarySearchTime = System.currentTimeMillis() - binarySearchStartTime

        println("🔍 Binary search completed in ${binarySearchTime}ms")
        println("📄 Found last page with events: $lastPageWithEvents")
        println("💾 Cached ${cachedResponses.size} responses during binary search")

        // Step 2: Concurrently process all pages from 1 to lastPageWithEvents
        val concurrentStartTime = System.currentTimeMillis()
        val semaphore = Semaphore(5) // Limit concurrent requests to avoid overwhelming the server

        val allPageEvents = (1..lastPageWithEvents).map { pageIndex ->
            async {
                semaphore.withPermit {
                    processPageConcurrently(client, pageIndex, cachedResponses)
                }
            }
        }.awaitAll()
        val concurrentTime = System.currentTimeMillis() - concurrentStartTime

        // Flatten all events from all pages
        val flattenStartTime = System.currentTimeMillis()
        allPageEvents.forEach { pageEvents ->
            if (pageEvents != null) {
                events.addAll(pageEvents)
            }
        }
        val flattenTime = System.currentTimeMillis() - flattenStartTime

        val totalTime = System.currentTimeMillis() - totalStartTime
        println("⚡ Concurrent processing completed in ${concurrentTime}ms")
        println("📋 Event flattening completed in ${flattenTime}ms")
        println("🎉 Total events found: ${events.size}")
        println("⏱️ TOTAL SCRAPING TIME: ${totalTime}ms (${totalTime/1000.0}s)")
        println("📊 Performance breakdown:")
        println("   - Binary search: ${binarySearchTime}ms (${(binarySearchTime.toDouble()/totalTime*100).toInt()}%)")
        println("   - Concurrent processing: ${concurrentTime}ms (${(concurrentTime.toDouble()/totalTime*100).toInt()}%)")
        println("   - Other operations: ${totalTime - binarySearchTime - concurrentTime}ms")

    } finally {
        client.close()
    }

    return@coroutineScope events
}

// Binary search to find the last page with events, caching responses for optimization
private suspend fun findLastPageWithBinarySearch(
    client: HttpClient,
    cachedResponses: MutableMap<Int, String>,
    startFrom: Int = 1
): Int {
    var left = startFrom
    var right = if (startFrom > 1) startFrom + 5 else 10 // Start from cached value or default
    var lastValidPage = startFrom

    // First, find an upper bound by doubling until we find a page with no events
    // Skip this phase if we already have responses cached for the range
    println("🔍 Phase 1: Finding upper bound...")
    var upperBoundRequests = 0

    if (!cachedResponses.containsKey(right)) {
        while (true) {
            try {
                val requestStart = System.currentTimeMillis()
                println("Checking page $right for upper bound...")
                val htmlContent = scrapePageContent(client, right)
                val requestTime = System.currentTimeMillis() - requestStart
                upperBoundRequests++

                cachedResponses[right] = htmlContent
                println("   ⏱️ Page $right fetched in ${requestTime}ms")

                if (hasEvents(htmlContent)) {
                    lastValidPage = right
                    left = right
                    right *= 2 // Double the search range
                } else {
                    // Found a page with no events, this is our upper bound
                    println("   ✅ Found upper bound at page $right (no events)")
                    break
                }
            } catch (e: Exception) {
                println("   ❌ Error checking page $right: ${e.message}")
                break
            }
        }
    } else {
        println("   💾 Using cached responses, skipping upper bound search")
    }
    println("🔍 Phase 1 complete: Made $upperBoundRequests requests to find upper bound")

    // Now perform binary search between left and right
    println("🔍 Phase 2: Binary search between pages $left and $right...")
    var binarySearchRequests = 0
    while (left <= right) {
        val mid = (left + right) / 2

        try {
            val requestStart = System.currentTimeMillis()
            println("Binary search: checking page $mid (range: $left-$right)")
            val htmlContent = if (cachedResponses.containsKey(mid)) {
                println("   💾 Using cached response for page $mid")
                cachedResponses[mid]!!
            } else {
                val content = scrapePageContent(client, mid)
                val requestTime = System.currentTimeMillis() - requestStart
                binarySearchRequests++
                println("   ⏱️ Page $mid fetched in ${requestTime}ms")
                cachedResponses[mid] = content
                content
            }

            if (hasEvents(htmlContent)) {
                lastValidPage = mid
                left = mid + 1
                println("   ✅ Page $mid has events, searching higher")
            } else {
                right = mid - 1
                println("   ❌ Page $mid has no events, searching lower")
            }
        } catch (e: Exception) {
            println("   ❌ Error during binary search at page $mid: ${e.message}")
            right = mid - 1
        }
    }

    println("🔍 Phase 2 complete: Made $binarySearchRequests additional requests during binary search")
    println("🎯 Final result: Last page with events is $lastValidPage")
    return lastValidPage
}

// Process a single page concurrently, using cache if available or fetching if needed
private suspend fun processPageConcurrently(
    client: HttpClient,
    pageIndex: Int,
    cachedResponses: Map<Int, String>
): List<Map<String, String>>? {
    return try {
        val pageStartTime = System.currentTimeMillis()
        println("🔄 Processing page $pageIndex...")

        val fetchStartTime = System.currentTimeMillis()
        val htmlContent = if (cachedResponses.containsKey(pageIndex)) {
            // Use cached response from binary search
            println("   💾 Using cached response for page $pageIndex")
            cachedResponses[pageIndex]!!
        } else {
            // Fetch page if not cached
            println("   🌐 Fetching page $pageIndex...")
            val content = scrapePageContent(client, pageIndex)
            val fetchTime = System.currentTimeMillis() - fetchStartTime
            println("   ⏱️ Page $pageIndex fetched in ${fetchTime}ms")
            content
        }

        val parseStartTime = System.currentTimeMillis()
        val pageEvents = parsePage(htmlContent)
        val parseTime = System.currentTimeMillis() - parseStartTime
        val totalPageTime = System.currentTimeMillis() - pageStartTime

        println("   ✅ Page $pageIndex: ${pageEvents.size} events found (parse: ${parseTime}ms, total: ${totalPageTime}ms)")
        pageEvents

    } catch (e: Exception) {
        println("   ❌ Error processing page $pageIndex: ${e.message}")
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
private fun parsePage(htmlContent: String): List<Map<String, String>> {
    val document = Jsoup.parse(htmlContent)
    val events = mutableListOf<Map<String, String>>()

    // Get all event containers in one query for better performance
    val eventContainers = document.select(".tribe-events-calendar-list__event")

    for (eventContainer in eventContainers) {
        try {
            val titleElement = eventContainer.selectFirst(".tribe-events-calendar-list__event-title")
            val linkElement = eventContainer.selectFirst(".tribe-events-calendar-list__event-title-link")
            val dateElement = eventContainer.selectFirst(".tribe-events-calendar-list__event-datetime")

            if (titleElement != null && linkElement != null && dateElement != null) {
                val fullDateTime = dateElement.text()
                val dateOnly = fullDateTime.replace(Regex("@.*"), "").trim()

                val event = mapOf(
                    "title" to titleElement.text(),
                    "link" to linkElement.attr("href"),
                    "dateOnly" to dateOnly
                )
                events.add(event)
            }
        } catch (e: Exception) {
            // Skip malformed events but continue processing
            println("Error parsing event: ${e.message}")
        }
    }

    return events
}