package com.kenjc.pagekit

import android.app.Application
import com.kenjc.pagekit.api.DefaultPageKitApi
import com.kenjc.pagekit.engine.adblock.AssetHostsRuleRepository
import com.kenjc.pagekit.engine.adblock.HostsAdBlocker
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

    lateinit var adBlocker: HostsAdBlocker
        private set

    lateinit var runtime: AndroidPageKitRuntime
        private set

    lateinit var pageKitApi: DefaultPageKitApi
        private set

    lateinit var homeViewModel: HomeViewModel
        private set

    override fun onCreate() {
        super.onCreate()
        hostsRuleRepository = AssetHostsRuleRepository(this).also { it.start(applicationScope) }
        adBlocker = HostsAdBlocker(
            repository = hostsRuleRepository,
            initialAllowlist = setOf("localhost", "127.0.0.1"),
        )
        runtime = AndroidPageKitRuntime(this, adBlocker)
        pageKitApi = DefaultPageKitApi(runtime)
        homeViewModel = HomeViewModel(this, runtime, pageKitApi)
    }

    override fun onTerminate() {
        applicationScope.cancel()
        super.onTerminate()
    }
}
