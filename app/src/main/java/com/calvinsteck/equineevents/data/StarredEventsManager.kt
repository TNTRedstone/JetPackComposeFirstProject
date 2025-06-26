package com.calvinsteck.equineevents.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map

// DataStore for starred events
val Context.starredEventsDataStore: DataStore<Preferences> by preferencesDataStore(name = "starredEvents")
val STARRED_EVENTS_KEY = stringSetPreferencesKey("starred_event_ids")

class StarredEventsManager(private val context: Context) {
    
    // Get all starred event IDs
    suspend fun getStarredEventIds(): Set<String> {
        return context.starredEventsDataStore.data
            .map { preferences ->
                preferences[STARRED_EVENTS_KEY] ?: emptySet()
            }
            .firstOrNull() ?: emptySet()
    }
    
    // Add an event to starred list
    suspend fun starEvent(eventId: String) {
        context.starredEventsDataStore.edit { preferences ->
            val currentStarred = preferences[STARRED_EVENTS_KEY] ?: emptySet()
            preferences[STARRED_EVENTS_KEY] = currentStarred + eventId
        }
    }
    
    // Remove an event from starred list
    suspend fun unstarEvent(eventId: String) {
        context.starredEventsDataStore.edit { preferences ->
            val currentStarred = preferences[STARRED_EVENTS_KEY] ?: emptySet()
            preferences[STARRED_EVENTS_KEY] = currentStarred - eventId
        }
    }
    
    // Toggle starred status of an event
    suspend fun toggleEventStar(eventId: String): Boolean {
        val currentStarred = getStarredEventIds()
        return if (eventId in currentStarred) {
            unstarEvent(eventId)
            false // Now unstarred
        } else {
            starEvent(eventId)
            true // Now starred
        }
    }
    
    // Apply starred status to a list of events
    suspend fun applyStarredStatus(events: List<DreamParkEvent>): List<DreamParkEvent> {
        val starredIds = getStarredEventIds()
        return events.map { event ->
            event.copy(isStarred = event.uniqueId in starredIds)
        }
    }
}
