package com.example.core

import android.net.VpnService
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Reflection-based adapter for the official XTLS/libXray Android AAR.
 *
 * Keeping the adapter reflective lets the project source compile before the
 * native AAR is generated. At runtime the app fails closed with a useful
 * message if libXray.aar is missing.
 */
object XrayCoreBridge {
    private const val TAG = "XrayCoreBridge"
    private val classCandidates = listOf("libXray.LibXray", "libxray.LibXray")

    @Volatile private var apiClass: Class<*>? = null

    data class Result(val success: Boolean, val error: String? = null, val raw: String? = null)

    private fun findApiClass(): Class<*> {
        apiClass?.let { return it }
        val found = classCandidates.firstNotNullOfOrNull { name ->
            try { Class.forName(name) } catch (_: Throwable) { null }
        } ?: throw IllegalStateException(
            "libXray.aar پیدا نشد. ابتدا scripts/build-libxray-aar.sh را اجرا کنید."
        )
        apiClass = found
        return found
    }

    private fun staticMethod(name: String, vararg parameterTypes: Class<*>): Method? {
        val clazz = findApiClass()
        return clazz.methods.firstOrNull { it.name.equals(name, ignoreCase = true) &&
            it.parameterTypes.contentEquals(parameterTypes) }
    }

    private fun invoke(request: JSONObject): JSONObject {
        val method = findApiClass().methods.firstOrNull {
            it.name.equals("invoke", ignoreCase = true) &&
                it.parameterTypes.size == 1 && it.parameterTypes[0] == String::class.java
        } ?: throw IllegalStateException("libXray Invoke API پیدا نشد")
        val raw = method.invoke(null, request.toString())?.toString()
            ?: throw IllegalStateException("libXray پاسخ خالی برگرداند")
        return JSONObject(raw)
    }

    fun start(vpnService: VpnService, tunFd: Int, rawConfig: String, dns: String): Result {
        return try {
            registerSocketProtection(vpnService)
            setDns(vpnService, dns)

            val xrayJson = if (rawConfig.trimStart().startsWith("{")) {
                rawConfig
            } else {
                val converted = invoke(JSONObject()
                    .put("apiVersion", 1)
                    .put("method", "convertShareLinksToXrayJson")
                    .put("payload", JSONObject().put("text", rawConfig)))
                if (!converted.optBoolean("success", false)) {
                    return Result(false, converted.optString("error", "تبدیل کانفیگ به Xray JSON ناموفق بود"))
                }
                extractXrayJson(converted)
            }

            val runtimeConfig = addTunInbound(xrayJson, tunFd)
            val response = invoke(JSONObject()
                .put("apiVersion", 1)
                .put("method", "runXray")
                .put("payload", JSONObject().put("xrayJson", runtimeConfig)))

            if (!response.optBoolean("success", false)) {
                resetDns()
                Result(false, response.optString("error", "Xray-core نتوانست اجرا شود"), response.toString())
            } else {
                Result(true, raw = response.toString())
            }
        } catch (t: Throwable) {
            resetDns()
            Log.e(TAG, "start failed", t)
            Result(false, t.cause?.message ?: t.message ?: "خطای ناشناخته در Xray-core")
        }
    }

    fun stop(): Result {
        return try {
            val response = invoke(JSONObject()
                .put("apiVersion", 1)
                .put("method", "stopXray")
                .put("payload", JSONObject()))
            resetDns()
            if (response.optBoolean("success", false)) Result(true, raw = response.toString())
            else Result(false, response.optString("error", "توقف Xray-core ناموفق بود"), response.toString())
        } catch (t: Throwable) {
            resetDns()
            Result(false, t.cause?.message ?: t.message ?: "خطا در توقف Xray-core")
        }
    }

    fun version(): String? {
        return try {
            val response = invoke(JSONObject()
                .put("apiVersion", 1)
                .put("method", "xrayVersion")
                .put("payload", JSONObject()))
            response.optJSONObject("data")?.optString("version")
        } catch (_: Throwable) { null }
    }

    private fun extractXrayJson(response: JSONObject): String {
        val data = response.opt("data")
        if (data is JSONObject) {
            val direct = data.optString("xrayJson", "")
            if (direct.isNotBlank()) return direct
            val config = data.optString("config", "")
            if (config.isNotBlank()) return config
        }
        if (data is String && data.trimStart().startsWith("{")) return data
        throw IllegalStateException("خروجی تبدیل libXray شامل Xray JSON معتبر نبود")
    }

    private fun addTunInbound(configText: String, tunFd: Int): String {
        val root = JSONObject(configText)
        val inbounds = JSONArray()
        inbounds.put(JSONObject()
            .put("port", 0)
            .put("protocol", "tun")
            .put("settings", JSONObject()
                .put("name", "jumpvpn0")
                .put("MTU", 1500)))
        root.put("inbounds", inbounds)

        val env = root.optJSONObject("env") ?: JSONObject().also { root.put("env", it) }
        env.put("xray.tun.fd", tunFd.toString())
        return root.toString()
    }

    private fun registerSocketProtection(service: VpnService) {
        val clazz = findApiClass()
        val controllerMethod = clazz.methods.firstOrNull {
            it.name.equals("registerDialerController", ignoreCase = true) && it.parameterTypes.size == 1
        } ?: return
        val interfaceType = controllerMethod.parameterTypes[0]
        val proxy = Proxy.newProxyInstance(interfaceType.classLoader, arrayOf(interfaceType)) { _, method, args ->
            if (method.name.equals("protectFd", ignoreCase = true)) {
                val fd = (args?.firstOrNull() as? Number)?.toInt() ?: -1
                return@newProxyInstance service.protect(fd)
            }
            when (method.name) {
                "toString" -> "VpnDialerController"
                "hashCode" -> System.identityHashCode(service)
                "equals" -> args?.firstOrNull() === service
                else -> defaultValue(method.returnType)
            }
        }
        controllerMethod.invoke(null, proxy)

        clazz.methods.firstOrNull {
            it.name.equals("registerListenerController", ignoreCase = true) && it.parameterTypes.size == 1
        }?.let { it.invoke(null, proxy) }
    }

    private fun setDns(service: VpnService, dns: String) {
        val clazz = findApiClass()
        val method = clazz.methods.firstOrNull {
            it.name.equals("setDNS", ignoreCase = true) && it.parameterTypes.size == 2
        } ?: return
        val interfaceType = method.parameterTypes[0]
        val proxy = Proxy.newProxyInstance(interfaceType.classLoader, arrayOf(interfaceType)) { _, m, args ->
            if (m.name.equals("protectFd", ignoreCase = true)) {
                val fd = (args?.firstOrNull() as? Number)?.toInt() ?: -1
                return@newProxyInstance service.protect(fd)
            }
            defaultValue(m.returnType)
        }
        method.invoke(null, proxy, normalizeDnsEndpoint(dns))
    }

    private fun resetDns() {
        try {
            staticMethod("resetDNS")?.invoke(null)
        } catch (t: Throwable) {
            Log.w(TAG, "resetDNS failed: ${t.message}")
        }
    }

    private fun normalizeDnsEndpoint(value: String): String {
        val clean = value.trim()
        return if (clean.contains(":")) clean else "$clean:53"
    }

    private fun defaultValue(type: Class<*>): Any? = when {
        type == Boolean::class.javaPrimitiveType -> false
        type == Byte::class.javaPrimitiveType -> 0.toByte()
        type == Short::class.javaPrimitiveType -> 0.toShort()
        type == Int::class.javaPrimitiveType -> 0
        type == Long::class.javaPrimitiveType -> 0L
        type == Float::class.javaPrimitiveType -> 0f
        type == Double::class.javaPrimitiveType -> 0.0
        else -> null
    }
}
