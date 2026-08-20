package com.kenjc.pagekit

import android.app.Application
import com.kenjc.pagekit.ui.home.HomeViewModel

/** Application：持有进程级 HomeViewModel，供验证通道 / V2 导出服务访问 */
class PageKitApp : Application() {

    lateinit var homeViewModel: HomeViewModel
        private set

    override fun onCreate() {
        super.onCreate()
        homeViewModel = HomeViewModel(this)
    }
}
