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
import com.kenjc.pagekit.compress.FilePageExpansionCache
import com.kenjc.pagekit.compress.OpenAiCompatibleCompressor
import com.kenjc.pagekit.compress.SharedPreferencesLlmSettings
import com.kenjc.pagekit.runtime.AndroidPageKitRuntime
import com.kenjc.pagekit.ui.home.HomeViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Application：装配进程级浏览器、API、MCP、压缩器与规则仓库。 */
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

    lateinit var llmSettings: SharedPreferencesLlmSettings
        private set

    override fun onCreate() {
        super.onCreate()
        hostsRuleRepository = AssetHostsRuleRepository(this).also { it.start(applicationScope) }
        cosmeticRuleRepository = AssetCosmeticRuleRepository(this).also { it.start(applicationScope) }
        RuleUpdateScheduler.schedule(this)
        hostsAdBlocker = HostsAdBlocker(
            repository = hostsRuleRepository,
            initialAllowlist = setOf("localhost", "127.0.0.1", "::1"),
        )
        val adBlocker = CompositeAdBlocker(hostsAdBlocker, cosmeticRuleRepository)
        llmSettings = SharedPreferencesLlmSettings(this)
        val pageCache = FilePageExpansionCache(this)
        runtime = AndroidPageKitRuntime(
            context = this,
            adBlocker = adBlocker,
            compressor = OpenAiCompatibleCompressor(llmSettings),
            pageCache = pageCache,
        )
        pageKitApi = DefaultPageKitApi(runtime)
        homeViewModel = HomeViewModel(this, runtime, pageKitApi, llmSettings)
        mcpTokenStore = McpTokenStore(this).also { it.token }
        mcpServer = PageKitMcpServerController(
            tools = PageKitMcpTools(pageKitApi, llmSettings),
            accessPolicy = McpAccessPolicy(mcpTokenStore.token),
        )
    }

    override fun onTerminate() {
        mcpServer.stop()
        applicationScope.cancel()
        super.onTerminate()
    }
}
