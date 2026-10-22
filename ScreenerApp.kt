package com.example.astockscreener

import android.app.Application

/**
 * 自定义Application，在整个App（包括后台闹钟触发的进程）启动时
 * 把上次保存的设置从SharedPreferences读回ScreenerConfig，
 * 这样无论是MainActivity还是AuctionAlarmReceiver先跑，参数都是一致的。
 */
class ScreenerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ScreenerConfig.load(this)
    }
}
