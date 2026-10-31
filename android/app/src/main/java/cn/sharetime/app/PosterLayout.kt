package cn.sharetime.app

import kotlin.math.min

data class PhotoPosition(val x: Float, val y: Float, val size: Float)
data class PhotoBox(val x: Float, val y: Float, val width: Float, val height: Float)

/** Shared by the editor and poster, in the same canvas coordinate system. */
object PosterLayout {
    fun box(width: Float, height: Float, position: PhotoPosition): PhotoBox {
        val w = width * position.size
        val h = height * position.size.coerceAtMost(.85f)
        return PhotoBox((width - w) * position.x, (height - h) * position.y, w, h)
    }

    fun fit(box: PhotoBox, imageWidth: Int, imageHeight: Int, top: Boolean = false): PhotoBox {
        require(imageWidth > 0 && imageHeight > 0)
        val scale = min(box.width / imageWidth, box.height / imageHeight)
        val w = imageWidth * scale
        val h = imageHeight * scale
        return PhotoBox(box.x + (box.width - w) / 2, box.y + if (top) 0f else (box.height - h) / 2, w, h)
    }
}
