package com.filmlut.android.model

data class FilmPreset(
    val name: String,
    val lutId: String,
    val adjustments: FilmAdjustments,
    val createdAt: Long,
)
