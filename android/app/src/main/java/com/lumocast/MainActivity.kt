package com.lumocast

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import com.lumocast.ui.LumoApp
import com.lumocast.ui.ViewerTarget
import com.lumocast.ui.theme.LumoTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val target = parseDeepLink(intent)

        setContent {
            LumoTheme {
                LumoApp(initialViewer = target)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 已在前台时被二维码唤起：重新加载内容以进入观看页
        if (parseDeepLink(intent) != null) recreate()
    }

    /** lumocast://connect?ip=192.168.1.8&port=8737&pin=1234&name=Pixel */
    private fun parseDeepLink(intent: Intent?): ViewerTarget? {
        val uri = intent?.data ?: return null
        if (uri.scheme != "lumocast") return null
        val ip = uri.getQueryParameter("ip") ?: return null
        val port = uri.getQueryParameter("port")?.toIntOrNull() ?: 8737
        val pin = uri.getQueryParameter("pin")
        val name = uri.getQueryParameter("name") ?: "LumoCast"
        return ViewerTarget(ip, port, pin, name)
    }
}
