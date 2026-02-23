package com.accelerate.videoproc

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

/**
 * Monitors thermal and power state of the device as proxy indicators.
 *
 * Uses battery temperature (available on all Android devices) as a thermal proxy,
 * and battery level + charging status as a power proxy.
 *
 * On Android Q+ (API 29), also reads the thermal status from PowerManager.
 */
class ThermalMonitor(private val context: Context) {

    data class ThermalState(
        val batteryTempCelsius: Float,    // Battery temperature in °C
        val batteryPercent: Int,           // Battery level 0-100%
        val isCharging: Boolean,           // Whether device is plugged in
        val thermalStatus: String,         // Thermal throttling status
        val thermalStatusLevel: Int        // 0=nominal, 1=light, 2=moderate, 3=severe, 4=critical
    )

    /**
     * Read current thermal and power state.
     * This is safe to call from any thread.
     */
    fun readState(): ThermalState {
        // Read battery info via sticky broadcast (no receiver registration needed)
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        val tempRaw = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val tempCelsius = tempRaw / 10.0f  // Battery temp is in tenths of °C

        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val batteryPercent = if (scale > 0) (level * 100) / scale else 0

        val plugged = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val isCharging = plugged != 0

        // Thermal status (API 29+)
        var thermalStatusStr = "NOMINAL"
        var thermalLevel = 0

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                val status = powerManager.currentThermalStatus
                when (status) {
                    PowerManager.THERMAL_STATUS_NONE -> {
                        thermalStatusStr = "NOMINAL"
                        thermalLevel = 0
                    }
                    PowerManager.THERMAL_STATUS_LIGHT -> {
                        thermalStatusStr = "LIGHT"
                        thermalLevel = 1
                    }
                    PowerManager.THERMAL_STATUS_MODERATE -> {
                        thermalStatusStr = "MODERATE"
                        thermalLevel = 2
                    }
                    PowerManager.THERMAL_STATUS_SEVERE -> {
                        thermalStatusStr = "SEVERE"
                        thermalLevel = 3
                    }
                    PowerManager.THERMAL_STATUS_CRITICAL -> {
                        thermalStatusStr = "CRITICAL"
                        thermalLevel = 4
                    }
                    PowerManager.THERMAL_STATUS_EMERGENCY -> {
                        thermalStatusStr = "EMERGENCY"
                        thermalLevel = 5
                    }
                    PowerManager.THERMAL_STATUS_SHUTDOWN -> {
                        thermalStatusStr = "SHUTDOWN"
                        thermalLevel = 6
                    }
                }
            } catch (e: Exception) {
                thermalStatusStr = "N/A"
            }
        } else {
            // Pre-Q: estimate from battery temp
            thermalStatusStr = when {
                tempCelsius < 35 -> "NOMINAL"
                tempCelsius < 40 -> "WARM"
                tempCelsius < 45 -> "HOT"
                else -> "CRITICAL"
            }
            thermalLevel = when {
                tempCelsius < 35 -> 0
                tempCelsius < 40 -> 1
                tempCelsius < 45 -> 2
                else -> 3
            }
        }

        return ThermalState(tempCelsius, batteryPercent, isCharging, thermalStatusStr, thermalLevel)
    }
}
