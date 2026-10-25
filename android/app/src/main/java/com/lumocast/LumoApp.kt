package com.lumocast

import android.app.Application

class LumoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 不做任何事：没有统计 SDK、没有崩溃上报、没有初始化网络栈。
        // 这一点是产品承诺的一部分（见 docs/PRIVACY.md）。
    }
}
