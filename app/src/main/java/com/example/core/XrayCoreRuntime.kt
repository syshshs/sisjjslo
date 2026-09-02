package com.example.core

/** Small runtime facade used by the VPN service. */
object XrayCoreRuntime {
    fun start(service: android.net.VpnService, tunFd: Int, rawConfig: String, dns: String): XrayCoreBridge.Result =
        XrayCoreBridge.start(service, tunFd, rawConfig, dns)

    fun stop(): XrayCoreBridge.Result = XrayCoreBridge.stop()
    fun version(): String? = XrayCoreBridge.version()
}
