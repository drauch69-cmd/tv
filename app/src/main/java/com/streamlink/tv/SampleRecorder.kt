package com.streamlink.tv

import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Keeps a small sample of what the current link's stream really sent (playlists and the first two
 * video chunks, as received from the network) so it can be saved from the phone page ("Save
 * sample") and analysed. Plain JVM code, thread-safe, strictly bounded in memory:
 *   playlists: 4 x 256 KB max (master + last 3 media versions)
 *   chunks:    2 x 6 MB max
 * Total worst case is about 13 MB. Reset whenever a new link starts ([Report.start]).
 */
object SampleRecorder {
    const val MAX_PLAYLIST = 256 * 1024
    const val MAX_CHUNK = 6 * 1024 * 1024
    const val MAX_CHUNKS = 2
    const val MAX_MEDIA_VERSIONS = 3

    class Playlist(val url: String, val text: ByteArray, val truncated: Boolean, val contentType: String, val fetchedAt: Long)

    /** Records one chunk as it is downloaded. Used by exactly one data source; not shared. */
    class ChunkTap internal constructor(val url: String, val startedAt: Long) {
        private var raw = ByteArrayOutputStream()
        @Volatile var rawSize = 0L; private set // total bytes received, even past the cap
        @Volatile var outputSize = 0L; private set // bytes handed to the player
        @Volatile var truncated = false; private set
        @Volatile var decision = "not decided"
        @Volatile var contentType = "?"
        @Volatile var complete = false

        fun received(b: ByteArray, off: Int, len: Int) {
            if (len <= 0) return
            synchronized(this) {
                rawSize += len
                val room = MAX_CHUNK - raw.size()
                if (room <= 0) { truncated = true; return }
                val take = minOf(room, len)
                raw.write(b, off, take)
                if (take < len) truncated = true
            }
        }

        fun delivered(len: Int) { if (len > 0) synchronized(this) { outputSize += len } }

        @Synchronized fun rawBytes(): ByteArray = raw.toByteArray()
    }

    private val lock = Any()
    private var master: Playlist? = null
    private val media = ArrayList<Playlist>()
    private val chunks = ArrayList<ChunkTap>()
    private val siteStreams = ArrayList<String>()

    fun reset() = synchronized(lock) {
        master = null
        media.clear()
        chunks.clear()
        siteStreams.clear()
    }

    /** A playlist address the site's own player loaded (the app's finder had missed it). Up to 5. */
    fun sitePlayerStream(url: String) = synchronized(lock) {
        if (url !in siteStreams && siteStreams.size < 5) siteStreams.add(url)
    }

    /** A playlist as received (before any fixing). [len] bytes of [bytes] are used. */
    fun playlist(url: String, bytes: ByteArray, len: Int, truncatedBySource: Boolean, contentType: String) {
        val n = minOf(len, MAX_PLAYLIST)
        val copy = bytes.copyOf(n)
        val p = Playlist(url, copy, truncatedBySource || len > MAX_PLAYLIST, contentType, System.currentTimeMillis())
        val isMedia = String(copy, Charsets.ISO_8859_1).contains("#EXTINF")
        synchronized(lock) {
            if (!isMedia) {
                master = p
            } else {
                val last = media.lastOrNull()
                if (last != null && last.url == url && last.text.contentEquals(copy)) return // unchanged refresh
                media.add(p)
                while (media.size > MAX_MEDIA_VERSIONS) media.removeAt(0)
            }
        }
    }

    /** Claims one of the first [MAX_CHUNKS] chunk slots, or null when they are taken. */
    fun beginChunk(url: String): ChunkTap? = synchronized(lock) {
        if (chunks.size >= MAX_CHUNKS) null else ChunkTap(url, System.currentTimeMillis()).also { chunks.add(it) }
    }

    fun hasData(): Boolean = synchronized(lock) { siteStreams.isNotEmpty() || master != null || media.isNotEmpty() || chunks.isNotEmpty() || lastPage != null || lastStreamPage != null }

    /** The last list (index) page the app read: its structure and a picture of it. Not cleared by [reset]. */
    class PageSnap(val url: String, val html: ByteArray, val image: ByteArray?, val at: Long, val note: String)

    @Volatile var lastPage: PageSnap? = null
        private set

    /** The last stream page searched (a player search that found no stream, or the links search): same as [lastPage]. Not cleared by [reset]. */
    @Volatile var lastStreamPage: PageSnap? = null
        private set

    fun page(url: String, html: String, image: ByteArray?, note: String) {
        lastPage = snap(url, html, image, note)
    }

    fun streamPage(url: String, html: String, image: ByteArray?, note: String) {
        lastStreamPage = snap(url, html, image, note)
    }

    private fun snap(url: String, html: String, image: ByteArray?, note: String): PageSnap {
        var h = html.toByteArray(Charsets.UTF_8)
        if (h.size > MAX_PAGE_HTML) h = h.copyOf(MAX_PAGE_HTML)
        val img = image?.takeIf { it.size <= MAX_PAGE_IMAGE }
        return PageSnap(url, h, img, System.currentTimeMillis(), note)
    }

    /** The zip for GET /sample, or null if nothing was recorded yet. */
    fun zip(reportText: String, appVersion: String, now: Long = System.currentTimeMillis()): ByteArray? {
        val page = lastPage
        val streamPage = lastStreamPage
        val snap = synchronized(lock) {
            if (master == null && media.isEmpty() && chunks.isEmpty() && siteStreams.isEmpty() && page == null && streamPage == null) null
            else Triple(master, media.toList(), chunks.toList()) to siteStreams.toList()
        } ?: return null
        val (m, md, ck) = snap.first
        return buildZip(reportText, appVersion, m, md, ck, now, page, streamPage, snap.second)
    }

    fun fileName(now: Long = System.currentTimeMillis()): String {
        val f = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return "streamlink-sample-" + f.format(Date(now)) + ".zip"
    }

    fun buildZip(
        reportText: String,
        appVersion: String,
        master: Playlist?,
        media: List<Playlist>,
        chunks: List<ChunkTap>,
        now: Long = System.currentTimeMillis(),
        page: PageSnap? = null,
        streamPage: PageSnap? = null,
        siteStreams: List<String> = emptyList(),
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).also { it.timeZone = TimeZone.getTimeZone("UTC") }
        val urls = StringBuilder()
        val info = StringBuilder()
        ZipOutputStream(out).use { z ->
            fun put(name: String, data: ByteArray) {
                z.putNextEntry(ZipEntry(name))
                z.write(data)
                z.closeEntry()
            }
            fun put(name: String, text: String) = put(name, text.toByteArray(Charsets.UTF_8))
            // A page snapshot: <prefix>.html, <prefix>.jpg, and their lines in urls.txt and info.txt.
            fun putPage(prefix: String, what: String, p: PageSnap) {
                put("$prefix.html", p.html)
                p.image?.let { put("$prefix.jpg", it) }
                urls.append("$prefix.html: ").append(p.url).append('\n')
                info.append("$prefix.html: ").append(p.html.size).append(" bytes, read ").append(iso.format(Date(p.at)))
                    .append(", result: ").append(p.note)
                    .append(if (p.image != null) "\n$prefix.jpg: ${p.image.size} bytes" else "\n(no picture of the $what)")
                    .append('\n')
            }

            put("README.txt", README)
            put("report.txt", reportText)
            if (master != null) put("master.m3u8", master.text)
            media.forEachIndexed { i, p -> put("media-${i + 1}.m3u8", p.text) }
            chunks.forEachIndexed { i, c -> put("chunk-${i + 1}.bin", c.rawBytes()) }

            info.append("StreamLink sample\napp version: ").append(appVersion)
                .append("\nsaved at: ").append(iso.format(Date(now))).append("\n\n")
            if (master != null) {
                urls.append("master.m3u8: ").append(master.url).append('\n')
                info.append("master.m3u8: ").append(master.text.size).append(" bytes").append(describe(master, iso)).append('\n')
            }
            media.forEachIndexed { i, p ->
                urls.append("media-${i + 1}.m3u8: ").append(p.url).append('\n')
                info.append("media-${i + 1}.m3u8: ").append(p.text.size).append(" bytes").append(describe(p, iso)).append('\n')
            }
            chunks.forEachIndexed { i, c ->
                urls.append("chunk-${i + 1}.bin: ").append(c.url).append('\n')
                info.append("chunk-${i + 1}.bin: saved ").append(c.rawBytes().size).append(" of ").append(c.rawSize)
                    .append(" raw bytes received").append(if (c.truncated) " (cut at the ${MAX_CHUNK / 1024 / 1024} MB limit)" else "")
                    .append(", content type ").append(c.contentType)
                    .append("\n    bytes given to the player after unwrapping: ").append(c.outputSize)
                    .append(if (c.complete) "" else " (download was still running or stopped early)")
                    .append("\n    unwrap decision: ").append(c.decision).append('\n')
            }
            if (page != null) putPage("list-page", "list page", page)
            if (streamPage != null) putPage("stream-page", "stream page", streamPage)
            siteStreams.forEachIndexed { i, u ->
                urls.append("site-player-stream-${i + 1}: ").append(u).append('\n')
            }
            if (siteStreams.isNotEmpty()) {
                info.append("The site's own player loaded ").append(siteStreams.size)
                    .append(" playlist(s) the finder missed (addresses in urls.txt; the playlists above may be these)\n")
            }
            if (master == null) info.append("(no main playlist seen)\n")
            if (media.isEmpty()) info.append("(no video playlist seen)\n")
            if (chunks.isEmpty()) info.append("(no video chunk seen)\n")
            put("urls.txt", urls.toString())
            put("info.txt", info.toString())
        }
        return out.toByteArray()
    }

    private fun describe(p: Playlist, iso: SimpleDateFormat) =
        ", type ${p.contentType}, fetched ${iso.format(Date(p.fetchedAt))}" + if (p.truncated) ", TRUNCATED" else ""

    private const val MAX_PAGE_HTML = 1_500_000
    private const val MAX_PAGE_IMAGE = 1_000_000

    private const val README = """StreamLink sample - what is in this zip

report.txt     The "What happened?" report from the phone page.
master.m3u8    The last main (master) playlist, as the site sent it.
media-1..3     The last (up to 3) versions of the video (segment) playlist, oldest first,
               as the site sent them (before StreamLink fixes any typos).
chunk-1.bin    The first two video chunks the player loaded, EXACTLY as received from
chunk-2.bin    the network, before StreamLink removed any disguise. Each is cut at 6 MB.
urls.txt       The full addresses of all of the above, INCLUDING any access tokens.
info.txt       Sizes, content types, the unwrap decision per chunk, app version.
list-page.html The last list page the app read, as the hidden browser saw it
               (scripts and styles removed), if any.
list-page.jpg  A picture of that page as the hidden browser saw it.
stream-page.html The last stream page the app searched (the player's own search when it
               found no stream, or the links search), as the hidden browser saw it
               (scripts and styles removed), if any. Its result line in info.txt says
               which search it was and what it found.
stream-page.jpg A picture of that stream page as the hidden browser saw it.
               When the app fell back to the site's own player, playlists that player
               loaded are saved too (master/media above) and listed in urls.txt as
               site-player-stream-N.

Privacy: urls.txt and the playlists contain the stream's address and tokens.
Only share this file with people you trust.
"""
}
