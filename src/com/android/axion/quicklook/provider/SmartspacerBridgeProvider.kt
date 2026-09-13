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

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.text.TextUtils
import android.util.Log
import com.android.axion.quicklook.QuickLookAction
import com.android.axion.quicklook.QuickLookTarget
import com.android.axion.quicklook.util.SettingsHelper
import com.google.geo.sidekick.SmartspaceProto.SmartspaceUpdate
import com.google.geo.sidekick.SmartspaceProto.SmartspaceUpdate.SmartspaceCard

class SmartspacerBridgeProvider(context: Context, workerHandler: Handler) :
    QuickLookProvider(context, workerHandler) {

    private var packageReceiver: BroadcastReceiver? = null
    @Volatile private var smartspacerInstalled = false
    @Volatile private var smartspacerTargets: List<QuickLookTarget> = emptyList()

    override val providerType
        get() = QuickLookTarget.TYPE_SMARTSPACER

    override val settingsKey
        get() = SettingsHelper.KEY_SMARTSPACER

    override val priority
        get() = 500

    override fun getTargets(): List<QuickLookTarget> =
        if (!isEnabled || !smartspacerInstalled) emptyList() else ArrayList(smartspacerTargets)

    override fun start() {

        instance = this

        packageReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    val pkg = intent.data?.schemeSpecificPart ?: return
                    if (SMARTSPACER_PACKAGE == pkg) {
                        workerHandler.post(::checkSmartspacerAvailability)
                    }
                }
            }
        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            }
        context.registerReceiver(packageReceiver, filter, null, workerHandler)

        workerHandler.post(::checkSmartspacerAvailability)
    }

    override fun shutdown() {
        instance = null
        packageReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (_: Exception) {}
            packageReceiver = null
        }
    }

    private fun checkSmartspacerAvailability() {
        try {
            context.packageManager.getPackageInfo(SMARTSPACER_PACKAGE, 0)
            smartspacerInstalled = true
        } catch (_: PackageManager.NameNotFoundException) {
            smartspacerInstalled = false
            smartspacerTargets = emptyList()
            notifyUpdate()
        }
    }

    internal fun handleSmartspaceUpdate(cardBytes: ByteArray) {
        if (!smartspacerInstalled || !isEnabled) return

        workerHandler.post {
            try {
                val update = SmartspaceUpdate.parseFrom(cardBytes)
                val targets =
                    update.cardList.mapNotNull { card ->
                        try {
                            convertSmartspaceCard(card)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to convert SmartspaceCard", e)
                            null
                        }
                    }
                smartspacerTargets = targets
                notifyUpdate()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse SmartspaceUpdate proto", e)
            }
        }
    }

    private fun convertSmartspaceCard(card: SmartspaceCard): QuickLookTarget? {
        if (card.shouldDiscard) return null

        val message =
            when {
                card.hasDuringEvent() -> card.duringEvent
                card.hasPreEvent() -> card.preEvent
                card.hasPostEvent() -> card.postEvent
                card.hasDuringEventStatic() -> card.duringEventStatic
                card.hasPreEventStatic() -> card.preEventStatic
                card.hasPostEventStatic() -> card.postEventStatic
                else -> return null
            }

        var title = if (message.hasTitle() && message.title.hasText()) message.title.text else null
        if (title.isNullOrEmpty() && message.hasTitle() && message.title.formatParamCount > 0) {
            val param = message.title.getFormatParam(0)
            if (param.hasText()) {
                title = param.text
            }
        }

        var subtitle = if (message.hasSubtitle() && message.subtitle.hasText()) message.subtitle.text else null
        if (subtitle.isNullOrEmpty() && message.hasSubtitle() && message.subtitle.formatParamCount > 0) {
            val param = message.subtitle.getFormatParam(0)
            if (param.hasText()) {
                subtitle = param.text
            }
        }

        if (TextUtils.isEmpty(title) && TextUtils.isEmpty(subtitle)) return null

        val id = "axql_smartspacer_${card.cardId}"

        val extras =
            Bundle().apply {
                putInt(
                    QuickLookTarget.EXTRA_SMARTSPACE_FEATURE_TYPE,
                    if (card.hasCardType()) card.cardType.number else 0,
                )
                putBoolean(QuickLookTarget.EXTRA_SMARTSPACE_SENSITIVE, card.isSensitive)
                if (card.hasIcon() && card.icon.hasUri()) {
                    putString(EXTRA_ICON_URI, card.icon.uri)
                }
                if (card.hasTapAction() && card.tapAction.hasIntent()) {
                    putString(EXTRA_TAP_INTENT, card.tapAction.intent)
                }
            }

        val score =
            when {
                card.hasCardPriority() &&
                    card.cardPriority == SmartspaceCard.CardPriority.PRIMARY -> 0.8f
                else -> 0.5f
            }

        val action =
            if (card.hasTapAction() && card.tapAction.hasIntent()) {
                try {
                    val intent = Intent.parseUri(card.tapAction.intent, 0)
                    val pi = PendingIntent.getActivity(
                        context, card.cardId, intent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    QuickLookAction.Builder("smartspacer_action_${card.cardId}")
                        .setPendingIntent(pi)
                        .setIntent(intent)
                        .build()
                } catch (_: Exception) {
                    null
                }
            } else null

        return QuickLookTarget.Builder(id, QuickLookTarget.TYPE_SMARTSPACER)
            .setTitle(title)
            .setSubtitle(subtitle)
            .setScore(score * 0.5f)
            .setPrimaryAction(action)
            .setExtras(extras)
            .apply {
                if (card.hasExpiryCriteria() && card.expiryCriteria.hasExpirationTimeMillis()) {
                    setExpiryTime(card.expiryCriteria.expirationTimeMillis)
                }
            }
            .build()
    }

    companion object {
        private const val TAG = "SmartspacerBridge"
        const val SMARTSPACER_PACKAGE = "com.kieronquinn.app.smartspacer"

        private const val EXTRA_ICON_URI = "smartspacer_icon_uri"
        private const val EXTRA_TAP_INTENT = "smartspacer_tap_intent"

        @Volatile private var instance: SmartspacerBridgeProvider? = null

        fun onSmartspaceUpdate(cardBytes: ByteArray) {
            instance?.handleSmartspaceUpdate(cardBytes)
                ?: Log.w(TAG, "Received smartspace update but provider not active")
        }
    }
}
