package com.geeksville.mesh.util

import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.createBitmap
import io.github.thibseisel.identikon.Identicon
import io.github.thibseisel.identikon.drawToBitmap

object IdentIkonGen {

    private val cachedBitmaps = object : LruCache<String, ImageBitmap>(2500) {}

    @Synchronized
    fun generateOrGetFromHexId(
        nodeHexId: String,
        size: Int = 100
    ): ImageBitmap {

        val key = "$nodeHexId:$size"

        cachedBitmaps.get(key)?.let { return it }

        val digest = Crypto.sha256Digest(nodeHexId)
        val icon = Identicon.fromHash(digest, size)

        val bitmap = createBitmap(size, size)
        icon.drawToBitmap(bitmap)

        val imageBitmap = bitmap.asImageBitmap()

        cachedBitmaps.put(key, imageBitmap)

        return imageBitmap
    }
}