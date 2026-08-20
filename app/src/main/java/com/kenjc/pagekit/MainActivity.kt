package com.kenjc.pagekit

import android.content.Intent
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
        handleIntents(intent)
        val initialUrl = intent.initialUrl
        val autoExtract = intent.autoExtract
        Log.i("PageKit", "MainActivity: initialUrl=$initialUrl autoExtract=$autoExtract")
        setContent {
            PageKitTheme {
                HomeScreen(initialUrl = initialUrl, autoExtract = autoExtract)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntents(intent)
    }

    private fun handleIntents(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            // 浏览器控制（singleTask → 主进程内直接操作共享 WebView）
            CONTROL_ACTION -> {
                val op = intent.getStringExtra("op") ?: return
                val eid = intent.getStringExtra("eid")
                val text = intent.getStringExtra("text")
                val dx = intent.getIntExtra("dx", 0)
                val dy = intent.getIntExtra("dy", 600)
                Log.i("PageKit", "CONTROL intent: op=$op eid=$eid text=$text dx=$dx dy=$dy")
                (applicationContext as PageKitApp).homeViewModel.controlOp(op, eid, text, dx, dy)
            }

            else -> Unit
        }
    }

    private val Intent.initialUrl: String?
        get() = data?.toString()?.takeIf { it.isNotBlank() } ?: getStringExtra("url")

    private val Intent.autoExtract: Boolean
        get() = getBooleanExtra("extract", false) || getStringExtra("extract") == "true"

    companion object {
        const val CONTROL_ACTION = "com.kenjc.pagekit.CONTROL"
    }
}
