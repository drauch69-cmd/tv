package com.streamlink.tv

import android.content.Context

/** The user's "Live now" channel words, kept on the TV (set from the phone page). */
object LiveWords {
    private const val KEY = "live_words"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("streamlink", Context.MODE_PRIVATE)

    /** Saved words, or the defaults when the user never changed them. A saved empty list stays empty. */
    fun get(ctx: Context): List<String> {
        val raw = prefs(ctx).getString(KEY, null) ?: return ChannelFilter.DEFAULT_WORDS
        return ChannelFilter.parseWords(raw)
    }

    fun set(ctx: Context, words: List<String>) {
        prefs(ctx).edit().putString(KEY, ChannelFilter.joinWords(ChannelFilter.parseWords(ChannelFilter.joinWords(words)))).apply()
    }

    fun reset(ctx: Context) { prefs(ctx).edit().remove(KEY).apply() }
}
