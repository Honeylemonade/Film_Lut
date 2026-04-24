package com.filmlut.android.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.collection.LruCache

object AssetBitmapCache {
    private val cache = LruCache<String, Bitmap>(24)

    fun get(context: Context, assetPath: String?): Bitmap? {
        if (assetPath.isNullOrBlank()) return null
        cache.get(assetPath)?.let { return it }
        return runCatching {
            context.assets.open(assetPath).use { input ->
                BitmapFactory.decodeStream(input)?.also { cache.put(assetPath, it) }
            }
        }.getOrNull()
    }
}
