package cn.sharetime.app

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class PosterLayoutTest {
    @Test fun customLayoutKeepsPositionsAndSizesAcrossExportResolution() {
        val p = PhotoPosition(.27f, .69f, .61f)
        val screen = PosterLayout.box(336f, 580f, p)
        val poster = PosterLayout.box(1008f, 1740f, p)
        assertEquals(screen.x * 3, poster.x, .001f)
        assertEquals(screen.y * 3, poster.y, .001f)
        assertEquals(screen.width * 3, poster.width, .001f)
        assertEquals(screen.height * 3, poster.height, .001f)
    }

    @Test fun photosRemainInsideCanvasAndKeepTheirAspectRatio() {
        for (size in listOf(.28f, .46f, .85f)) for (x in listOf(0f, .5f, 1f)) for (y in listOf(0f, .5f, 1f)) {
            val box = PosterLayout.box(336f, 580f, PhotoPosition(x, y, size))
            for ((width, height) in listOf(800 to 1600, 1600 to 800)) {
                val photo = PosterLayout.fit(box, width, height)
                assertEquals(width.toFloat() / height, photo.width / photo.height, .0001f)
                assertTrue(photo.x >= 0 && photo.y >= 0)
                assertTrue(photo.x + photo.width <= 336.001f)
                assertTrue(photo.y + photo.height <= 580.001f)
            }
        }
    }

    @Test fun verticalImagesAlignToTopWithoutCropping() {
        val fitted = PosterLayout.fit(PhotoBox(0f, 400f, 336f, 360f), 1600, 800, top = true)
        assertEquals(400f, fitted.y, 0f)
        assertEquals(336f, fitted.width, 0f)
        assertEquals(168f, fitted.height, 0f)
    }

    @Test fun posterUsesRecordHourInLocalTimezoneWithoutMinutes() {
        val time = Instant.parse("2026-10-30T16:08:59Z").toEpochMilli()
        assertEquals("2026 年 10 月 31 日 · 00 时", RecordPoster.hour(time, ZoneId.of("Asia/Shanghai")))
    }
}
