package com.filmlut.android.model

import android.graphics.Bitmap
import android.net.Uri

data class PhotoItem(
    val uri: Uri,
    val thumbnail: Bitmap,
    val label: String,
)
