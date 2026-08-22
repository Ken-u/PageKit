package com.kenjc.pagekit.mcp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.kenjc.pagekit.MainActivity
import com.kenjc.pagekit.PageKitApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/** 用户可见且可停止的 MCP 服务生命周期；绑定地址由 `--es mcp_bind` 决定（默认 0.0.0.0）。 */
class McpServerService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // START_STICKY 被系统拉回时 intent 为 null，回退到上次显式指定的绑定模式。
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (intent?.hasExtra(EXTRA_BIND_MODE) == true) {
            prefs.edit().putString(PREF_BIND_MODE, intent.getStringExtra(EXTRA_BIND_MODE) ?: "").apply()
        }
        val savedMode = prefs.getString(PREF_BIND_MODE, null)
        val bindMode = McpBindMode.parse(intent?.getStringExtra(EXTRA_BIND_MODE) ?: savedMode)
        val bindHost = McpBindHosts.resolve(bindMode ?: McpBindMode.ALL)
        startForeground(NOTIFICATION_ID, notification(bindHost))
        (application as PageKitApp).mcpServer.start(serviceScope, bindHost)
        return START_STICKY
    }

    override fun onDestroy() {
        val app = application as PageKitApp
        app.mcpServer.stop()
        runBlocking { app.sessionGateway.close() }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "PageKit MCP",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps the localhost MCP endpoint available"
            },
        )
    }

    private fun notification(bindHost: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, McpServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("PageKit MCP 正在运行")
            .setContentText("$bindHost:3000/mcp · 需 Bearer token 访问")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "停止", stopIntent).build())
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "pagekit_mcp"
        private const val NOTIFICATION_ID = 3000
        const val ACTION_STOP = "com.kenjc.pagekit.mcp.STOP"

        /** 启动 Service 时指定绑定模式：`all`（默认）/ `lan` / `loopback`，见 [McpBindMode]。 */
        const val EXTRA_BIND_MODE = "mcp_bind"
        private const val PREFS_NAME = "pagekit_mcp"
        private const val PREF_BIND_MODE = "bind_mode"
    }
}
