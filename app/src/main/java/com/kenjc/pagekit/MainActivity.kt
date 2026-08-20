package com.kenjc.pagekit

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.kenjc.pagekit.ui.home.HomeScreen
import com.kenjc.pagekit.ui.theme.PageKitTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // intent 驱动：-d <url> 或 --es url <url>；--ez extract true 在就绪后自动提取（MCP fetch 前身）
        val initialUrl = intent?.data?.toString()?.takeIf { it.isNotBlank() }
            ?: intent?.getStringExtra("url")
        val autoExtract = intent?.let {
            it.getBooleanExtra("extract", false) || it.getStringExtra("extract") == "true"
        } == true
        Log.i("PageKit", "MainActivity: initialUrl=$initialUrl autoExtract=$autoExtract extras=${intent?.extras}")
        setContent {
            PageKitTheme {
                HomeScreen(initialUrl = initialUrl, autoExtract = autoExtract)
            }
        }
    }
}
