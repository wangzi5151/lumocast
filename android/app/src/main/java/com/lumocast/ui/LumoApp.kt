package com.lumocast.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.lumocast.core.license.License
import com.lumocast.data.Prefs
import com.lumocast.ui.components.GradientBackdrop

/** 深链 / 扫码解析出来的观看目标。 */
data class ViewerTarget(
    val ip: String,
    val port: Int,
    val pin: String? = null,
    val name: String = "LumoCast"
)

@Composable
fun LumoApp(initialViewer: ViewerTarget? = null) {
    val nav = rememberNavController()
    val context = LocalContext.current
    val prefs = remember { Prefs.get(context) }

    var licenseToken by remember { mutableStateOf(prefs.licenseToken) }
    var viewerTarget by remember { mutableStateOf(initialViewer) }

    // 每次页面切换重新读一次设置里的 license（用户在设置页激活后立即生效）
    val entry by nav.currentBackStackEntryAsState()
    LaunchedEffect(entry) { licenseToken = prefs.licenseToken }

    val entitlement = remember(licenseToken) { License.verify(licenseToken, context) }
    val hostState by com.lumocast.data.LiveSession.host.collectAsState()

    GradientBackdrop {
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            NavHost(
                navController = nav,
                startDestination = if (initialViewer != null) "viewer" else "home"
            ) {
                composable("home") {
                    HomeScreen(
                        onOpenHost = { nav.navigate("host") { launchSingleTop = true } },
                        onOpenViewer = { ip, port, pin, name ->
                            viewerTarget = ViewerTarget(ip, port, pin, name)
                            nav.navigate("viewer") { launchSingleTop = true }
                        },
                        onOpenSettings = { nav.navigate("settings") { launchSingleTop = true } },
                        onOpenPro = { nav.navigate("pro") { launchSingleTop = true } },
                        isPro = entitlement.active
                    )
                }

                composable("host") {
                    HostScreen(onBack = { nav.popBackStack() })
                }

                composable("viewer") {
                    val target = viewerTarget
                    if (target != null) {
                        ViewerScreen(
                            ip = target.ip,
                            port = target.port,
                            pin = target.pin,
                            name = target.name,
                            licenseToken = licenseToken,
                            onBack = {
                                viewerTarget = null
                                nav.popBackStack()
                                if (hostState.running.not()) {
                                    // 从深链直接进来时没有上一页，回到首页
                                    nav.navigate("home") { popUpTo("home") { inclusive = true } }
                                }
                            }
                        )
                    }
                }

                composable("settings") {
                    SettingsScreen(
                        onBack = { nav.popBackStack() },
                        onOpenPro = { nav.navigate("pro") { launchSingleTop = true } }
                    )
                }

                composable("pro") {
                    PaywallScreen(
                        onBack = { nav.popBackStack() },
                        onOpenSettings = { nav.navigate("settings") { launchSingleTop = true } }
                    )
                }
            }
        }
    }
}
