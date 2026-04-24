package com.filmlut.android.model

data class FilmAdjustments(
    val lutIntensity: Int = 100,
    val grain: Int = 38,
    val dispersion: Int = 8,
    val vignette: Int = 18,
    val sharpen: Int = -10,
    val clarity: Int = -8,
    val highlightRolloff: Int = 35,
    val halation: Int = 22,
    val bloom: Int = 18,
    val shadowLift: Int = 16,
    val toe: Int = 28,
    val shoulder: Int = 42,
    val highlightSaturation: Int = -18,
    val shadowSaturation: Int = -10,
    val highlightWarmth: Int = 8,
    val shadowCoolness: Int = 6,
) {
    fun summary(): String {
        return "L$lutIntensity · G$grain · B$bloom · H$halation"
    }
}
