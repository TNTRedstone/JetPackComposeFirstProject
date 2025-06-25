package com.example.jetpackcomposefirstproject

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.jetpackcomposefirstproject.ui.ListViewScreen

object AppDestinations {
    const val LISTVIEW = "list view"
}

class MainActivity : ComponentActivity() {
    lateinit var scheme: ColorScheme

    @RequiresApi(Build.VERSION_CODES.S)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(applicationContext) else dynamicLightColorScheme(applicationContext)

            MaterialTheme(
                colorScheme = scheme
            ) {
                Controller(this)
            }
        }
    }
}

@Composable
fun Controller(context: MainActivity) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = AppDestinations.LISTVIEW,
        modifier = Modifier.fillMaxSize()
    ) {
        composable(AppDestinations.LISTVIEW) {
            ListViewScreen(navController, context)
        }
    }
}
