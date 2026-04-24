package com.filmlut.android.processing

import android.content.res.AssetManager

object CubeLutParser {
    fun parse(assetManager: AssetManager, assetPath: String): CubeLut {
        assetManager.open(assetPath).bufferedReader().use { reader ->
            var size = 0
            val values = ArrayList<Float>(32 * 32 * 32 * 3)

            reader.lineSequence().forEach { rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEach
                when {
                    line.startsWith("TITLE") -> Unit
                    line.startsWith("DOMAIN_MIN") -> Unit
                    line.startsWith("DOMAIN_MAX") -> Unit
                    line.startsWith("LUT_3D_SIZE") -> {
                        size = line.substringAfter("LUT_3D_SIZE").trim().toInt()
                    }
                    else -> {
                        appendFirstThreeFloats(line, values)
                    }
                }
            }

            check(size > 1) { "Invalid LUT size for $assetPath" }
            check(values.size == size * size * size * 3) {
                "Unexpected LUT payload for $assetPath. size=$size values=${values.size}"
            }
            return CubeLut(size = size, values = values.toFloatArray())
        }
    }

    private fun appendFirstThreeFloats(line: String, values: MutableList<Float>) {
        var index = 0
        var parsed = 0
        while (index < line.length && parsed < 3) {
            while (index < line.length && line[index].isWhitespace()) index += 1
            val start = index
            while (index < line.length && !line[index].isWhitespace()) index += 1
            if (start < index) {
                values += line.substring(start, index).toFloat()
                parsed += 1
            }
        }
    }
}
