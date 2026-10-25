package com.lumocast.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 一台正在观看的设备（UI 用，避免把 net 层类型泄漏到 Compose）。 */
data class ViewerLite(
    val id: String,
    val name: String,
    val ip: String,
    val rttMs: Int = 0
)

/** 主机会话的全局状态。Service 写，Compose 读。 */
data class HostState(
    val running: Boolean = false,
    val ip: String? = null,
    val port: Int = 0,
    val pin: String = "",
    val pinRequired: Boolean = true,
    val viewers: List<ViewerLite> = emptyList(),
    val fps: Int = 30,
    val bitrateKbps: Int = 0,
    val width: Int = 1080,
    val height: Int = 1920,
    val audio: Boolean = false,
    val error: String? = null
)

object LiveSession {
    private val _host = MutableStateFlow(HostState())
    val host: StateFlow<HostState> = _host.asStateFlow()

    fun setHost(state: HostState) {
        _host.value = state
    }

    fun update(block: (HostState) -> HostState) {
        _host.value = block(_host.value)
    }

    fun reset() {
        _host.value = HostState()
    }
}
