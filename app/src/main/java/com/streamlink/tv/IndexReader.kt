package com.streamlink.tv

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZoneId
import kotlin.concurrent.thread

/**
 * Loads an index page in a hidden, locked-down WebView, waits for it to settle, and reads its
 * events: with a site recipe (recipes.json on GitHub) if one matches, otherwise automatically
 * (assets/list-extract.js). The holder must be laid out (not GONE), like StreamSniffer's.
 */
class IndexReader(private val context: Context, private val holder: ViewGroup) {

    private val main = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var client: SafeWebViewClient? = null
    private var done = true
    private var generation = 0
    private var onResult: ((ListResult) -> Unit)? = null
    private var requestUrl = ""
    private var startedAt = 0L
    private var firstLoadedAt = 0L
    private var lastSignature: String? = null
    private var unchanged = 0
    private var polling = false
    private var extracting = false
    private var retried = false
    private var recipes: List<ListRecipes.Recipe>? = null // null while still being fetched

    private val overallTimeout = Runnable {
        if (done) return@Runnable
        if (firstLoadedAt != 0L && !extracting) {
            Report.log("List: page still changing after ${OVERALL_MS / 1000}s; reading what is there")
            extract(allowRetry = false)
            main.postDelayed(hardStop, 4000)
        } else if (!extracting) {
            finish(fail("The page did not load in time"))
        } else {
            main.postDelayed(hardStop, 4000)
        }
    }
    private val hardStop = Runnable { if (!done) finish(fail("Reading the page took too long")) }
    private val poll = object : Runnable {
        override fun run() {
            if (done || extracting) return
            pollOnce()
        }
    }

    fun read(url: String, callback: (ListResult) -> Unit) {
        cancel()
        val scheme = Uri.parse(url).scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            val r = ListResult(url, "", emptyList(), "automatic", "Not a web address: $url")
            main.post { callback(r) }
            return
        }
        done = false
        generation++
        val gen = generation
        onResult = callback
        requestUrl = url
        startedAt = System.currentTimeMillis()
        firstLoadedAt = 0L
        lastSignature = null
        unchanged = 0
        polling = false
        extracting = false
        retried = false
        recipes = null
        val wv = createWebView(url)
        webView = wv
        // A fixed desktop-ish size, so pages lay out their list and not a phone menu.
        holder.addView(wv, ViewGroup.LayoutParams(1280, 720))
        Report.log("List: loading ${Report.host(url)}")
        wv.loadUrl(url)
        main.postDelayed(overallTimeout, OVERALL_MS)
        thread {
            val loaded = ListRecipes.load(context)
            main.post { if (!done && gen == generation) recipes = loaded }
        }
    }

    fun cancel() {
        done = true
        generation++
        onResult = null
        main.removeCallbacks(overallTimeout)
        main.removeCallbacks(hardStop)
        main.removeCallbacks(poll)
        destroyWebView()
    }

    private fun destroyWebView() {
        val wv = webView ?: return
        webView = null
        client = null
        wv.stopLoading()
        wv.loadUrl("about:blank")
        holder.removeView(wv)
        wv.destroy()
    }

    private fun currentPage(): String = client?.siteUrl ?: requestUrl

    private fun fail(msg: String): ListResult {
        Report.log("List: $msg")
        return ListResult(currentPage(), "", emptyList(), "automatic", msg)
    }

    private fun finish(result: ListResult) {
        if (done) return
        val cb = onResult
        cancel()
        cb?.invoke(result)
    }

    private fun createWebView(url: String): WebView {
        val wv = WebView(context)
        wv.isFocusable = false
        wv.isFocusableInTouchMode = false
        SafeWeb.configure(wv)
        wv.webChromeClient = SafeChromeClient()
        val c = object : SafeWebViewClient(url) {
            override fun onLoaded() {
                pageLoaded()
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true && !done && firstLoadedAt == 0L) {
                    main.post { finish(fail("Could not open the page (${error?.description ?: "network error"})")) }
                }
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: android.webkit.WebResourceResponse?) {
                val code = errorResponse?.statusCode ?: return
                if (request?.isForMainFrame == true && code >= 400 && !done && firstLoadedAt == 0L) {
                    main.post { finish(fail("The page answered HTTP $code")) }
                }
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                main.post { finish(fail("The page crashed the web view")) }
                return true
            }
        }
        client = c
        wv.webViewClient = c
        return wv
    }

    private fun pageLoaded() {
        if (done) return
        val now = System.currentTimeMillis()
        if (firstLoadedAt == 0L) firstLoadedAt = now
        // a (re)load restarts the "has it stopped changing" count
        lastSignature = null
        unchanged = 0
        if (!polling) {
            polling = true
            main.postDelayed(poll, POLL_MS)
        }
    }

    /** One look at the page; settled = unchanged for 2 polls in a row, or 15s since it first finished loading. */
    private fun pollOnce() {
        val wv = webView ?: return
        val gen = generation
        wv.evaluateJavascript(SIGNATURE_JS) { raw ->
            if (done || gen != generation || extracting) return@evaluateJavascript
            val sig = (try { MiniJson.parse(raw ?: "null") as? String } catch (_: Exception) { null }) ?: ""
            if (sig == lastSignature) unchanged++ else { unchanged = 0; lastSignature = sig }
            val waited = System.currentTimeMillis() - firstLoadedAt
            if (unchanged >= 2 || waited >= SETTLE_MAX_MS) {
                extract(allowRetry = true)
            } else {
                main.postDelayed(poll, POLL_MS)
            }
        }
    }

    private fun extract(allowRetry: Boolean) {
        val wv = webView ?: return
        if (done || extracting) return
        // The recipe list may still be downloading; give it a moment (the overall timeout bounds this).
        if (recipes == null && System.currentTimeMillis() - startedAt < OVERALL_MS - 6000) {
            main.postDelayed({ if (!done) extract(allowRetry) }, 300)
            return
        }
        extracting = true
        val gen = generation
        val pageHost = Uri.parse(currentPage()).host
        val reqHost = Uri.parse(requestUrl).host
        val recipe = recipes?.let { ListRecipes.match(it, pageHost) ?: ListRecipes.match(it, reqHost) }
        val script = try { loadScript(context) } catch (e: Exception) { finish(fail("Reader script missing")); return }
        val arg = if (recipe != null) JSONObject.quote(recipe.toJson()) else "null"
        wv.evaluateJavascript("($script)($arg)") { raw ->
            if (done || gen != generation) return@evaluateJavascript
            val ex = try {
                parseExtraction(MiniJson.parse(raw ?: "null") as? String ?: "", currentPage(), recipe?.host)
            } catch (e: Exception) {
                null
            }
            val parsed = ex?.result
            if (ex == null || parsed == null) {
                finish(fail("The page could not be read"))
                return@evaluateJavascript
            }
            if (parsed.items.isEmpty() && allowRetry && !retried) {
                // some lists load more when scrolled; scroll once and look again
                retried = true
                extracting = false
                Report.log("List: nothing found; scrolling once and retrying")
                wv.evaluateJavascript("window.scrollTo(0, document.body ? document.body.scrollHeight : 0)", null)
                lastSignature = null
                unchanged = 0
                main.postDelayed({ if (!done && gen == generation) extract(allowRetry = true) }, 2500)
                return@evaluateJavascript
            }
            val secs = (System.currentTimeMillis() - startedAt) / 1000.0
            Report.log(
                String.format(java.util.Locale.US, "List: read %d events from %s (%s) in %.1fs",
                    parsed.items.size, Report.host(parsed.pageUrl), if (parsed.method.startsWith("recipe")) "recipe" else "automatic", secs)
            )
            if (ex.debug.isNotEmpty()) Report.log("List: ${ex.debug}")
            val result = if (parsed.items.isEmpty()) parsed.copy(error = "No events found on this page") else parsed
            finish(result)
        }
    }

    companion object {
        private const val POLL_MS = 1000L
        private const val SETTLE_MAX_MS = 15_000L
        private const val OVERALL_MS = 25_000L
        private const val SIGNATURE_JS =
            "(function(){var b=document.body;if(!b)return '0:0';return b.innerText.length+':'+b.getElementsByTagName('*').length;})()"

        @Volatile private var scriptCache: String? = null

        private fun loadScript(context: Context): String {
            scriptCache?.let { return it }
            val text = context.applicationContext.assets.open("list-extract.js").bufferedReader().use { it.readText() }
            scriptCache = text
            return text
        }

        /**
         * Turns the extractor's JSON into a [ListResult]: absolute http(s) URLs only, no ad networks
         * or TV-local addresses, start times parsed with [DateGuess]. Pure apart from the
         * [AdBlock] host checks; [now] and [zone] are injectable for tests.
         */
        internal fun parseExtraction(
            json: String,
            fallbackUrl: String,
            recipeHost: String?,
            now: Long = System.currentTimeMillis(),
            zone: ZoneId = ZoneId.systemDefault(),
            hostBlocked: (String?) -> Boolean = { AdBlock.isBlockedHost(it) || AdBlock.isLocalHost(it) },
        ): Extraction {
            val root = MiniJson.parse(json) as Map<*, *>
            val items = ArrayList<ListItem>()
            for (o in (root["items"] as? List<*>) ?: emptyList<Any?>()) {
                val m = o as? Map<*, *> ?: continue
                val url = (m["url"] as? String)?.trim().orEmpty()
                val uri = try { java.net.URI(url) } catch (_: Exception) { continue }
                if (uri.scheme?.lowercase() !in listOf("http", "https") || uri.host == null) continue
                if (hostBlocked(uri.host)) continue
                val title = (m["title"] as? String)?.trim().orEmpty()
                if (title.isEmpty()) continue
                val timeText = (m["timeText"] as? String)?.trim().orEmpty()
                val section = (m["section"] as? String)?.trim().orEmpty()
                val datetime = (m["datetime"] as? String)?.trim()?.ifEmpty { null }
                items.add(
                    ListItem(
                        title = title,
                        subtitle = (m["subtitle"] as? String)?.trim().orEmpty(),
                        url = url,
                        startMillis = DateGuess.guess(timeText, section, now, zone, datetime),
                        timeText = timeText,
                        section = section,
                        liveHint = m["liveHint"] == true,
                        imageUrl = (m["imageUrl"] as? String)?.ifEmpty { null },
                    )
                )
            }
            val method = if (root["method"] == "recipe" && recipeHost != null) "recipe:$recipeHost" else "automatic"
            val result = ListResult(
                pageUrl = (root["pageUrl"] as? String)?.ifEmpty { null } ?: fallbackUrl,
                pageTitle = (root["pageTitle"] as? String).orEmpty(),
                items = items,
                method = method,
                error = null,
            )
            return Extraction(result, (root["debug"] as? String).orEmpty())
        }

        internal class Extraction(val result: ListResult, val debug: String)
    }
}

/** Tiny JSON reader (objects, arrays, strings, numbers, booleans, null) so this code is unit-testable on the JVM. Throws on malformed input. */
internal object MiniJson {
    fun parse(s: String): Any? {
        val p = Parser(s)
        p.skip()
        val v = p.value(0)
        p.skip()
        if (p.i != s.length) throw IllegalArgumentException("trailing characters")
        return v
    }

    private class Parser(val s: String) {
        var i = 0

        fun skip() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
        }

        fun value(depth: Int): Any? {
            if (depth > 40) throw IllegalArgumentException("too deep")
            if (i >= s.length) throw IllegalArgumentException("unexpected end")
            return when (val c = s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else throw IllegalArgumentException("unexpected '$c'")
            }
        }

        private fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) throw IllegalArgumentException("bad literal")
            i += word.length
            return v
        }

        private fun num(): Double {
            val st = i
            while (i < s.length && (s[i] in '0'..'9' || s[i] in "+-.eE")) i++
            return s.substring(st, i).toDouble()
        }

        private fun str(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw IllegalArgumentException("unterminated string")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) throw IllegalArgumentException("bad escape")
                        when (val e = s[i++]) {
                            'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                            '/', '\\', '"' -> sb.append(e)
                            'u' -> {
                                if (i + 4 > s.length) throw IllegalArgumentException("bad \\u")
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> throw IllegalArgumentException("bad escape")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun arr(depth: Int): List<Any?> {
            i++
            val out = ArrayList<Any?>()
            skip()
            if (i < s.length && s[i] == ']') { i++; return out }
            while (true) {
                skip()
                out.add(value(depth + 1))
                skip()
                if (i >= s.length) throw IllegalArgumentException("unterminated array")
                if (s[i] == ',') { i++; continue }
                if (s[i] == ']') { i++; return out }
                throw IllegalArgumentException("bad array")
            }
        }

        private fun obj(depth: Int): Map<String, Any?> {
            i++
            val out = LinkedHashMap<String, Any?>()
            skip()
            if (i < s.length && s[i] == '}') { i++; return out }
            while (true) {
                skip()
                if (i >= s.length || s[i] != '"') throw IllegalArgumentException("bad key")
                val k = str()
                skip()
                if (i >= s.length || s[i] != ':') throw IllegalArgumentException("missing colon")
                i++
                skip()
                out[k] = value(depth + 1)
                skip()
                if (i >= s.length) throw IllegalArgumentException("unterminated object")
                if (s[i] == ',') { i++; continue }
                if (s[i] == '}') { i++; return out }
                throw IllegalArgumentException("bad object")
            }
        }
    }
}

/**
 * Per-site "recipes" for index pages the automatic reader gets wrong, kept in recipes.json in the
 * repo root and downloaded (cached 6h in SharedPreferences "streamlink"/"recipes_json").
 */
object ListRecipes {
    const val URL = "https://raw.githubusercontent.com/drauch69-cmd/tv/main/recipes.json"
    private const val PREFS = "streamlink"
    private const val KEY = "recipes_json"
    private const val KEY_AT = "recipes_json_at"
    private const val REFRESH_MS = 6 * 60 * 60 * 1000L
    private const val MAX_BYTES = 256 * 1024

    data class Recipe(
        val host: String,
        val item: String,
        val title: String? = null,
        val subtitle: String? = null,
        val time: String? = null,
        val link: String? = null,
        val section: String? = null,
        val live: String? = null,
    ) {
        /** The JSON handed to list-extract.js. */
        fun toJson(): String {
            val o = JSONObject()
            o.put("host", host); o.put("item", item)
            title?.let { o.put("title", it) }; subtitle?.let { o.put("subtitle", it) }; time?.let { o.put("time", it) }
            link?.let { o.put("link", it) }; section?.let { o.put("section", it) }; live?.let { o.put("live", it) }
            return o.toString()
        }
    }

    private fun normHost(h: String?): String = (h ?: "").trim().lowercase().removePrefix("www.").trimEnd('.')

    /** Reads recipes.json text; bad JSON gives an empty list, and entries without "host" and "item" are skipped. Never throws. */
    fun parse(json: String): List<Recipe> {
        val sites = try { (MiniJson.parse(json) as? Map<*, *>)?.get("sites") as? List<*> } catch (_: Exception) { null } ?: return emptyList()
        val out = ArrayList<Recipe>()
        for (e in sites) {
            val m = e as? Map<*, *> ?: continue
            val host = normHost(m["host"] as? String)
            val item = (m["item"] as? String)?.trim().orEmpty()
            if (host.isEmpty() || item.isEmpty()) continue
            fun opt(k: String) = (m[k] as? String)?.trim()?.ifEmpty { null }
            out.add(Recipe(host, item, opt("title"), opt("subtitle"), opt("time"), opt("link"), opt("section"), opt("live")))
        }
        return out
    }

    /** The recipe for this page host (exact or a parent domain of it); the most specific one wins. */
    fun match(recipes: List<Recipe>, pageHost: String?): Recipe? {
        val h = normHost(pageHost)
        if (h.isEmpty()) return null
        return recipes.filter { h == it.host || h.endsWith("." + it.host) }.maxByOrNull { it.host.length }
    }

    /** Blocking (network): the cached list if fresh, else download, else the cache, else empty. Call off the main thread. */
    fun load(context: Context): List<Recipe> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cached = prefs.getString(KEY, null)
        val at = prefs.getLong(KEY_AT, 0L)
        val now = System.currentTimeMillis()
        if (cached != null && now - at in 0 until REFRESH_MS) return parse(cached)
        val fresh = try { download() } catch (_: Exception) { null }
        if (fresh != null) {
            prefs.edit().putString(KEY, fresh).putLong(KEY_AT, now).apply()
            return parse(fresh)
        }
        Report.logOnce("recipes-fail", "List: could not refresh recipes; using ${if (cached != null) "the saved copy" else "none"}")
        return if (cached != null) parse(cached) else emptyList()
    }

    private fun download(): String? {
        val c = URL(URL).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 5000
            c.readTimeout = 5000
            c.instanceFollowRedirects = true
            if (c.responseCode != 200) return null
            val bytes = c.inputStream.use { it.readNBytesLimited(MAX_BYTES) } ?: return null
            val text = String(bytes, Charsets.UTF_8)
            // only keep text that is valid JSON with a "sites" list
            val root = MiniJson.parse(text) as? Map<*, *> ?: return null
            return if (root["sites"] is List<*>) text else null
        } finally {
            c.disconnect()
        }
    }

    private fun java.io.InputStream.readNBytesLimited(max: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > max) return null
        }
        return out.toByteArray()
    }
}
