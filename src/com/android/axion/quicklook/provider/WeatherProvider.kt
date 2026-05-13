/*
 * Copyright (C) 2025 AxionOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.axion.quicklook.provider

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.util.Log
import androidx.core.graphics.drawable.toBitmap
import com.android.internal.util.android.OmniJawsClient
import java.io.ByteArrayOutputStream
import com.android.axion.quicklook.QuickLookAction
import com.android.axion.quicklook.QuickLookTarget
import com.android.axion.quicklook.R
import com.android.axion.quicklook.util.SettingsHelper
import java.text.DecimalFormat
import java.util.concurrent.TimeUnit

class WeatherProvider(context: Context, workerHandler: Handler) :
    QuickLookProvider(context, workerHandler) {

    private var weatherReceiver: BroadcastReceiver? = null
    private var weatherObserver: ContentObserver? = null
    @Volatile private var currentTarget: QuickLookTarget? = null
    private val omniJawsClient = OmniJawsClient.get()

    override val providerType
        get() = QuickLookTarget.TYPE_WEATHER

    override val settingsKey
        get() = SettingsHelper.KEY_WEATHER

    override val priority
        get() = 100

    override fun getTargets(): List<QuickLookTarget> {
        val target = currentTarget
        return if (target == null || !isEnabled) emptyList() else listOf(target)
    }

    override fun start() {
        weatherReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    when (intent.action) {
                        WEATHER_UPDATE -> workerHandler.post(::queryAndUpdate)
                        WEATHER_ERROR -> Log.w(TAG, "Weather error received")
                    }
                }
            }
        val filter =
            IntentFilter().apply {
                addAction(WEATHER_UPDATE)
                addAction(WEATHER_ERROR)
            }
        context.registerReceiver(weatherReceiver, filter, Context.RECEIVER_EXPORTED)

        weatherObserver =
            object : ContentObserver(workerHandler) {
                override fun onChange(selfChange: Boolean) {
                    queryAndUpdate()
                }
            }
        context.contentResolver.registerContentObserver(WEATHER_URI, true, weatherObserver!!)
        context.contentResolver.registerContentObserver(SETTINGS_URI, true, weatherObserver!!)

        workerHandler.post(::queryAndUpdate)
    }

    override fun shutdown() {
        weatherReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (_: Exception) {}
            weatherReceiver = null
        }
        weatherObserver?.let {
            context.contentResolver.unregisterContentObserver(it)
            weatherObserver = null
        }
    }

    private fun queryAndUpdate() {
        if (!isEnabled || !isOmniJawsAvailable()) {
            currentTarget = null
            notifyUpdate()
            return
        }

        var city: String? = null
        var windSpeed: String? = null
        var windDirection: String? = null
        var conditionCode = -1
        var temp: String? = null
        var humidity: String? = null
        var condition: String? = null
        var timeStamp = 0L
        var pinWheel: String? = null

        try {
            context.contentResolver.query(WEATHER_URI, WEATHER_PROJECTION, null, null, null)?.use {
                cursor ->
                if (cursor.moveToFirst()) {
                    city = cursor.getString(0)
                    windSpeed = formatValue(cursor.getFloat(1))
                    windDirection = "${cursor.getInt(2)}\u00b0"
                    conditionCode = cursor.getInt(3)
                    temp = formatValue(cursor.getFloat(4))
                    humidity = cursor.getString(5)
                    condition = cursor.getString(6)
                    timeStamp = cursor.getString(11).toLong()
                    pinWheel = cursor.getString(13)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query weather data", e)
            currentTarget = null
            notifyUpdate()
            return
        }

        if (temp == null || condition == null) {
            currentTarget = null
            notifyUpdate()
            return
        }

        var tempUnit = "\u00b0C"
        var windUnit = "km/h"
        try {
            context.contentResolver
                .query(SETTINGS_URI, SETTINGS_PROJECTION, null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val metric = cursor.getInt(1) == 0
                        tempUnit = if (metric) "\u00b0C" else "\u00b0F"
                        windUnit = if (metric) "km/h" else "mph"
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query weather settings", e)
        }

        val extras =
            Bundle().apply {
                putString(QuickLookTarget.EXTRA_WEATHER_TEMP, temp)
                putString(QuickLookTarget.EXTRA_WEATHER_CONDITION, condition)
                putInt(QuickLookTarget.EXTRA_WEATHER_CONDITION_CODE, conditionCode)
                putString(QuickLookTarget.EXTRA_WEATHER_CITY, city)
                putString(QuickLookTarget.EXTRA_WEATHER_HUMIDITY, humidity)
                putString(QuickLookTarget.EXTRA_WEATHER_WIND, windSpeed)
                putString(QuickLookTarget.EXTRA_WEATHER_WIND_DIRECTION, windDirection)
                putString(QuickLookTarget.EXTRA_WEATHER_TEMP_UNIT, tempUnit)
                putString(QuickLookTarget.EXTRA_WEATHER_WIND_UNIT, windUnit)
                putString(QuickLookTarget.EXTRA_WEATHER_PIN_WHEEL, pinWheel)
                putLong(QuickLookTarget.EXTRA_WEATHER_TIMESTAMP, timeStamp)
            }

        val weatherIntent = context.packageManager.getLaunchIntentForPackage(OMNIJAWS_PACKAGE)
        val action =
            weatherIntent?.let {
                QuickLookAction.Builder("weather_action").setLabel("Weather").setIntent(it).build()
            }

        omniJawsClient.queryWeather(context)
        val iconBytes = loadConditionIconBytes(conditionCode)

        currentTarget =
            QuickLookTarget.Builder("axql_weather", QuickLookTarget.TYPE_WEATHER)
                .setTitle("$temp$tempUnit")
                .setSubtitle(condition)
                .setIconResId(R.drawable.ic_weather_default)
                .setIconBytes(iconBytes)
                .setScore(10.0f)
                .setExpiryTime(System.currentTimeMillis() + TimeUnit.HOURS.toMillis(3))
                .setPrimaryAction(action)
                .setExtras(extras)
                .build()

        notifyUpdate()
    }

    private fun loadConditionIconBytes(conditionCode: Int): ByteArray? {
        return try {
            val drawable = omniJawsClient.getWeatherConditionImage(context, conditionCode)
                ?: return null
            val bitmap: Bitmap = if (drawable is BitmapDrawable) {
                drawable.bitmap
            } else {
                drawable.toBitmap(
                    width = drawable.intrinsicWidth.coerceAtLeast(1),
                    height = drawable.intrinsicHeight.coerceAtLeast(1),
                )
            }
            ByteArrayOutputStream().use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                stream.toByteArray()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load OmniJaws icon", e)
            null
        }
    }

    private fun isOmniJawsAvailable(): Boolean {
        try {
            val pm = context.packageManager
            pm.getPackageInfo(OMNIJAWS_PACKAGE, PackageManager.GET_ACTIVITIES)
            val state = pm.getApplicationEnabledSetting(OMNIJAWS_PACKAGE)
            if (
                state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
                    state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
            )
                return false
        } catch (_: PackageManager.NameNotFoundException) {
            return false
        } catch (_: IllegalArgumentException) {
            return false
        }

        return try {
            context.contentResolver
                .query(SETTINGS_URI, SETTINGS_PROJECTION, null, null, null)
                ?.use { it.moveToFirst() && it.getInt(0) == 1 } ?: false
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private const val TAG = "WeatherProvider"
        private const val OMNIJAWS_PACKAGE = "org.omnirom.omnijaws"
        private val WEATHER_URI = Uri.parse("content://org.omnirom.omnijaws.provider/weather")
        private val SETTINGS_URI = Uri.parse("content://org.omnirom.omnijaws.provider/settings")
        private const val WEATHER_UPDATE = "$OMNIJAWS_PACKAGE.WEATHER_UPDATE"
        private const val WEATHER_ERROR = "$OMNIJAWS_PACKAGE.WEATHER_ERROR"
        private val NO_DIGITS_FORMAT = DecimalFormat("0")

        private val WEATHER_PROJECTION =
            arrayOf(
                "city",
                "wind_speed",
                "wind_direction",
                "condition_code",
                "temperature",
                "humidity",
                "condition",
                "forecast_low",
                "forecast_high",
                "forecast_condition",
                "forecast_condition_code",
                "time_stamp",
                "forecast_date",
                "pin_wheel",
            )

        private val SETTINGS_PROJECTION =
            arrayOf("enabled", "units", "provider", "setup", "icon_pack")

        private fun formatValue(value: Float): String {
            if (value.isNaN()) return "-"
            val result = NO_DIGITS_FORMAT.format(value)
            return if (result == "-0") "0" else result
        }
    }
}
