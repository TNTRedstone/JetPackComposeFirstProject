package com.example.jetpackcomposefirstproject.data

// Data class for better performance and type safety
data class DreamParkEvent(
    val title: String,
    val date: String,
    val link: String
) {
    // Generate a stable key for LazyColumn using hashCode for better performance
    val key: String by lazy { "${title.hashCode()}_${date.hashCode()}" }
}
