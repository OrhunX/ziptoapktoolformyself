package com.sen0413.ultimatewidget.playback

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.annotation.AnyRes
import androidx.annotation.DrawableRes
import androidx.annotation.RawRes
import com.sen0413.ultimatewidget.R
import com.sen0413.ultimatewidget.data.LocalTrack

/** Three royalty-free tracks synthesized for this project, so the app works out of the box. */
object DemoLibrary {
    private data class Demo(
        val id: String,
        val title: String,
        val durationMs: Long,
        @RawRes val raw: Int,
        @DrawableRes val cover: Int,
    )

    private val demos = listOf(
        Demo("demo:night_train", "Night Train", 42_000, R.raw.night_train, R.drawable.cover_night_train),
        Demo("demo:blue_hours", "Blue Hours", 47_000, R.raw.blue_hours, R.drawable.cover_blue_hours),
        Demo("demo:last_ferry", "Last Ferry", 44_857, R.raw.last_ferry, R.drawable.cover_last_ferry),
    )

    @DrawableRes
    fun coverFor(mediaId: String?): Int = demos.firstOrNull { it.id == mediaId }?.cover ?: 0

    fun localTracks(context: Context): List<LocalTrack> = demos.map { demo ->
        LocalTrack(
            id = demo.id,
            title = demo.title,
            artist = context.getString(R.string.demo_artist),
            durationMs = demo.durationMs,
            uri = resourceUri(context, demo.raw).toString(),
            coverRes = demo.cover,
            artUri = resourceUri(context, demo.cover).toString(),
        )
    }

    private fun resourceUri(context: Context, @AnyRes resId: Int): Uri {
        val res = context.resources
        return Uri.Builder()
            .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
            .authority(res.getResourcePackageName(resId))
            .appendPath(res.getResourceTypeName(resId))
            .appendPath(res.getResourceEntryName(resId))
            .build()
    }
}
