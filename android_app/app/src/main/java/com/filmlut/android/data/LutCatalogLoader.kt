package com.filmlut.android.data

import android.content.Context
import com.filmlut.android.model.LutSpec
import org.json.JSONObject

object LutCatalogLoader {
    fun load(context: Context): List<LutSpec> {
        val raw = context.assets.open("favorite_luts/index.json").bufferedReader().use { it.readText() }
        val array = JSONObject(raw).getJSONArray("luts")
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    LutSpec(
                        id = item.getString("id"),
                        name = item.getString("name").replace('_', ' '),
                        category = item.getString("category").replace('_', ' '),
                        lutAssetPath = item.getString("lutAssetPath"),
                        thumbnailAssetPath = item.optString("thumbnailAssetPath").takeIf { it.isNotBlank() },
                        starred = item.optBoolean("starred", true),
                    )
                )
            }
        }
    }
}
