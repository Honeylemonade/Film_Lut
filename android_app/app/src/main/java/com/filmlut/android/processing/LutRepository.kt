package com.filmlut.android.processing

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

object LutRepository {
    private val cache = ConcurrentHashMap<String, CubeLut>()

    fun get(context: Context, assetPath: String): CubeLut {
        cache[assetPath]?.let { return it }
        synchronized(cache) {
            cache[assetPath]?.let { return it }
            return CubeLutParser.parse(context.assets, assetPath).also { parsed ->
                cache[assetPath] = parsed
            }
        }
    }

    fun warm(context: Context, assetPaths: Collection<String>) {
        assetPaths.forEach { assetPath ->
            get(context, assetPath)
        }
    }
}
