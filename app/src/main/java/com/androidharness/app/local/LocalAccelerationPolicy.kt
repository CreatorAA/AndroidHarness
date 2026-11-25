package com.androidharness.app.local

import kotlinx.serialization.Serializable

@Serializable
enum class LocalBackend { AUTO, CPU, GPU }

/** Runtime capability decides availability, independent of phone brand or chipset. */
object LocalAccelerationPolicy {
    const val ISSUES_URL = "https://github.com/CreatorAA/AndroidHarness/issues/new?template=device_acceleration.yml"
    fun useGpu(experimental: Boolean, preference: LocalBackend, available: Boolean): Boolean =
        experimental && preference != LocalBackend.CPU && available

    fun retryOnCpu(gpu: Boolean, outputStarted: Boolean, cancelled: Boolean): Boolean =
        gpu && !outputStarted && !cancelled
}
