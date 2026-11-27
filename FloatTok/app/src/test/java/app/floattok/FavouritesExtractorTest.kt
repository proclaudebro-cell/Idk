package app.floattok

import app.floattok.data.ExtractResult
import app.floattok.data.FavouritesExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FavouritesExtractorTest {

    // Shape of TikTok's JSON export: likes live under "ItemFavoriteList", favourites under "FavoriteVideoList".
    private val exportJson = """
    {
      "Activity": {
        "Favorite Videos": {
          "FavoriteVideoList": [
            {"Date": "2026-03-14 19:02:11", "Link": "https://www.tiktokv.com/share/video/7111111111111111111/"},
            {"Date": "2026-03-10 08:00:00", "Link": "https://www.tiktokv.com/share/video/7222222222222222222/"}
          ]
        },
        "Like List": {
          "ItemFavoriteList": [
            {"Date": "2026-03-01 10:00:00", "Link": "https://www.tiktokv.com/share/video/7333333333333333333/"},
            {"Date": "2026-03-02 10:00:00", "Link": "https://www.tiktokv.com/share/video/7444444444444444444/"},
            {"Date": "2026-03-03 10:00:00", "Link": "https://www.tiktokv.com/share/video/7555555555555555555/"}
          ]
        },
        "Favorite Sounds": {"FavoriteSoundList": [{"Date": "x", "Link": "https://www.tiktok.com/music/abc-123"}]},
        "Video Browsing History": {"VideoList": [{"Date": "x", "Link": "https://www.tiktokv.com/share/video/7666666666666666666/"}]},
        "Login History": {"LoginHistoryList": [{"Date": "x", "IP": "1.2.3.4", "Note": "esc \" \\ é"}]}
      },
      "Profile": {"Profile Information": {"ProfileMap": {"userName": "me", "bioDescription": null, "likesReceived": 42, "verified": false}}}
    }
    """.trimIndent()

    @Test fun jsonTakesFavouritesNotLikes() {
        val r = ExtractResult()
        FavouritesExtractor.fromJson(StringReader(exportJson), r)
        assertEquals(listOf(
            "https://www.tiktokv.com/share/video/7111111111111111111/",
            "https://www.tiktokv.com/share/video/7222222222222222222/",
        ), r.records.keys.toList())
        assertEquals("2026-03-14 19:02:11", r.records.values.first().date)
        assertEquals(3, r.likesSkipped)
        assertEquals(2, r.otherLinksSkipped) // sound + browsing history
    }

    @Test fun lowercaseKeysAndCollections() {
        val json = """
        {"Your Activity": {
           "Favorite Videos": {"favoriteVideoList": [{"date": "d1", "link": "https://www.tiktok.com/@a/video/7000000000000000001"}]},
           "Favorite Collections": {"CollectionList": [
              {"CollectionName": "Recipes", "Videos": [{"Link": "https://www.tiktok.com/@b/video/7000000000000000002"}]},
              {"CollectionName": "Workouts", "Videos": ["https://www.tiktok.com/@c/video/7000000000000000003"]}
           ]}
        }}
        """.trimIndent()
        val r = ExtractResult()
        FavouritesExtractor.fromJson(StringReader(json), r)
        assertEquals(3, r.records.size)
        assertEquals("d1", r.records["https://www.tiktok.com/@a/video/7000000000000000001"]?.date)
        assertEquals("Recipes", r.records["https://www.tiktok.com/@b/video/7000000000000000002"]?.collection)
        assertEquals("Workouts", r.records["https://www.tiktok.com/@c/video/7000000000000000003"]?.collection)
    }

    @Test fun txtExport() {
        val txt = "Date: 2026-01-01 10:00:00\nLink: https://www.tiktokv.com/share/video/7111111111111111111/\n\n" +
            "Date: 2026-01-02 10:00:00\nLink: https://www.tiktokv.com/share/video/7222222222222222222/\n"
        val r = ExtractResult()
        FavouritesExtractor.fromTxt("Activity/Favorite Videos.txt", txt, r, treatUnknownAsFavourites = false)
        assertEquals(2, r.records.size)
        assertEquals("2026-01-02 10:00:00", r.records["https://www.tiktokv.com/share/video/7222222222222222222/"]?.date)

        val likes = ExtractResult()
        FavouritesExtractor.fromTxt("Activity/Like List.txt", txt, likes, treatUnknownAsFavourites = true)
        assertEquals(0, likes.records.size)
        assertEquals(2, likes.likesSkipped)
    }

    @Test fun zipWithJsonAndTxtDeduplicates() {
        val bytes = ByteArrayOutputStream().use { bos ->
            ZipOutputStream(bos).use { z ->
                z.putNextEntry(ZipEntry("user_data_tiktok.json")); z.write(exportJson.toByteArray()); z.closeEntry()
                z.putNextEntry(ZipEntry("Activity/Favorite Videos.txt"))
                z.write("Date: x\nLink: https://www.tiktokv.com/share/video/7111111111111111111/\nDate: y\nLink: https://www.tiktokv.com/share/video/7999999999999999999/\n".toByteArray())
                z.closeEntry()
                z.putNextEntry(ZipEntry("Activity/Like List.txt")); z.write("Link: https://www.tiktokv.com/share/video/7333333333333333333/\n".toByteArray()); z.closeEntry()
                z.putNextEntry(ZipEntry("Activity/Browsing History.txt")); z.write("Link: https://www.tiktokv.com/share/video/7888888888888888888/\n".toByteArray()); z.closeEntry()
            }
            bos.toByteArray()
        }
        val r = ExtractResult()
        FavouritesExtractor.fromStream("export.zip", ByteArrayInputStream(bytes), r)
        assertEquals(setOf(
            "https://www.tiktokv.com/share/video/7111111111111111111/",
            "https://www.tiktokv.com/share/video/7222222222222222222/",
            "https://www.tiktokv.com/share/video/7999999999999999999/",
        ), r.records.keys)
        assertEquals(4, r.likesSkipped)
    }

    @Test fun detectsJsonAndPlainTextByContent() {
        val r = ExtractResult()
        FavouritesExtractor.fromStream("data.bin", ByteArrayInputStream(("﻿  " + exportJson).toByteArray()), r)
        assertEquals(2, r.records.size)
        val t = ExtractResult()
        FavouritesExtractor.fromStream("mylinks.txt", ByteArrayInputStream("https://www.tiktok.com/@a/video/7000000000000000009".toByteArray()), t)
        assertEquals(1, t.records.size)
    }

    @Test fun keyClassification() {
        assertTrue(FavouritesExtractor.isFavouriteVideoKey("Favorite Videos"))
        assertTrue(FavouritesExtractor.isFavouriteVideoKey("FavoriteVideoList"))
        assertTrue(FavouritesExtractor.isFavouriteVideoKey("favourite_videos"))
        assertTrue(!FavouritesExtractor.isFavouriteVideoKey("ItemFavoriteList"))
        assertTrue(FavouritesExtractor.isLikeKey("ItemFavoriteList"))
        assertTrue(FavouritesExtractor.isLikeKey("Like List"))
        assertTrue(!FavouritesExtractor.isFavouriteVideoKey("Favorite Sounds"))
    }
}
