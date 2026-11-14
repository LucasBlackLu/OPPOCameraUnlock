package com.oppocamera.unlock

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

object Link {

    const val TARGET = "com.oplus.camera"

    var service: XposedService? by mutableStateOf(null)
        private set

    var scope: List<String> by mutableStateOf(emptyList())
        private set

    fun start() {
        runCatching {
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(service: XposedService) {
                    this@Link.service = service
                    scope = runCatching { service.scope.orEmpty() }.getOrDefault(emptyList())
                }

                override fun onServiceDied(service: XposedService) {
                    this@Link.service = null
                    scope = emptyList()
                }
            })
        }
    }
}
