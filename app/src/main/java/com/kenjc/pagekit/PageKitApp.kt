package com.kenjc.pagekit

import android.app.Application
import com.kenjc.pagekit.api.DefaultPageKitApi
import com.kenjc.pagekit.runtime.AndroidPageKitRuntime
import com.kenjc.pagekit.ui.home.HomeViewModel

/** Application：持有进程级 HomeViewModel，供验证通道 / V2 导出服务访问 */
class PageKitApp : Application() {

    lateinit var runtime: AndroidPageKitRuntime
        private set

    lateinit var pageKitApi: DefaultPageKitApi
        private set

    lateinit var homeViewModel: HomeViewModel
        private set

    override fun onCreate() {
        super.onCreate()
        runtime = AndroidPageKitRuntime(this)
        pageKitApi = DefaultPageKitApi(runtime)
        homeViewModel = HomeViewModel(this, runtime, pageKitApi)
    }
}
