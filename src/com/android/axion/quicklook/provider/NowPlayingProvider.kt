/*
 * Copyright (C) 2025-2026 AxionOS
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
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.text.TextUtils
import android.util.Log
import com.android.axion.platform.AxPlatformClient
import com.android.axion.quicklook.QuickLookAction
import com.android.axion.quicklook.QuickLookTarget
import com.android.axion.quicklook.R
import com.android.axion.quicklook.util.SettingsHelper
import java.util.concurrent.Executor

class NowPlayingProvider(context: Context, workerHandler: Handler) :
    QuickLookProvider(context, workerHandler) {

    @Volatile private var currentTarget: QuickLookTarget? = null
    private val hideRunnable = Runnable {
        currentTarget = null
        notifyUpdate()
    }

    private val platformExecutor = Executor { command -> workerHandler.post(command) }
    private val callback =
        AxPlatformClient.StateCallback { key, state ->
            if (key == AxPlatformClient.KEY_NOW_PLAYING) {
                handleData(state.getString("action", ""), state.toBundle())
            }
        }

    override val providerType
        get() = QuickLookTarget.TYPE_NOW_PLAYING

    override val settingsKey
        get() = SettingsHelper.KEY_NOW_PLAYING

    override val priority
        get() = 225

    override fun getTargets(): List<QuickLookTarget> {
        val target = currentTarget
        return if (target == null || !isEnabled) emptyList() else listOf(target)
    }

    override fun start() {
        Log.d(TAG, "start: isEnabled=$isEnabled")
        val client = AxPlatformClient.getInstance()
        client.init(context)
        client.registerCallback(platformExecutor, callback)
    }

    override fun shutdown() {
        workerHandler.removeCallbacks(hideRunnable)
        AxPlatformClient.getInstance().unregisterCallback(callback)
    }

    private fun handleData(action: String, data: Bundle) {
        Log.d(TAG, "handleData: action=$action")
        when (action) {
            ACTION_SHOW -> handleShow(data)
            ACTION_EXPAND -> handleExpand(data)
            ACTION_HIDE -> handleHide()
        }
    }

    private fun handleShow(data: Bundle) {
        if (!isEnabled) return

        val version = data.getInt(EXTRA_VERSION, 0)
        if (version != 1) {
            Log.w(TAG, "Unsupported ambient indication version: $version")
            return
        }

        val text = data.getCharSequence(EXTRA_TEXT)
        val songTitle = data.getCharSequence(EXTRA_SONG_TITLE)
        val artistName = data.getCharSequence(EXTRA_ARTIST_NAME)

        if (TextUtils.isEmpty(text) && TextUtils.isEmpty(songTitle)) return

        val ttlMillis =
            data.getLong(EXTRA_TTL_MILLIS, DEFAULT_TTL_MILLIS).coerceIn(0, MAX_TTL_MILLIS)
        val openIntent = data.getParcelable(EXTRA_OPEN_INTENT, PendingIntent::class.java)
        val favoritingIntent =
            data.getParcelable(EXTRA_FAVORITING_INTENT, PendingIntent::class.java)
        val skipUnlock = data.getBoolean(EXTRA_SKIP_UNLOCK, false)
        val iconOverride = data.getInt(EXTRA_ICON_OVERRIDE, 0)
        val iconDescription = data.getString(EXTRA_ICON_DESCRIPTION)
        val useExtended = data.getBoolean(EXTRA_USE_EXTENDED, false)

        val title = songTitle?.toString() ?: text?.toString() ?: return
        val subtitle = artistName?.toString()

        val extras =
            Bundle().apply {
                putString(QuickLookTarget.EXTRA_NOW_PLAYING_TITLE, songTitle?.toString())
                putString(QuickLookTarget.EXTRA_NOW_PLAYING_ARTIST, artistName?.toString())
                putBoolean("np_skip_unlock", skipUnlock)
                putInt(QuickLookTarget.EXTRA_NOW_PLAYING_ICON_OVERRIDE, iconOverride)
                iconDescription?.let {
                    putString(QuickLookTarget.EXTRA_NOW_PLAYING_ICON_DESCRIPTION, it)
                }
                favoritingIntent?.let {
                    putParcelable(QuickLookTarget.EXTRA_NOW_PLAYING_FAVORITING_INTENT, it)
                }
                if (useExtended) {
                    putBoolean(QuickLookTarget.EXTRA_NOW_PLAYING_IS_RECOGNITION, true)
                    data.getParcelable(EXTRA_EXPAND_INTENT, PendingIntent::class.java)?.let {
                        putParcelable(QuickLookTarget.EXTRA_NOW_PLAYING_EXPAND_INTENT, it)
                    }
                }
            }

        val primaryAction =
            openIntent?.let {
                QuickLookAction.Builder("now_playing_action")
                    .setLabel("Open")
                    .setPendingIntent(it)
                    .build()
            }

        currentTarget =
            QuickLookTarget.Builder("axql_now_playing", QuickLookTarget.TYPE_NOW_PLAYING)
                .setTitle(title)
                .setSubtitle(subtitle)
                .setIconResId(R.drawable.ic_music_note)
                .setScore(0.6f)
                .setPrimaryAction(primaryAction)
                .setExtras(extras)
                .build()

        notifyUpdate()

        workerHandler.removeCallbacks(hideRunnable)
        workerHandler.postDelayed(hideRunnable, ttlMillis)
    }

    private fun handleExpand(data: Bundle) {
        if (!isEnabled) return

        val text = data.getCharSequence(EXTRA_TEXT)
        val songTitle = data.getCharSequence(EXTRA_SONG_TITLE)
        val artistName = data.getCharSequence(EXTRA_ARTIST_NAME)

        if (TextUtils.isEmpty(text) && TextUtils.isEmpty(songTitle)) return

        val ttlMillis =
            data.getLong(EXTRA_TTL_MILLIS, DEFAULT_TTL_MILLIS).coerceIn(0, MAX_TTL_MILLIS)
        val openIntent = data.getParcelable(EXTRA_OPEN_INTENT, PendingIntent::class.java)
        val favoritingIntent =
            data.getParcelable(EXTRA_FAVORITING_INTENT, PendingIntent::class.java)
        val albumArtUri = data.getString(EXTRA_ALBUM_ART_URI)
        val dmpIntent = data.getParcelable(EXTRA_DMP_INTENT, PendingIntent::class.java)
        val dmpPackageName = data.getString(EXTRA_DMP_PACKAGE_NAME)
        val isFavorite = data.getBoolean(EXTRA_IS_FAVORITE, false)

        val title = songTitle?.toString() ?: text?.toString() ?: return
        val subtitle = artistName?.toString()

        val extras =
            Bundle().apply {
                putString(QuickLookTarget.EXTRA_NOW_PLAYING_TITLE, songTitle?.toString())
                putString(QuickLookTarget.EXTRA_NOW_PLAYING_ARTIST, artistName?.toString())
                albumArtUri?.let { putString(QuickLookTarget.EXTRA_NOW_PLAYING_ALBUM_ART_URI, it) }
                putBoolean(QuickLookTarget.EXTRA_NOW_PLAYING_IS_RECOGNITION, true)
                putBoolean(QuickLookTarget.EXTRA_NOW_PLAYING_IS_FAVORITE, isFavorite)
                favoritingIntent?.let {
                    putParcelable(QuickLookTarget.EXTRA_NOW_PLAYING_FAVORITING_INTENT, it)
                }
                dmpIntent?.let {
                    putParcelable(QuickLookTarget.EXTRA_NOW_PLAYING_DMP_INTENT, it)
                }
                dmpPackageName?.let {
                    putString(QuickLookTarget.EXTRA_NOW_PLAYING_DMP_PACKAGE, it)
                }
            }

        val primaryAction =
            openIntent?.let {
                QuickLookAction.Builder("now_playing_action")
                    .setLabel("Open")
                    .setPendingIntent(it)
                    .build()
            }

        currentTarget =
            QuickLookTarget.Builder("axql_now_playing", QuickLookTarget.TYPE_NOW_PLAYING)
                .setTitle(title)
                .setSubtitle(subtitle)
                .setIconResId(R.drawable.ic_music_note)
                .setScore(0.6f)
                .setPrimaryAction(primaryAction)
                .setExtras(extras)
                .build()

        notifyUpdate()

        workerHandler.removeCallbacks(hideRunnable)
        workerHandler.postDelayed(hideRunnable, ttlMillis)
    }

    private fun handleHide() {
        workerHandler.removeCallbacks(hideRunnable)
        currentTarget = null
        notifyUpdate()
    }

    companion object {
        private const val TAG = "NowPlayingProvider"

        private const val ACTION_SHOW =
            "com.google.android.ambientindication.action.AMBIENT_INDICATION_SHOW"
        private const val ACTION_HIDE =
            "com.google.android.ambientindication.action.AMBIENT_INDICATION_HIDE"
        private const val ACTION_EXPAND =
            "com.google.android.ambientindication.action.AMBIENT_INDICATION_EXPAND"

        private const val EXTRA_VERSION =
            "com.google.android.ambientindication.extra.VERSION"
        private const val EXTRA_TEXT =
            "com.google.android.ambientindication.extra.TEXT"
        private const val EXTRA_SONG_TITLE =
            "com.google.android.ambientindication.extra.SONG_TITLE"
        private const val EXTRA_ARTIST_NAME =
            "com.google.android.ambientindication.extra.ARTIST_NAME"
        private const val EXTRA_TTL_MILLIS =
            "com.google.android.ambientindication.extra.TTL_MILLIS"
        private const val EXTRA_OPEN_INTENT =
            "com.google.android.ambientindication.extra.OPEN_INTENT"
        private const val EXTRA_FAVORITING_INTENT =
            "com.google.android.ambientindication.extra.FAVORITING_INTENT"
        private const val EXTRA_SKIP_UNLOCK =
            "com.google.android.ambientindication.extra.SKIP_UNLOCK"
        private const val EXTRA_ICON_OVERRIDE =
            "com.google.android.ambientindication.extra.ICON_OVERRIDE"
        private const val EXTRA_ICON_DESCRIPTION =
            "com.google.android.ambientindication.extra.ICON_DESCRIPTION"
        private const val EXTRA_USE_EXTENDED =
            "com.google.android.ambientindication.extra.USE_EXTENDED_INTERACTION"
        private const val EXTRA_EXPAND_INTENT =
            "com.google.android.ambientindication.extra.EXPAND_INTENT"
        private const val EXTRA_ALBUM_ART_URI =
            "com.google.android.ambientindication.extra.ALBUM_ART_URI"
        private const val EXTRA_DMP_INTENT =
            "com.google.android.ambientindication.extra.DMP_INTENT"
        private const val EXTRA_DMP_PACKAGE_NAME =
            "com.google.android.ambientindication.extra.DMP_PACKAGE_NAME"
        private const val EXTRA_IS_FAVORITE =
            "com.google.android.ambientindication.extra.IS_FAVORITE"

        private const val DEFAULT_TTL_MILLIS = 180_000L
        private const val MAX_TTL_MILLIS = 180_000L
    }
}
