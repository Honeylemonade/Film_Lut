package com.filmlut.android.model

data class LutSpec(
    val id: String,
    val name: String,
    val category: String,
    val lutAssetPath: String,
    val thumbnailAssetPath: String?,
    val starred: Boolean,
)
