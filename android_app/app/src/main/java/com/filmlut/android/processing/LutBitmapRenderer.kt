package com.filmlut.android.processing

import android.graphics.Bitmap
import com.filmlut.android.model.FilmAdjustments
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

object LutBitmapRenderer {
    enum class RenderQuality {
        PREVIEW,
        EXPORT,
    }

    fun render(
        source: Bitmap,
        lut: CubeLut,
        adjustments: FilmAdjustments,
        quality: RenderQuality = RenderQuality.EXPORT,
    ): Bitmap {
        val width = source.width
        val height = source.height
        val basePixels = IntArray(width * height)
        source.getPixels(basePixels, 0, width, 0, 0, width, height)

        val mapped = applyLutAndTone(basePixels, width, height, lut, adjustments)
        val isPreview = quality == RenderQuality.PREVIEW
        val localBlur = if (adjustments.clarity != 0 || adjustments.sharpen != 0) {
            blur(mapped, width, height, if (isPreview) 1 else 2)
        } else {
            null
        }
        val sharedGlowBlur = if (isPreview && (adjustments.bloom > 0 || adjustments.halation > 0)) {
            blur(mapped, width, height, max(2, (adjustments.bloom + adjustments.halation) / 44 + 2))
        } else {
            null
        }
        val bloomBlur = if (!isPreview && adjustments.bloom > 0) {
            blur(mapped, width, height, max(2, adjustments.bloom / 18 + 3))
        } else {
            sharedGlowBlur
        }
        val halationBlur = if (!isPreview && adjustments.halation > 0) {
            blur(mapped, width, height, max(2, adjustments.halation / 20 + 2))
        } else {
            sharedGlowBlur
        }

        val refined = IntArray(mapped.size)
        val vignetteStrength = adjustments.vignette.coerceIn(0, 100) / 100f
        val dispersionPx = (adjustments.dispersion.coerceIn(0, 100) / 100f) * 4f
        val dispersionOffset = dispersionPx.roundToInt()
        val clarityAmount = adjustments.clarity.coerceIn(-100, 100) / 100f * 0.45f
        val sharpenAmount = adjustments.sharpen.coerceIn(-100, 100) / 100f * 0.75f
        val bloomOpacity = adjustments.bloom.coerceIn(0, 100) / 100f * 0.20f
        val halationOpacity = adjustments.halation.coerceIn(0, 100) / 100f * 0.24f
        val cx = width / 2f
        val cy = height / 2f
        val maxDistance = kotlin.math.sqrt(cx * cx + cy * cy)
        val maxDistanceSq = cx * cx + cy * cy
        val grainAmount = adjustments.grain.coerceIn(0, 100) / 100f * if (isPreview) 0.075f else 0.085f

        var index = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                var r = channel(mapped[index], 16) / 255f
                var g = channel(mapped[index], 8) / 255f
                var b = channel(mapped[index], 0) / 255f

                val luma = luminance(r, g, b)
                val highlightMask = smoothstep(0.42f, 0.86f, luma)

                localBlur?.let { blurred ->
                    val br = channel(blurred[index], 16) / 255f
                    val bg = channel(blurred[index], 8) / 255f
                    val bb = channel(blurred[index], 0) / 255f
                    val localMask = (0.35f + (1f - kotlin.math.abs(luma - 0.5f) * 1.6f)).coerceIn(0.18f, 1f)
                    r = clamp01(r + (r - br) * (clarityAmount * localMask + sharpenAmount))
                    g = clamp01(g + (g - bg) * (clarityAmount * localMask + sharpenAmount))
                    b = clamp01(b + (b - bb) * (clarityAmount * localMask + sharpenAmount))
                }

                bloomBlur?.let { blurred ->
                    val br = channel(blurred[index], 16) / 255f
                    val bg = channel(blurred[index], 8) / 255f
                    val bb = channel(blurred[index], 0) / 255f
                    val mask = highlightMask * 0.85f
                    r = screenBlend(r, br, bloomOpacity * mask)
                    g = screenBlend(g, bg, bloomOpacity * mask)
                    b = screenBlend(b, bb, bloomOpacity * mask)
                }

                halationBlur?.let { blurred ->
                    val br = channel(blurred[index], 16) / 255f
                    val bg = channel(blurred[index], 8) / 255f
                    val bb = channel(blurred[index], 0) / 255f
                    val mask = highlightMask * 0.9f
                    r = screenBlend(r, br * 1.06f, halationOpacity * mask)
                    g = screenBlend(g, bg * 0.45f, halationOpacity * mask * 0.7f)
                    b = screenBlend(b, bb * 0.18f, halationOpacity * mask * 0.4f)
                }

                if (dispersionOffset > 0) {
                    val redIndex = shiftedIndex(width, height, x - dispersionOffset, y - dispersionOffset)
                    val blueIndex = shiftedIndex(width, height, x + dispersionOffset, y + dispersionOffset)
                    r = mix(r, channel(mapped[redIndex], 16) / 255f, 0.36f)
                    b = mix(b, channel(mapped[blueIndex], 0) / 255f, 0.36f)
                }

                if (vignetteStrength > 0f) {
                    val dx = x - cx
                    val dy = y - cy
                    val distanceSq = dx * dx + dy * dy
                    val falloff = if (isPreview) {
                        (distanceSq / maxDistanceSq).coerceIn(0f, 1f)
                    } else {
                        val normalized = kotlin.math.sqrt(distanceSq) / maxDistance
                        normalized.pow(1.9f)
                    }
                    val vignette = 1f - falloff * vignetteStrength * 0.46f
                    r *= vignette
                    g *= vignette
                    b *= vignette
                }

                if (grainAmount > 0f) {
                    val noise = fastNoise(index) * grainAmount
                    r = clamp01(r + noise * 1.08f)
                    g = clamp01(g + noise)
                    b = clamp01(b + noise * 0.92f)
                }

                refined[index] = toColor(r, g, b)
                index += 1
            }
        }

        return Bitmap.createBitmap(refined, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun applyLutAndTone(
        pixels: IntArray,
        width: Int,
        height: Int,
        lut: CubeLut,
        adjustments: FilmAdjustments,
    ): IntArray {
        val output = IntArray(width * height)
        val maxIndex = lut.size - 1
        val values = lut.values
        val size = lut.size
        val lutStrength = adjustments.lutIntensity.coerceIn(0, 100) / 100f
        val shadowLiftRatio = adjustments.shadowLift.coerceIn(0, 100) / 100f
        val toeRatio = adjustments.toe.coerceIn(0, 100) / 100f
        val rolloffRatio = adjustments.highlightRolloff.coerceIn(0, 100) / 100f
        val shoulderRatio = adjustments.shoulder.coerceIn(0, 100) / 100f
        val highlightSatFactor = 1f + adjustments.highlightSaturation.coerceIn(-100, 100) / 100f * 0.55f
        val shadowSatFactor = 1f + adjustments.shadowSaturation.coerceIn(-100, 100) / 100f * 0.55f
        val highlightWarmthRatio = adjustments.highlightWarmth.coerceIn(-100, 100) / 100f * 0.16f
        val shadowCoolnessRatio = adjustments.shadowCoolness.coerceIn(-100, 100) / 100f * 0.16f

        for (index in pixels.indices) {
            val color = pixels[index]
            var r = channel(color, 16) / 255f
            var g = channel(color, 8) / 255f
            var b = channel(color, 0) / 255f

            val rScaled = r * maxIndex
            val gScaled = g * maxIndex
            val bScaled = b * maxIndex
            val r0 = floor(rScaled).toInt().coerceIn(0, maxIndex)
            val g0 = floor(gScaled).toInt().coerceIn(0, maxIndex)
            val b0 = floor(bScaled).toInt().coerceIn(0, maxIndex)
            val r1 = min(r0 + 1, maxIndex)
            val g1 = min(g0 + 1, maxIndex)
            val b1 = min(b0 + 1, maxIndex)
            val fr = rScaled - r0
            val fg = gScaled - g0
            val fb = bScaled - b0

            val o000 = offset(size, r0, g0, b0)
            val o100 = offset(size, r1, g0, b0)
            val o010 = offset(size, r0, g1, b0)
            val o110 = offset(size, r1, g1, b0)
            val o001 = offset(size, r0, g0, b1)
            val o101 = offset(size, r1, g0, b1)
            val o011 = offset(size, r0, g1, b1)
            val o111 = offset(size, r1, g1, b1)

            val lutRed = lerp3(values[o000], values[o100], values[o010], values[o110], values[o001], values[o101], values[o011], values[o111], fr, fg, fb)
            val lutGreen = lerp3(values[o000 + 1], values[o100 + 1], values[o010 + 1], values[o110 + 1], values[o001 + 1], values[o101 + 1], values[o011 + 1], values[o111 + 1], fr, fg, fb)
            val lutBlue = lerp3(values[o000 + 2], values[o100 + 2], values[o010 + 2], values[o110 + 2], values[o001 + 2], values[o101 + 2], values[o011 + 2], values[o111 + 2], fr, fg, fb)

            r = mix(r, lutRed, lutStrength)
            g = mix(g, lutGreen, lutStrength)
            b = mix(b, lutBlue, lutStrength)

            val luma = luminance(r, g, b)
            val shadowMask = (1f - smoothstep(0.18f, 0.62f, luma)).coerceIn(0f, 1f)
            val highlightMask = smoothstep(0.42f, 0.86f, luma)

            r = mix(r, r + (1f - r) * 0.16f, shadowMask * shadowLiftRatio)
            g = mix(g, g + (1f - g) * 0.16f, shadowMask * shadowLiftRatio)
            b = mix(b, b + (1f - b) * 0.16f, shadowMask * shadowLiftRatio)

            r = mix(r, r.pow(0.86f), shadowMask * toeRatio * 0.42f)
            g = mix(g, g.pow(0.86f), shadowMask * toeRatio * 0.42f)
            b = mix(b, b.pow(0.86f), shadowMask * toeRatio * 0.42f)

            r = mix(r, r.pow(1f + 0.42f * rolloffRatio), highlightMask)
            g = mix(g, g.pow(1f + 0.42f * rolloffRatio), highlightMask)
            b = mix(b, b.pow(1f + 0.42f * rolloffRatio), highlightMask)

            r = mix(r, r.pow(1f + 0.26f * shoulderRatio), highlightMask)
            g = mix(g, g.pow(1f + 0.26f * shoulderRatio), highlightMask)
            b = mix(b, b.pow(1f + 0.26f * shoulderRatio), highlightMask)

            val saturationGray = luminance(r, g, b)
            val shadowR = clamp01(saturationGray + (r - saturationGray) * shadowSatFactor)
            val shadowG = clamp01(saturationGray + (g - saturationGray) * shadowSatFactor)
            val shadowB = clamp01(saturationGray + (b - saturationGray) * shadowSatFactor)
            val highlightR = clamp01(saturationGray + (r - saturationGray) * highlightSatFactor)
            val highlightG = clamp01(saturationGray + (g - saturationGray) * highlightSatFactor)
            val highlightB = clamp01(saturationGray + (b - saturationGray) * highlightSatFactor)

            r = mix(r, shadowR, shadowMask)
            g = mix(g, shadowG, shadowMask)
            b = mix(b, shadowB, shadowMask)
            r = mix(r, highlightR, highlightMask)
            g = mix(g, highlightG, highlightMask)
            b = mix(b, highlightB, highlightMask)

            val warmth = highlightWarmthRatio * highlightMask
            val coolness = shadowCoolnessRatio * shadowMask
            r = clamp01(r + warmth - coolness * 0.10f)
            g = clamp01(g + warmth * 0.04f + coolness * 0.02f)
            b = clamp01(b - warmth * 0.11f + coolness)

            output[index] = toColor(r, g, b)
        }
        return output
    }

    private fun blur(pixels: IntArray, width: Int, height: Int, radius: Int): IntArray {
        if (radius <= 0) return pixels.copyOf()
        val horizontal = IntArray(pixels.size)
        val output = IntArray(pixels.size)
        val kernel = radius * 2 + 1

        for (y in 0 until height) {
            var sumR = 0
            var sumG = 0
            var sumB = 0
            for (i in -radius..radius) {
                val x = clampInt(i, 0, width - 1)
                val color = pixels[y * width + x]
                sumR += channel(color, 16)
                sumG += channel(color, 8)
                sumB += channel(color, 0)
            }
            for (x in 0 until width) {
                horizontal[y * width + x] = toColor(sumR / kernel / 255f, sumG / kernel / 255f, sumB / kernel / 255f)
                val removeX = clampInt(x - radius, 0, width - 1)
                val addX = clampInt(x + radius + 1, 0, width - 1)
                val remove = pixels[y * width + removeX]
                val add = pixels[y * width + addX]
                sumR += channel(add, 16) - channel(remove, 16)
                sumG += channel(add, 8) - channel(remove, 8)
                sumB += channel(add, 0) - channel(remove, 0)
            }
        }

        for (x in 0 until width) {
            var sumR = 0
            var sumG = 0
            var sumB = 0
            for (i in -radius..radius) {
                val y = clampInt(i, 0, height - 1)
                val color = horizontal[y * width + x]
                sumR += channel(color, 16)
                sumG += channel(color, 8)
                sumB += channel(color, 0)
            }
            for (y in 0 until height) {
                output[y * width + x] = toColor(sumR / kernel / 255f, sumG / kernel / 255f, sumB / kernel / 255f)
                val removeY = clampInt(y - radius, 0, height - 1)
                val addY = clampInt(y + radius + 1, 0, height - 1)
                val remove = horizontal[removeY * width + x]
                val add = horizontal[addY * width + x]
                sumR += channel(add, 16) - channel(remove, 16)
                sumG += channel(add, 8) - channel(remove, 8)
                sumB += channel(add, 0) - channel(remove, 0)
            }
        }
        return output
    }

    private fun shiftedIndex(width: Int, height: Int, x: Int, y: Int): Int {
        return clampInt(y, 0, height - 1) * width + clampInt(x, 0, width - 1)
    }

    private fun fastNoise(index: Int): Float {
        var value = index * 1103515245 + 12345
        value = value xor (value ushr 16)
        return ((value and 0xFF) / 127.5f) - 1f
    }

    private fun luminance(r: Float, g: Float, b: Float): Float = r * 0.299f + g * 0.587f + b * 0.114f

    private fun screenBlend(base: Float, layer: Float, opacity: Float): Float {
        val screened = 1f - (1f - base) * (1f - clamp01(layer))
        return mix(base, screened, opacity.coerceIn(0f, 1f))
    }

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun offset(size: Int, r: Int, g: Int, b: Int): Int = ((b * size + g) * size + r) * 3

    private fun lerp3(
        c000: Float,
        c100: Float,
        c010: Float,
        c110: Float,
        c001: Float,
        c101: Float,
        c011: Float,
        c111: Float,
        tx: Float,
        ty: Float,
        tz: Float,
    ): Float {
        val x00 = lerp(c000, c100, tx)
        val x10 = lerp(c010, c110, tx)
        val x01 = lerp(c001, c101, tx)
        val x11 = lerp(c011, c111, tx)
        return lerp(lerp(x00, x10, ty), lerp(x01, x11, ty), tz)
    }

    private fun channel(color: Int, shift: Int): Int = color shr shift and 0xFF

    private fun toColor(r: Float, g: Float, b: Float): Int {
        return (0xFF shl 24) or
            (clamp255(r) shl 16) or
            (clamp255(g) shl 8) or
            clamp255(b)
    }

    private fun clamp255(value: Float): Int = (clamp01(value) * 255f).roundToInt().coerceIn(0, 255)

    private fun clamp01(value: Float): Float = value.coerceIn(0f, 1f)

    private fun clampInt(value: Int, minValue: Int, maxValue: Int): Int = max(minValue, min(value, maxValue))

    private fun lerp(start: Float, end: Float, amount: Float): Float = start + (end - start) * amount.coerceIn(0f, 1f)

    private fun mix(start: Float, end: Float, amount: Float): Float = lerp(start, end, amount)
}
