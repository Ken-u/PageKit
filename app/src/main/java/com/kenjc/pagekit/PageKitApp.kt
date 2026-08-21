package com.kenjc.pagekit

import android.app.Application
import com.kenjc.pagekit.api.DefaultPageKitApi
import com.kenjc.pagekit.engine.adblock.AssetHostsRuleRepository
import com.kenjc.pagekit.engine.adblock.AssetCosmeticRuleRepository
import com.kenjc.pagekit.engine.adblock.CompositeAdBlocker
import com.kenjc.pagekit.engine.adblock.HostsAdBlocker
import com.kenjc.pagekit.engine.adblock.RuleUpdateScheduler
import com.kenjc.pagekit.mcp.PageKitMcpServerController
import com.kenjc.pagekit.mcp.PageKitMcpTools
import com.kenjc.pagekit.mcp.McpAccessPolicy
import com.kenjc.pagekit.mcp.McpTokenStore
import com.kenjc.pagekit.runtime.AndroidPageKitRuntime
import com.kenjc.pagekit.ui.home.HomeViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Application：持有进程级 HomeViewModel，供验证通道 / V2 导出服务访问 */
class PageKitApp : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var hostsRuleRepository: AssetHostsRuleRepository
        private set

    lateinit var cosmeticRuleRepository: AssetCosmeticRuleRepository
        private set

    lateinit var hostsAdBlocker: HostsAdBlocker
        private set

    lateinit var runtime: AndroidPageKitRuntime
        private set

    lateinit var pageKitApi: DefaultPageKitApi
        private set

    lateinit var homeViewModel: HomeViewModel
        private set

    lateinit var mcpServer: PageKitMcpServerController
        private set

    lateinit var mcpTokenStore: McpTokenStore
        private set

    override fun onCreate() {
        super.onCreate()
        hostsRuleRepository = AssetHostsRuleRepository(this).also { it.start(applicationScope) }
        cosmeticRuleRepository = AssetCosmeticRuleRepository(this).also { it.start(applicationScope) }
        RuleUpdateScheduler.schedule(this)
        hostsAdBlocker = HostsAdBlocker(
            repository = hostsRuleRepository,
            initialAllowlist = setOf("localhost", "127.0.0.1"),
        )
        val adBlocker = CompositeAdBlocker(hostsAdBlocker, cosmeticRuleRepository)
        runtime = AndroidPageKitRuntime(this, adBlocker)
        pageKitApi = DefaultPageKitApi(runtime)
        homeViewModel = HomeViewModel(this, runtime, pageKitApi)
        mcpTokenStore = McpTokenStore(this).also { it.token }
        mcpServer = PageKitMcpServerController(
            tools = PageKitMcpTools(pageKitApi),
            accessPolicy = McpAccessPolicy(mcpTokenStore.token),
        )
    }

    override fun onTerminate() {
        mcpServer.stop()
        applicationScope.cancel()
        super.onTerminate()
    }
}
