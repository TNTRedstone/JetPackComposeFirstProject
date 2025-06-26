package com.calvinsteck.equineevents

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.calvinsteck.equineevents.data.DreamParkEvent
import com.calvinsteck.equineevents.data.EventDateParser
import io.ktor.client.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jsoup.Jsoup

val Context.lastPageDataStore: DataStore<Preferences> by
        preferencesDataStore(name = "lastPageCache")
val LAST_PAGE_COUNT_KEY = intPreferencesKey("last_page_count")

suspend fun getLastPageCount(context: Context): Int? =
        context.lastPageDataStore.data.map { it[LAST_PAGE_COUNT_KEY] }.firstOrNull()

suspend fun saveLastPageCount(context: Context, pageCount: Int) {
    context.lastPageDataStore.edit { it[LAST_PAGE_COUNT_KEY] = pageCount }
}

suspend fun scrapeDreamParkEvents(context: Context): List<DreamParkEvent> = coroutineScope {
    val client =
            HttpClient(Android) {
                install(HttpTimeout) {
                    requestTimeoutMillis = 30_000
                    connectTimeoutMillis = 15_000
                }
            }

    try {
        val cachedResponses = mutableMapOf<Int, String>()
        val cachedLastPage = getLastPageCount(context)

        val lastPageWithEvents =
                if (cachedLastPage != null) {
                    val htmlContent = scrapePageContent(client, cachedLastPage)
                    cachedResponses[cachedLastPage] = htmlContent

                    if (hasEvents(htmlContent)) {
                        val nextPageContent = scrapePageContent(client, cachedLastPage + 1)
                        cachedResponses[cachedLastPage + 1] = nextPageContent

                        if (hasEvents(nextPageContent)) {
                            findLastPageWithBinarySearch(client, cachedResponses, cachedLastPage)
                        } else {
                            cachedLastPage
                        }
                    } else {
                        findLastPageWithBinarySearch(client, cachedResponses)
                    }
                } else {
                    findLastPageWithBinarySearch(client, cachedResponses)
                }

        saveLastPageCount(context, lastPageWithEvents)

        val semaphore = Semaphore(5)
        val allPageEvents =
                (1..lastPageWithEvents)
                        .map { pageIndex ->
                            async {
                                semaphore.withPermit {
                                    processPageConcurrently(client, pageIndex, cachedResponses)
                                }
                            }
                        }
                        .awaitAll()

        allPageEvents.filterNotNull().flatten()
    } finally {
        client.close()
    }
}

private suspend fun findLastPageWithBinarySearch(
        client: HttpClient,
        cachedResponses: MutableMap<Int, String>,
        startFrom: Int = 1
): Int {
    var left = startFrom
    var right = if (startFrom > 1) startFrom + 5 else 10
    var lastValidPage = startFrom

    // Find upper bound by doubling
    if (!cachedResponses.containsKey(right)) {
        while (true) {
            val htmlContent = scrapePageContent(client, right)
            cachedResponses[right] = htmlContent

            if (hasEvents(htmlContent)) {
                lastValidPage = right
                left = right
                right *= 2
            } else {
                break
            }
        }
    }

    // Binary search
    while (left <= right) {
        val mid = (left + right) / 2
        val htmlContent =
                cachedResponses[mid]
                        ?: run {
                            val content = scrapePageContent(client, mid)
                            cachedResponses[mid] = content
                            content
                        }

        if (hasEvents(htmlContent)) {
            lastValidPage = mid
            left = mid + 1
        } else {
            right = mid - 1
        }
    }

    return lastValidPage
}

private suspend fun processPageConcurrently(
        client: HttpClient,
        pageIndex: Int,
        cachedResponses: Map<Int, String>
): List<DreamParkEvent>? {
    val htmlContent = cachedResponses[pageIndex] ?: scrapePageContent(client, pageIndex)
    println("[processPageConcurrently] Processing page $pageIndex")
    return parsePage(htmlContent, pageIndex)
}

private suspend fun scrapePageContent(client: HttpClient, pageIndex: Int): String {
    println("[scrapePageContent] Fetching page $pageIndex")
    val response = client.get("https://dreamparknj.com/events/list/page/$pageIndex")
    return response.bodyAsText()
}

private fun hasEvents(htmlContent: String, pageIndex: Int? = null): Boolean {
    val result = hasCurrentYearEvents(htmlContent, pageIndex)
    if (pageIndex != null) {
        println("[hasEvents] Page $pageIndex: hasCurrentYearEvents = $result")
    }
    return result
}

private fun hasCurrentYearEvents(htmlContent: String, pageIndex: Int? = null): Boolean {
    val document = Jsoup.parse(htmlContent)

    if (document.select(".tribe-events-c-messages__message--notice").isNotEmpty()) {
        if (pageIndex != null)
                println("[hasCurrentYearEvents] Page $pageIndex: No events message found")
        return false
    }

    val eventContainers = document.select(".tribe-events-calendar-list__event")
    if (eventContainers.isEmpty()) {
        if (pageIndex != null)
                println("[hasCurrentYearEvents] Page $pageIndex: No event containers found")
        return false
    }

    val calendar = java.util.Calendar.getInstance()
    val currentYear = calendar.get(java.util.Calendar.YEAR)

    val hasCurrentYear =
            eventContainers.any { eventContainer ->
                eventContainer.selectFirst(".tribe-events-calendar-list__event-datetime")?.let {
                        dateElement ->
                    val dateOnly = dateElement.text().replace(Regex("@.*"), "").trim()
                    val (startDate, _) = EventDateParser.parseDateString(dateOnly)
                    calendar.time = startDate
                    calendar.get(java.util.Calendar.YEAR) == currentYear
                } == true
            }
    if (pageIndex != null)
            println("[hasCurrentYearEvents] Page $pageIndex: hasCurrentYear = $hasCurrentYear")
    return hasCurrentYear
}

private fun parsePage(htmlContent: String, pageIndex: Int? = null): List<DreamParkEvent> {
    val document = Jsoup.parse(htmlContent)
    val calendar = java.util.Calendar.getInstance()

    val eventContainers = document.select(".tribe-events-calendar-list__event")
    val eventYears = mutableListOf<Int>()
    val events =
            eventContainers.mapNotNull { eventContainer ->
                val titleElement =
                        eventContainer.selectFirst(".tribe-events-calendar-list__event-title")
                val linkElement =
                        eventContainer.selectFirst(".tribe-events-calendar-list__event-title-link")
                val dateElement =
                        eventContainer.selectFirst(".tribe-events-calendar-list__event-datetime")

                if (titleElement != null && linkElement != null && dateElement != null) {
                    val dateOnly = dateElement.text().replace(Regex("@.*"), "").trim()
                    val (startDate, endDate) = EventDateParser.parseDateString(dateOnly)
                    calendar.time = startDate
                    val year = calendar.get(java.util.Calendar.YEAR)
                    eventYears.add(year)
                    println(
                            "[parsePage] Page $pageIndex: dateOnly='$dateOnly', parsedStartDate='$startDate', parsedYear=$year"
                    )
                    DreamParkEvent(
                            title = titleElement.text(),
                            rawDateString = dateOnly,
                            startDate = startDate,
                            endDate = endDate,
                            link = linkElement.attr("href"),
                            isStarred = false // will be set later
                    )
                } else null
            }
    if (pageIndex != null) println("[parsePage] Page $pageIndex: Event years found: $eventYears")
    return events
}
