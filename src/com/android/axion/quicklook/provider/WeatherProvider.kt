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

import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.os.Handler
import android.util.Log
import androidx.core.graphics.drawable.toBitmap
import com.android.axion.quicklook.QuickLookAction
import com.android.axion.quicklook.QuickLookTarget
import com.android.axion.quicklook.R
import com.android.axion.quicklook.util.SettingsHelper
import com.android.internal.util.android.OmniJawsClient
import java.io.ByteArrayOutputStream

class WeatherProvider(context: Context, workerHandler: Handler) :
    QuickLookProvider(context, workerHandler) {

    @Volatile private var currentTarget: QuickLookTarget? = null
    private val omniJawsClient = OmniJawsClient.get()
    private val weatherObserver =
        object : ContentObserver(workerHandler) {
            override fun onChange(selfChange: Boolean) {
                queryAndUpdate()
            }
        }

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
        context.contentResolver.registerContentObserver(
            OmniJawsClient.WEATHER_URI,
            true,
            weatherObserver,
        )
        context.contentResolver.registerContentObserver(
            OmniJawsClient.SETTINGS_URI,
            true,
            weatherObserver,
        )
        workerHandler.post(::queryAndUpdate)
    }

    override fun shutdown() {
        context.contentResolver.unregisterContentObserver(weatherObserver)
    }

    private fun queryAndUpdate() {
        if (!isEnabled) {
            currentTarget = null
            notifyUpdate()
            return
        }

        omniJawsClient.queryWeather(context)
        val weatherInfo = omniJawsClient.getWeatherInfo()
        if (weatherInfo == null || weatherInfo.temp == null || weatherInfo.condition == null) {
            currentTarget = null
            notifyUpdate()
            return
        }

        val extras =
            Bundle().apply {
                putString(QuickLookTarget.EXTRA_WEATHER_TEMP, weatherInfo.temp)
                putString(QuickLookTarget.EXTRA_WEATHER_CONDITION, weatherInfo.condition)
                putInt(QuickLookTarget.EXTRA_WEATHER_CONDITION_CODE, weatherInfo.conditionCode)
                putString(QuickLookTarget.EXTRA_WEATHER_CITY, weatherInfo.city)
                putString(QuickLookTarget.EXTRA_WEATHER_HUMIDITY, weatherInfo.humidity)
                putString(QuickLookTarget.EXTRA_WEATHER_WIND, weatherInfo.windSpeed)
                putString(QuickLookTarget.EXTRA_WEATHER_WIND_DIRECTION, weatherInfo.windDirection)
                putString(
                    QuickLookTarget.EXTRA_WEATHER_TEMP_UNIT,
                    weatherInfo.tempUnits ?: "\u00b0C",
                )
                putString(
                    QuickLookTarget.EXTRA_WEATHER_WIND_UNIT,
                    weatherInfo.windUnits ?: "km/h",
                )
                putString(QuickLookTarget.EXTRA_WEATHER_PIN_WHEEL, weatherInfo.pinWheel)
                putLong(QuickLookTarget.EXTRA_WEATHER_TIMESTAMP, weatherInfo.timeStamp ?: 0L)
            }

        val weatherIntent =
            context.packageManager.getLaunchIntentForPackage(OmniJawsClient.SERVICE_PACKAGE)
        val action =
            weatherIntent?.let {
                QuickLookAction.Builder("weather_action").setLabel("Weather").setIntent(it).build()
            }

        val iconBytes = loadConditionIconBytes(weatherInfo.conditionCode)

        currentTarget =
            QuickLookTarget.Builder("axql_weather", QuickLookTarget.TYPE_WEATHER)
                .setTitle("${weatherInfo.temp}${weatherInfo.tempUnits ?: "\u00b0C"}")
                .setSubtitle(weatherInfo.condition)
                .setIconResId(R.drawable.ic_weather_default)
                .setIconBytes(iconBytes)
                .setScore(10.0f)
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

    companion object {
        private const val TAG = "WeatherProvider"
    }
}
