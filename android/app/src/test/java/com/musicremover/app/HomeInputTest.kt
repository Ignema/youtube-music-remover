package com.musicremover.app

import com.musicremover.app.HomeInput.Entry
import com.musicremover.app.HomeInput.EntryFacts
import com.musicremover.app.HomeInput.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeInputTest {
    private val a = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
    private val b = "https://youtu.be/9bZkp7q19f0"
    private val c = "https://vimeo.com/76979871"

    // --- extractLinks ---

    @Test
    fun newlineSeparated() {
        assertEquals(listOf(a, b, c), HomeInput.extractLinks("$a\n$b\n$c"))
    }

    @Test
    fun crlfAndBlankLines() {
        assertEquals(listOf(a, b), HomeInput.extractLinks("$a\r\n\r\n  $b  \r\n"))
    }

    @Test
    fun spaceAndTabSeparated() {
        assertEquals(listOf(a, b, c), HomeInput.extractLinks("$a $b\t$c"))
    }

    @Test
    fun mixedProse() {
        assertEquals(
            listOf("https://a.com/x", "https://b.com/y"),
            HomeInput.extractLinks("watch https://a.com/x and https://b.com/y."),
        )
    }

    @Test
    fun trailingPunctuationStripped() {
        assertEquals(
            listOf("https://a.com/x", "https://b.com/y", "https://c.com/z"),
            HomeInput.extractLinks("(see https://a.com/x), \"https://b.com/y\"; https://c.com/z!"),
        )
    }

    @Test
    fun duplicatesCollapseKeepingFirstOrder() {
        assertEquals(listOf(b, a), HomeInput.extractLinks("$b\n$a\n$b $a"))
    }

    @Test
    fun invalidEntriesDropped() {
        assertEquals(
            listOf(a),
            HomeInput.extractLinks("ftp://files.example.com/x\nwww.example.com\nhello world\nhttps://\n$a"),
        )
    }

    @Test
    fun bareYoutubeIdOnlyWhenAloneOnLine() {
        assertEquals(listOf("dQw4w9WgXcQ"), HomeInput.extractLinks("  dQw4w9WgXcQ  "))
        // An 11-letter word inside prose is not an id
        assertEquals(emptyList<String>(), HomeInput.extractLinks("this is wonderfully good"))
        assertEquals(listOf("dQw4w9WgXcQ", b), HomeInput.extractLinks("dQw4w9WgXcQ\n$b"))
    }

    @Test
    fun overlongUrlDropped() {
        val long = "https://a.com/" + "x".repeat(2048)
        assertEquals(listOf(b), HomeInput.extractLinks("$long\n$b"))
    }

    @Test
    fun emptyInput() {
        assertTrue(HomeInput.extractLinks("").isEmpty())
        assertTrue(HomeInput.extractLinks("   \n\t ").isEmpty())
    }

    @Test
    fun linkListInputOnPasteNotWhileTyping() {
        // Paste of several links into an empty field
        assertTrue(HomeInput.isLinkListInput("", "$a $b"))
        // Typing the next char of a second link: not yet
        assertEquals(false, HomeInput.isLinkListInput("$a https://y", "$a https://yo"))
        // Typed separator after the second link
        assertTrue(HomeInput.isLinkListInput("$a $b", "$a $b "))
    }

    // --- normalizeInputTab ---

    @Test
    fun normalizeInputTab() {
        assertEquals(0, HomeInput.normalizeInputTab(0))
        assertEquals(1, HomeInput.normalizeInputTab(1))
        // Old Batch -> Link
        assertEquals(0, HomeInput.normalizeInputTab(2))
        assertEquals(0, HomeInput.normalizeInputTab(-1))
        assertEquals(0, HomeInput.normalizeInputTab(99))
    }

    // --- pickSource ---

    @Test
    fun oneFieldLinkIsSingleWithExactText() {
        assertEquals(Source.Single("$a,"), HomeInput.pickSource(0, emptyList(), "  $a,  ", false))
        assertEquals(Source.Single("dQw4w9WgXcQ"), HomeInput.pickSource(0, emptyList(), "dQw4w9WgXcQ", false))
    }

    @Test
    fun severalLinksAreLinks() {
        assertEquals(Source.Links(listOf(a, b, c)), HomeInput.pickSource(0, listOf(a, b, c), "", false))
        // The field's link joins the list, deduplicated
        assertEquals(Source.Links(listOf(a, b)), HomeInput.pickSource(0, listOf(a), b, false))
        assertEquals(Source.Single(a), HomeInput.pickSource(0, listOf(a), a, false))
    }

    @Test
    fun listOfOneIsSingle() {
        assertEquals(Source.Single(a), HomeInput.pickSource(0, listOf(a), "", false))
    }

    @Test
    fun linkTabIgnoresStaleFile() {
        assertEquals(Source.Single(a), HomeInput.pickSource(0, emptyList(), a, true))
    }

    @Test
    fun fileTabIgnoresStaleField() {
        assertEquals(Source.File, HomeInput.pickSource(1, emptyList(), a, true))
        assertEquals(Source.File, HomeInput.pickSource(1, listOf(a, b), "", true))
    }

    @Test
    fun fallbacksWhenVisibleModeIsEmpty() {
        // File tab without a file: the field, as before
        assertEquals(Source.Single(a), HomeInput.pickSource(1, emptyList(), a, false))
        // Link tab empty with a file: the file, as before
        assertEquals(Source.File, HomeInput.pickSource(0, emptyList(), " ", true))
        assertEquals(Source.None, HomeInput.pickSource(0, emptyList(), "", false))
        assertEquals(Source.None, HomeInput.pickSource(1, emptyList(), "", false))
    }

    // --- entryFor ---

    private fun send(type: String?, text: String? = null, streams: Int = 0) =
        EntryFacts(HomeInput.ACTION_SEND, type, text, streams)

    @Test
    fun sharedTextOneLink() {
        assertEquals(Entry.SingleLink(a), HomeInput.entryFor(send("text/plain", a)))
        assertEquals(Entry.SingleLink(b), HomeInput.entryFor(send("text/plain", "Look at this video! $b")))
        // No link: the whole text, as before
        assertEquals(Entry.SingleLink("dQw4w9WgXcQ"), HomeInput.entryFor(send("text/plain", "dQw4w9WgXcQ")))
        assertEquals(Entry.SingleLink("just words"), HomeInput.entryFor(send("text/plain", "just words")))
    }

    @Test
    fun sharedTextSeveralLinks() {
        assertEquals(Entry.Links(listOf(a, b)), HomeInput.entryFor(send("text/plain", "$a\n$b")))
        assertEquals(Entry.Links(listOf(a, b, c)), HomeInput.entryFor(send("text/plain", "$a $b $c")))
    }

    @Test
    fun sharedTextBlank() {
        assertEquals(Entry.None, HomeInput.entryFor(send("text/plain", null)))
        assertEquals(Entry.None, HomeInput.entryFor(send("text/plain", "  ")))
    }

    @Test
    fun sharedMedia() {
        assertEquals(Entry.Files(1), HomeInput.entryFor(send("video/mp4", streams = 1)))
        assertEquals(Entry.Files(1), HomeInput.entryFor(send("audio/mpeg", streams = 1)))
        assertEquals(Entry.None, HomeInput.entryFor(send("video/mp4", streams = 0)))
        assertEquals(
            Entry.Files(3),
            HomeInput.entryFor(EntryFacts(HomeInput.ACTION_SEND_MULTIPLE, "video/*", null, 3)),
        )
        assertEquals(Entry.None, HomeInput.entryFor(send("image/png", streams = 1)))
        assertEquals(
            Entry.None,
            HomeInput.entryFor(EntryFacts(HomeInput.ACTION_SEND_MULTIPLE, "image/*", null, 2)),
        )
    }

    @Test
    fun widgetActions() {
        fun w(action: String) = HomeInput.entryFor(EntryFacts(action, null, null, 0))
        assertEquals(Entry.WidgetPaste, w("com.musicremover.app.PASTE_PROCESS"))
        assertEquals(Entry.WidgetFile, w("com.musicremover.app.WIDGET_FILE"))
        // Legacy Batch button on widgets placed by older versions
        assertEquals(Entry.WidgetLinks, w("com.musicremover.app.WIDGET_BATCH"))
        assertEquals(Entry.WidgetSettings, w("com.musicremover.app.WIDGET_SETTINGS"))
        assertEquals(Entry.None, w("android.intent.action.MAIN"))
        assertEquals(Entry.None, HomeInput.entryFor(EntryFacts(null, null, null, 0)))
    }

    // --- linkLabel ---

    @Test
    fun linkLabel() {
        assertEquals("youtube.com/watch?v=dQw4w9WgXcQ", HomeInput.linkLabel(a))
        assertEquals("vimeo.com/76979871", HomeInput.linkLabel(c))
        assertEquals("a.com", HomeInput.linkLabel("https://a.com/"))
        val long = HomeInput.linkLabel("https://example.com/" + "p".repeat(100), max = 20)
        assertEquals(20, long.length)
        assertTrue(long.endsWith("…"))
        assertEquals("dQw4w9WgXcQ", HomeInput.linkLabel("dQw4w9WgXcQ"))
    }
}
