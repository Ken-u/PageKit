package com.kenjc.pagekit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.kenjc.pagekit.ui.home.HomeScreen
import com.kenjc.pagekit.ui.theme.PageKitTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PageKitTheme {
                HomeScreen()
            }
        }
    }
}
