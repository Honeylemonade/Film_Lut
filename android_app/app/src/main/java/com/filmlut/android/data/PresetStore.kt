package com.filmlut.android.data

import android.content.Context
import com.filmlut.android.model.FilmAdjustments
import com.filmlut.android.model.FilmPreset
import org.json.JSONArray
import org.json.JSONObject

class PresetStore(context: Context) {
    private val prefs = context.getSharedPreferences("film_lut_presets", Context.MODE_PRIVATE)

    fun load(): List<FilmPreset> {
        val raw = prefs.getString("presets_json", "[]") ?: "[]"
        val array = JSONArray(raw)
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    FilmPreset(
                        name = item.getString("name"),
                        lutId = item.getString("lutId"),
                        createdAt = item.optLong("createdAt", System.currentTimeMillis()),
                        adjustments = item.getJSONObject("adjustments").toAdjustments(),
                    )
                )
            }
        }
    }

    fun saveAll(presets: List<FilmPreset>) {
        val array = JSONArray()
        presets.forEach { preset ->
            array.put(
                JSONObject().apply {
                    put("name", preset.name)
                    put("lutId", preset.lutId)
                    put("createdAt", preset.createdAt)
                    put("adjustments", preset.adjustments.toJson())
                }
            )
        }
        prefs.edit().putString("presets_json", array.toString()).apply()
    }

    private fun FilmAdjustments.toJson(): JSONObject {
        return JSONObject().apply {
            put("lutIntensity", lutIntensity)
            put("grain", grain)
            put("dispersion", dispersion)
            put("vignette", vignette)
            put("sharpen", sharpen)
            put("clarity", clarity)
            put("highlightRolloff", highlightRolloff)
            put("halation", halation)
            put("bloom", bloom)
            put("shadowLift", shadowLift)
            put("toe", toe)
            put("shoulder", shoulder)
            put("highlightSaturation", highlightSaturation)
            put("shadowSaturation", shadowSaturation)
            put("highlightWarmth", highlightWarmth)
            put("shadowCoolness", shadowCoolness)
        }
    }

    private fun JSONObject.toAdjustments(): FilmAdjustments {
        return FilmAdjustments(
            lutIntensity = optInt("lutIntensity", 100),
            grain = optInt("grain", 38),
            dispersion = optInt("dispersion", 8),
            vignette = optInt("vignette", 18),
            sharpen = optInt("sharpen", -10),
            clarity = optInt("clarity", -8),
            highlightRolloff = optInt("highlightRolloff", 35),
            halation = optInt("halation", 22),
            bloom = optInt("bloom", 18),
            shadowLift = optInt("shadowLift", 16),
            toe = optInt("toe", 28),
            shoulder = optInt("shoulder", 42),
            highlightSaturation = optInt("highlightSaturation", -18),
            shadowSaturation = optInt("shadowSaturation", -10),
            highlightWarmth = optInt("highlightWarmth", 8),
            shadowCoolness = optInt("shadowCoolness", 6),
        )
    }
}
