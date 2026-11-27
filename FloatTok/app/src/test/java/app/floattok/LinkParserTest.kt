package app.floattok

import app.floattok.data.LinkParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkParserTest {

    @Test fun extractsShareText() {
        val text = "Check out this video! https://vm.tiktok.com/ZMabc123/ #fyp"
        assertEquals(listOf("https://vm.tiktok.com/ZMabc123/"), LinkParser.extractUrls(text))
        val p = LinkParser.parse("https://vm.tiktok.com/ZMabc123/")
        assertEquals(LinkParser.Kind.TIKTOK_SHORT, p.kind)
        assertNull(p.videoId)
    }

    @Test fun trailingPunctuationRemoved() {
        assertEquals(listOf("https://www.tiktok.com/@a/video/7234567890123456789"),
            LinkParser.extractUrls("(see https://www.tiktok.com/@a/video/7234567890123456789)."))
    }

    @Test fun longLinkForms() {
        val forms = listOf(
            "https://www.tiktok.com/@some.user/video/7234567890123456789?is_from_webapp=1&sender_device=pc",
            "https://www.tiktokv.com/share/video/7234567890123456789/",
            "https://m.tiktok.com/v/7234567890123456789.html",
            "https://www.tiktok.com/player/v1/7234567890123456789?autoplay=1",
            "https://www.tiktok.com/embed/v2/7234567890123456789",
        )
        for (f in forms) {
            val p = LinkParser.parse(f)
            assertEquals(f, LinkParser.Kind.TIKTOK, p.kind)
            assertEquals(f, "7234567890123456789", p.videoId)
            assertEquals("tt:7234567890123456789", LinkParser.keyFor(p))
        }
    }

    @Test fun shortLinkVariants() {
        assertTrue(LinkParser.isShortLink("https://vt.tiktok.com/ZSabc/"))
        assertTrue(LinkParser.isShortLink("https://www.tiktok.com/t/ZTabc123/"))
        assertEquals(LinkParser.Kind.TIKTOK_SHORT, LinkParser.parse("https://www.tiktok.com/t/ZTabc123/").kind)
    }

    @Test fun photoAndDirectMedia() {
        assertEquals(LinkParser.Kind.TIKTOK_PHOTO, LinkParser.parse("https://www.tiktok.com/@u/photo/7300000000000000001").kind)
        assertEquals(LinkParser.Kind.DIRECT_MEDIA, LinkParser.parse("https://example.com/clip.mp4?x=1").kind)
        assertEquals(LinkParser.Kind.OTHER, LinkParser.parse("https://example.com/page").kind)
        assertEquals(LinkParser.Kind.OTHER, LinkParser.parse("https://www.tiktok.com/@someone").kind)
    }

    @Test fun notFooledByLookalikeHosts() {
        assertNull(LinkParser.videoIdFrom("https://tiktok.com.evil.example/video/7234567890123456789"))
        assertEquals(LinkParser.Kind.OTHER, LinkParser.parse("https://nottiktok.com/@a/video/7234567890123456789").kind)
    }

    @Test fun usableLinksSkipsOthers() {
        val text = "a https://example.com b https://www.tiktok.com/@x/video/7111111111111111111 c https://vm.tiktok.com/ZMx/"
        assertEquals(2, LinkParser.usableLinks(text).size)
    }
}
