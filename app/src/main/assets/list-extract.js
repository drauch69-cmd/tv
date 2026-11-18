/*
 * StreamLink "stream lists": reads the events off an index page.
 *
 * This file is ONE function expression. IndexReader.kt evaluates it as
 *     (<this file>)(<recipe JSON string or null>)
 * in the top frame of the loaded index page and gets a JSON string back:
 *   { pageTitle, pageUrl, method: "automatic"|"recipe", debug, noLink,
 *     textLen, loading, frames: [{ src, w, h, pct, same, vis }], clickRows,
 *     items: [{ title, subtitle, url, timeText, datetime, section, liveHint, imageUrl }] }
 * Rows that are events but have no URL anywhere in the DOM (JS click handlers) get
 *     url = <page url> + "#sl-click=" + encodeURIComponent(JSON {t: title, i: index, s: row selector, h: page hash if any})
 * which StreamSniffer replays with assets/click-row.js.
 * Second argument: JSON array of "learned" row selectors for this site (from earlier good reads), or null.
 * The result also carries "shape": the row selector of the list found here, for the app to remember.
 * Same-origin iframes are scanned as extra roots; all iframes are reported in "frames" so the
 * Kotlin side can follow a big (cross-origin) one.
 * Dates are NOT parsed here (Kotlin DateGuess does that); "datetime" is only a machine-readable
 * value found next to the text (<time datetime>, data-start ...), passed through for DateGuess.
 *
 * How the automatic mode works, in short:
 *  1. Find "groups": elements of the same tag and similar classes that are siblings (rows of a
 *     list). Groups whose parents are same-looking siblings are merged (a list split by day
 *     headings is still one group).
 *  2. Score each group: time text in the rows and a different same-site link per row are good;
 *     living in nav/header/footer/aside, very short text, "18"-style counts, and rows with
 *     neither a time nor their own link are bad.
 *  3. Take the best group (plus equally good ones), read each row: title (most prominent line),
 *     subtitle (next line), time line, link, section heading above it, live hint, image.
 */
(function (recipeJson, learnedJson) {
  'use strict';
  var debug = [];
  // Pages using content-visibility:auto leave off-screen rows unrendered, and their innerText is then empty
  // (in the TV's hidden browser that can be every row). Render everything while reading.
  var cvStyle = null;
  try {
    cvStyle = document.createElement('style');
    cvStyle.textContent = '*{content-visibility:visible !important}';
    (document.head || document.documentElement).appendChild(cvStyle);
  } catch (e) { cvStyle = null; }
  try {
    return run();
  } catch (e) {
    return JSON.stringify({
      pageTitle: safeTitle(), pageUrl: String(location.href), method: 'automatic', items: [], noLink: 0,
      debug: 'extractor error: ' + (e && e.message ? e.message : e), error: 'extractor error'
    });
  } finally {
    try { if (cvStyle && cvStyle.parentNode) cvStyle.parentNode.removeChild(cvStyle); } catch (e) { }
  }

  function safeTitle() { try { return String(document.title || '').trim(); } catch (e) { return ''; } }

  function run() {
    var PAGE = location.href;
    var MIN_SCORE = 25;
    var MAX_ITEMS = 300;

    // ---------------------------------------------------------------- small helpers
    var TWO_PART = { 'co.uk': 1, 'com.au': 1, 'co.nz': 1, 'com.br': 1, 'co.jp': 1, 'co.in': 1, 'com.mx': 1 };
    function baseDomain(h) {
      var p = String(h || '').toLowerCase().replace(/\.$/, '').split('.');
      if (p.length <= 2) return p.join('.');
      var l2 = p.slice(-2).join('.');
      return TWO_PART[l2] ? p.slice(-3).join('.') : l2;
    }
    function sameSite(host) { return baseDomain(host) === baseDomain(location.hostname); }

    function normUrl(u) {
      try { var x = new URL(u, document.baseURI); x.hash = ''; return x.href.replace(/\/+$/, ''); } catch (e) { return ''; }
    }
    var PAGE_N = normUrl(PAGE);

    // Documents to read: the top one, then same-origin iframes (visible ones, two levels deep).
    var docs = [document];
    function collectDocs(doc, depth) {
      if (depth > 2) return;
      var fs = doc.querySelectorAll('iframe');
      for (var i = 0; i < fs.length && docs.length < 8; i++) {
        try {
          var f = fs[i], d = f.contentDocument;
          if (!d || !d.body) continue;
          var r = f.getBoundingClientRect();
          if (r.width < 50 || r.height < 50) continue;
          docs.push(d);
          collectDocs(d, depth + 1);
        } catch (e) { /* cross-origin */ }
      }
    }
    try { collectDocs(document, 0); } catch (e) { }
    function baseOf(el) { try { return el.ownerDocument.baseURI || document.baseURI; } catch (e) { return document.baseURI; } }

    function txt(el) {
      var t = el.innerText;
      if (t == null) t = el.textContent;
      return String(t || '').replace(/ /g, ' ').replace(/[ \t\r\f\v]+/g, ' ').replace(/ ?\n ?/g, '\n').trim();
    }
    function oneLine(s) { return String(s || '').replace(/\s+/g, ' ').trim(); }

    var uidCounter = 0;
    function uid(el) { if (!el.__lxid) el.__lxid = ++uidCounter; return el.__lxid; }

    var layoutOk = false;
    try { layoutOk = document.documentElement.clientWidth > 0; } catch (e) { layoutOk = false; }
    function visible(el) {
      if (el.hidden) return false;
      var cs = getComputedStyle(el);
      if (cs.display === 'none' || cs.visibility === 'hidden') return false;
      if (layoutOk && cs.display !== 'contents' && !el.getClientRects().length) return false;
      return true;
    }

    // ---------------------------------------------------------------- date / time look-alikes
    // (only to recognise "this text is a date/time"; Kotlin does the real parsing)
    var MONTHS = '(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sept?(?:ember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)';
    var RE_MONTH_DAY = new RegExp('\\b' + MONTHS + '\\.?\\s+\\d{1,2}(?!\\d)', 'i');
    var RE_DAY_MONTH = new RegExp('\\b\\d{1,2}(?:st|nd|rd|th)?\\s+(?:of\\s+)?' + MONTHS + '\\b', 'i');
    var RE_NUM_DATE = /\b\d{1,4}[\/.\-]\d{1,2}[\/.\-]\d{1,4}\b/;
    var RE_TIME12 = /\b(?:1[0-2]|0?[1-9])(?::[0-5]\d)?\s*[ap]\.?m\.?(?![a-z])/i;
    var RE_TIME24 = /\b(?:[01]?\d|2[0-3]):[0-5]\d\b/;
    var RE_REL_DAY = /\b(?:today|tonight|tomorrow)\b/i;
    // "in 2 hours", "in an hour", and compact countdowns: "in 1d 19h", "starts in 2h 05m", "in 45m", "in 3d"
    var RE_REL_IN = /\bin\s+(?:about\s+)?(?:\d+\s*d(?:ays?)?\b(?:\s*\d+\s*h(?:ours?|rs?)?\b)?(?:\s*\d+\s*m(?:in(?:ute)?s?)?\b)?|\d+\s*h(?:ours?|rs?)?\b(?:\s*\d+\s*m(?:in(?:ute)?s?)?\b)?|\d+\s*m(?:in(?:ute)?s?)?\b|(?:an?|half an)\s*(?:hours?|hrs?|h|min(?:ute)?s?|m)\b)/i;
    var RE_IN_WORD = /(?:^|\s)in$/i;                                   // "STARTS IN" (the count is in the next line)
    var RE_COMPACT_ONLY = /^\d+\s*[dhm]\b(?:\s*\d+\s*[hm]\b)*$/i;     // "1d 19h", "2h 05m", "45m"
    var RE_COUNTDOWN = /^\d+\s*[dhms]\b(?:\s*\d+\s*[hms]\b)*$|^\d{1,2}(?::\d{2}){1,2}$/i;
    var RE_SEPARATOR = /^(vs\.?|v\.?|versus|-|\u2013|\u2014|@|at|x)$/i;
    var RE_SCORE = /^\d{1,3}(?:\s*[:\-\u2013]\s*\d{1,3})?$/;
    var RE_NOISE = new RegExp(
      '\\b' + MONTHS + '\\b\\.?|\\b\\d{1,4}[\\/.\\-]\\d{1,2}[\\/.\\-]\\d{1,4}\\b|\\b(?:mon|tue|tues|wed|thu|thur|thurs|fri|sat|sun)[a-z]*\\b\\.?|' +
      '\\b(?:[01]?\\d|2[0-3]):[0-5]\\d(?::[0-5]\\d)?\\b|\\b\\d{1,2}(?::\\d{2})?\\s*[ap]\\.?m\\.?(?![a-z])|\\b\\d{1,4}(?:st|nd|rd|th)?\\b|' +
      '\\b(?:today|tonight|tomorrow|start|starts|starting|time|date|when|at|on|from|to|until|till|in|about|of|utc|gmt|[a-z]{1,2}t)\\b|[T:,.\\-\\u2013\\u2022\\u00b7|/@()]', 'gi');

    function kinds(t) {
      var date = RE_MONTH_DAY.test(t) || RE_DAY_MONTH.test(t) || RE_NUM_DATE.test(t) || RE_REL_DAY.test(t);
      var time = RE_TIME12.test(t) || RE_TIME24.test(t) || RE_REL_IN.test(t);
      return { date: date, time: time };
    }
    /** How much of this line is something other than date/time words (a title that merely contains a time is not a time line). */
    function remainder(t) { return oneLine(t.replace(RE_NOISE, ' ')).length; }
    function timeLineRank(t) {
      if (t.length > 80) return 0;
      var k = kinds(t);
      if (!k.date && !k.time) return 0;
      if (RE_REL_DAY.test(t) && !k.time && !RE_MONTH_DAY.test(t) && t.length > 40) return 0;
      if (remainder(t) > 20) return 0;
      return k.date && k.time ? 3 : (k.time ? 2 : 1);
    }
    function extractSub(t) {
      var res = [RE_MONTH_DAY, RE_DAY_MONTH, RE_NUM_DATE, RE_TIME12, RE_TIME24, RE_REL_DAY, RE_REL_IN];
      var lo = -1, hi = -1;
      for (var i = 0; i < res.length; i++) {
        var m = res[i].exec(t);
        if (!m) continue;
        if (lo < 0 || m.index < lo) lo = m.index;
        if (m.index + m[0].length > hi) hi = m.index + m[0].length;
      }
      if (lo < 0) return '';
      return t.substring(lo, Math.min(hi, lo + 60)).trim();
    }

    var EPOCH_RE = /^\d{10}(\d{3})?$/;
    function dtValue(v) {
      v = String(v || '').trim();
      if (!v) return '';
      if (EPOCH_RE.test(v)) { var n = Number(v); if (v.length === 10) n *= 1000; try { return new Date(n).toISOString(); } catch (e) { return ''; } }
      if (/^\d{4}-\d{2}-\d{2}/.test(v)) return v;
      return '';
    }
    var DATA_ATTRS = ['datetime', 'data-datetime', 'data-start', 'data-start-time', 'data-starttime', 'data-timestamp', 'data-time', 'data-date', 'data-event-start'];
    function dtAttr(el) {
      for (var i = 0; i < DATA_ATTRS.length; i++) {
        var v = dtValue(el.getAttribute(DATA_ATTRS[i]));
        if (v) return v;
      }
      return '';
    }

    // ---------------------------------------------------------------- links
    var DATA_LINK_ATTRS = ['data-href', 'data-url', 'data-link', 'data-target-url', 'data-event-url', 'data-permalink'];
    var RE_ONCLICK_URL = /(?:location(?:\.href)?|window\.open|location\.assign|location\.replace)\s*(?:=|\()\s*['"]([^'"]+)['"]/;

    /** Absolute URL if acceptable as an event link, else ''. strict = must be on the same site. */
    function okLink(href, strict, base) {
      if (!href) return '';
      var h = String(href).trim();
      if (!h || h.charAt(0) === '#' || /^(javascript|mailto|tel|sms|data|blob|about|intent|market):/i.test(h)) return '';
      var x;
      try { x = new URL(h, base || document.baseURI); } catch (e) { return ''; }
      if (x.protocol !== 'http:' && x.protocol !== 'https:') return '';
      if (strict && !sameSite(x.hostname)) return '';
      if (normUrl(x.href) === PAGE_N) return '';
      return x.href;
    }
    function linkFromAttrs(el, strict) {
      for (var i = 0; i < DATA_LINK_ATTRS.length; i++) {
        var u = okLink(el.getAttribute(DATA_LINK_ATTRS[i]), strict, baseOf(el));
        if (u) return u;
      }
      var oc = el.getAttribute && el.getAttribute('onclick');
      if (oc) { var m = RE_ONCLICK_URL.exec(oc); if (m) { var u2 = okLink(m[1], strict, baseOf(el)); if (u2) return u2; } }
      return '';
    }
    /** The item's own link: itself if an <a>, first usable <a> inside, (optionally the <a> around it), data-href/onclick. */
    function linkOf(el, strict, allowAncestor) {
      var u, base = baseOf(el);
      if (el.tagName === 'A') { u = okLink(el.getAttribute('href'), strict, base); if (u) return u; }
      var as = el.querySelectorAll('a[href]');
      for (var i = 0; i < as.length; i++) { u = okLink(as[i].getAttribute('href'), strict, base); if (u) return u; }
      u = linkFromAttrs(el, strict); if (u) return u;
      var ds = el.querySelectorAll('[data-href],[data-url],[data-link],[data-target-url],[data-event-url],[data-permalink],[onclick]');
      for (var j = 0; j < ds.length && j < 40; j++) { u = linkFromAttrs(ds[j], strict); if (u) return u; }
      if (allowAncestor) {
        var anc = el.parentElement && el.parentElement.closest('a[href]');
        if (anc) { u = okLink(anc.getAttribute('href'), strict, base); if (u) return u; }
      }
      return '';
    }

    // ---------------------------------------------------------------- groups of similar siblings
    var SKIP_PARENT = { SCRIPT: 1, STYLE: 1, SVG: 1, SELECT: 1, NOSCRIPT: 1, TEMPLATE: 1, HEAD: 1, TEXTAREA: 1, OPTION: 1 };
    var SKIP_ITEM = { SCRIPT: 1, STYLE: 1, LINK: 1, META: 1, NOSCRIPT: 1, TEMPLATE: 1, SVG: 1, BR: 1, HR: 1, OPTION: 1, OPTGROUP: 1,
      INPUT: 1, SELECT: 1, TEXTAREA: 1, IMG: 1, CANVAS: 1, IFRAME: 1, HEAD: 1, TITLE: 1, PATH: 1 };
    var RE_STATE_TOKEN = /^(active|selected|current|open|opened|closed|first|last|odd|even|hover|focus|focused|show|shown|visible|hidden|loaded|live|new|featured|highlight|highlighted|past|upcoming|ended|soon|ng-.*|is-.*|has-.*|js-.*)$/i;
    function tokens(el) {
      var out = [], mods = [], cl = el.classList;
      for (var i = 0; i < cl.length; i++) {
        if (RE_STATE_TOKEN.test(cl[i])) continue;
        if (cl[i].indexOf('--') > 0) mods.push(cl[i]); else out.push(cl[i]); // "card--live" is a variant, not a different row shape
      }
      return out.length ? out : mods;
    }
    function jacc(a, b) {
      if (!a.length && !b.length) return 1;
      var s = {}, i, inter = 0, un = a.length;
      for (i = 0; i < a.length; i++) s[a[i]] = 1;
      for (i = 0; i < b.length; i++) { if (s[b[i]]) inter++; else un++; }
      return inter / un;
    }
    function sig(el) { return el.tagName + '.' + tokens(el).sort().join('.'); }
    function describe(el) {
      if (!el) return '?';
      var s = el.tagName.toLowerCase();
      if (el.id) s += '#' + el.id;
      var t = tokens(el).slice(0, 2);
      if (t.length) s += '.' + t.join('.');
      return s.length > 50 ? s.substring(0, 50) : s;
    }
    function docOrder(a, b) {
      if (a === b) return 0;
      if (a.ownerDocument !== b.ownerDocument) return docs.indexOf(a.ownerDocument) - docs.indexOf(b.ownerDocument);
      return (a.compareDocumentPosition(b) & 4) ? -1 : 1;
    }

    function buildGroups() {
      var map = {}, list = [], budget = 15000;
      for (var di = 0; di < docs.length; di++) {
      var body = docs[di].body;
      if (!body) continue;
      var all = body.getElementsByTagName('*');
      var limit = Math.min(all.length, budget);
      budget -= limit;
      for (var i = -1; i < limit; i++) {
        var p = i < 0 ? body : all[i];
        if (SKIP_PARENT[p.tagName.toUpperCase()]) continue;
        var kids = p.children;
        if (!kids.length) continue;
        var clusters = [];
        for (var k = 0; k < kids.length; k++) {
          var c = kids[k];
          if (SKIP_ITEM[c.tagName.toUpperCase()]) continue;
          var tc = c.textContent;
          if (!tc || tc.length > 4000 || tc.replace(/\s+/g, '').length < 2) continue;
          if (!visible(c)) continue;
          var tk = tokens(c), placed = false;
          for (var j = 0; j < clusters.length; j++) {
            if (clusters[j].tag === c.tagName && jacc(clusters[j].tk, tk) >= 0.5) { clusters[j].items.push(c); placed = true; break; }
          }
          if (!placed) clusters.push({ tag: c.tagName, tk: tk, items: [c] });
        }
        if (!clusters.length) continue;
        var psig = sig(p);
        var gpe = p.parentElement;
        var gp = gpe ? (gpe.parentElement ? uid(gpe.parentElement) : 0) + ':' + sig(gpe) : 0; // sections with distinct ids still merge
        for (var m = 0; m < clusters.length; m++) {
          var cl = clusters[m];
          var key = gp + '|' + psig + '|' + cl.tag + '.' + cl.tk.slice().sort().join('.');
          var g = map[key];
          if (!g) { g = map[key] = { items: [], parents: [] }; list.push(g); }
          g.parents.push(p);
          for (var q = 0; q < cl.items.length; q++) g.items.push(cl.items[q]);
        }
      }
      }
      var out = [];
      for (var x = 0; x < list.length; x++) {
        var items = list[x].items;
        items.sort(docOrder);
        var uniq = [];
        for (var y = 0; y < items.length; y++) if (!y || items[y] !== items[y - 1]) uniq.push(items[y]);
        if (uniq.length >= 2) out.push({ items: uniq, parents: list[x].parents });
      }
      return out;
    }

    var RE_CHROME_CLASS = /(^|[\s_-])(nav|navbar|navigation|menu|topbar|footer|sidebar|breadcrumbs?|pagination|pager|chips?|filters?|toolbar|dropdown|cookie|consent|banner|ads?|advert|sponsor|sponsored|promo)([\s_-]|$)/i;
    function chromeOf(el) {
      for (var e = el; e && e !== document.body && e !== document.documentElement; e = e.parentElement) {
        var tg = e.tagName;
        if (tg === 'NAV' || tg === 'ASIDE') return tg.toLowerCase();
        if ((tg === 'HEADER' || tg === 'FOOTER') && !(e.parentElement && e.parentElement.closest('main,article,section'))) return tg.toLowerCase();
        var r = (e.getAttribute('role') || '').toLowerCase();
        if (r === 'navigation' || r === 'banner' || r === 'contentinfo' || r === 'complementary' || r === 'menu' || r === 'menubar' || r === 'tablist' || r === 'toolbar') return 'role=' + r;
        var c = (typeof e.className === 'string' ? e.className : '') + ' ' + (e.id || '');
        if (RE_CHROME_CLASS.test(c)) return 'class';
      }
      return '';
    }
    function inMain(el) { return !!el.closest('main,[role=main]'); }

    /** Cheap text lines of a row for scoring: table cells (tab-separated in innerText) are separate lines. */
    function rowLines(el) {
      var t = el.innerText;
      if (t == null) t = el.textContent;
      return String(t || '').split(/[\n\t]+/).map(oneLine).filter(function (l) { return l; });
    }
    /** A link inside running text (an <a> in a paragraph, or a paragraph/list item that is mostly prose around its link). */
    function proseLink(el) {
      var host = (/^(P|LI|DD|BLOCKQUOTE)$/.test(el.tagName) ? el : (el.tagName === 'A' ? el.parentElement && el.parentElement.closest('p,li,dd,blockquote') : null));
      if (!host) return false;
      var a = el.tagName === 'A' ? el : el.querySelector('a[href]');
      if (!a) return false;
      var hl = oneLine(txt(host)).length, al = oneLine(txt(a)).length;
      return hl > al * 3 + 40;
    }
    /** True if the rows sit under a date heading (so a short list without clock times is still an agenda). */
    function datedSection(g) {
      try {
        var set = {};
        g.items.forEach(function (el) { set[uid(el)] = 1; });
        var h = findSection(g.items[0], set);
        return !!h && kinds(h).date;
      } catch (e) { return false; }
    }
    function scoreGroup(g) {
      var items = g.items, n = items.length;
      var sample = items.length > 60 ? items.slice(0, 60) : items;
      var timed = 0, linked = 0, urls = {}, nUrls = 0, lenSum = 0, linesSum = 0, counts = 0, prose = 0, forms = 0;
      for (var i = 0; i < sample.length; i++) {
        var t = txt(sample[i]);
        var lines = rowLines(sample[i]);
        var hasTime = false;
        for (var j = 0; j < lines.length; j++) if (timeLineRank(lines[j]) > 0) { hasTime = true; break; }
        // a "live" marker counts like a time (rows of a live section have no clock)
        if (!hasTime && (dtAttr(sample[i]) || sample[i].querySelector('time[datetime]') || liveHintOf(sample[i], lines.map(function (x) { return { text: x }; }), -1))) hasTime = true;
        if (hasTime) timed++;
        if (proseLink(sample[i])) prose++;
        if (sample[i].querySelector('select,input,textarea')) forms++;
        var u = linkOf(sample[i], true, false);
        if (u) { linked++; var nu = normUrl(u); if (!urls[nu]) { urls[nu] = 1; nUrls++; } }
        var ol = oneLine(t);
        lenSum += ol.length; linesSum += lines.length;
        if (/\s\d{1,4}$/.test(ol)) counts++;
      }
      var sn = sample.length;
      g.n = n;
      g.timeFrac = timed / sn;
      g.linkFrac = linked / sn;
      g.uniqFrac = nUrls / sn;
      g.avgLen = lenSum / sn;
      g.chrome = chromeOf(items[0]);
      var s = 0;
      s += g.timeFrac * 50;
      s += g.linkFrac * 10 + g.uniqFrac * 30;
      s += Math.min(n, 25) * 0.8;
      if (linesSum / sn >= 2) s += 5;
      if (g.chrome) s -= 60;
      if (inMain(items[0])) s += 10;
      if (g.timeFrac < 0.25 && g.uniqFrac < 0.5) s -= 40;   // feature cards: no time, no link of their own
      if (g.avgLen < 14 && g.timeFrac < 0.3) s -= 35;       // menus, chips: very short text
      if (counts / sn >= 0.6 && g.avgLen < 30) s -= 30;     // "Workshops 18"
      if (g.avgLen > 600) s -= 30;
      if (/^T[DH]$/.test(items[0].tagName)) s -= 45;          // table cells: the row is the <tr>, never a single cell
      if (prose / sn >= 0.5) s -= 60;                        // links inside running text are not rows
      if (g.timeFrac === 0 && (prose / sn >= 0.25 || (n < 4 && !datedSection(g)))) s -= 40; // no time, no live marker: must be clearly list-like
      if (forms / sn >= 0.5) s -= 50;                        // rows holding form controls (a section header with a filter)
      if (items[0].tagName === 'HEADER' && g.timeFrac < 0.5) s -= 40; // section headers
      g.score = s;
      g.raw = s;
      return g;
    }

    function related(a, b) {
      var A = a.items.slice(0, 40), B = b.items.slice(0, 40);
      for (var i = 0; i < A.length; i++) for (var j = 0; j < B.length; j++) if (A[i].contains(B[j]) || B[j].contains(A[i])) return true;
      return false;
    }
    function outerOf(a, b) { // true if a's items contain b's items
      var b0 = b.items[0];
      for (var i = 0; i < a.items.length; i++) if (a.items[i] !== b0 && a.items[i].contains(b0)) return true;
      return false;
    }
    /** A group of "day" wrappers: each starts with a date heading followed by the real rows. */
    function wrapperLike(g) {
      var hit = 0, tot = Math.min(g.items.length, 10);
      for (var i = 0; i < tot; i++) {
        var f = g.items[i].firstElementChild;
        if (f && g.items[i].children.length >= 2 && isDateHeader(f)) hit++;
      }
      return tot > 0 && hit / tot >= 0.6;
    }

    // ---------------------------------------------------------------- headings / sections
    function isHeadingEl(el) { return /^H[1-6]$/.test(el.tagName) || el.getAttribute('role') === 'heading'; }
    function isDateHeader(el) {
      if (el.querySelector('a[href]') || el.tagName === 'A') return false;
      var t = oneLine(txt(el));
      if (!t || t.length > 80) return false;
      var k = kinds(t);
      return k.date && !k.time && !RE_REL_IN.test(t) && remainder(t) <= 20;
    }
    function headingText(el) { return oneLine(txt(el)).substring(0, 120); }
    function findSection(item, itemSet) {
      var cur = item, steps = 0;
      for (var level = 0; level < 6 && cur && cur !== document.body; level++) {
        for (var sib = cur.previousElementSibling; sib; sib = sib.previousElementSibling) {
          if (itemSet[uid(sib)]) continue;
          steps++;
          if (!txt(sib)) continue;
          if (isDateHeader(sib)) return headingText(sib);
          var hd = isHeadingEl(sib) ? sib : sib.querySelector('h1,h2,h3,h4,h5,h6,[role=heading]');
          // a section heading may itself be a link ("Soccer" -> its page), if every link in the block sits inside the heading
          var hdLinks = hd ? hd.querySelectorAll('a[href]').length : 0;
          if (hd && (!sib.querySelector('a[href]') || (hdLinks > 0 && hdLinks <= 2 && sib.querySelectorAll('a[href]').length === hdLinks))) {
            var ht = headingText(hd);
            var dated = kinds(ht).date;
            // plain (non-date) headings count only when close to the list, and not the page's h1
            if (dated || (level <= 1 && steps <= 2 && hd.tagName !== 'H1')) return ht;
          }
        }
        cur = cur.parentElement;
      }
      return '';
    }

    // ---------------------------------------------------------------- reading one item
    var INLINE_DISPLAY = { inline: 1, 'inline-block': 1, contents: 1, ruby: 1 };
    function hasText(el) { return !!(el.textContent && el.textContent.trim()); }
    function allInline(el) {
      for (var c = el.firstElementChild; c; c = c.nextElementSibling) {
        if (!hasText(c)) continue;
        var cs = getComputedStyle(c);
        if (cs.display === 'none') continue;
        if (!INLINE_DISPLAY[cs.display] || !allInline(c)) return false;
      }
      return true;
    }
    function styleEl(el) { // descend while there is exactly one text-bearing child element and no text of our own
      for (var guard = 0; guard < 6; guard++) {
        var only = null, count = 0, ownText = false;
        for (var n = el.firstChild; n; n = n.nextSibling) {
          if (n.nodeType === 3 && n.nodeValue.trim()) ownText = true;
          else if (n.nodeType === 1 && hasText(n)) { count++; only = n; }
        }
        if (ownText || count !== 1) break;
        el = only;
      }
      return el;
    }
    var SKIP_TEXT = { SCRIPT: 1, STYLE: 1, SVG: 1, NOSCRIPT: 1, TEMPLATE: 1, SELECT: 1, OPTION: 1, TEXTAREA: 1 };
    var RE_HASALNUM = /[A-Za-z0-9À-￿]/;
    function collectLines(item) {
      var out = [];
      function push(text, el) {
        var parts = text.split('\n');
        for (var i = 0; i < parts.length; i++) {
          var t = oneLine(parts[i]);
          if (t && RE_HASALNUM.test(t)) out.push({ text: t, el: el });
        }
      }
      (function walk(el) {
        if (SKIP_TEXT[el.tagName.toUpperCase()] || !visible(el)) return;
        var t = txt(el);
        if (!t) return;
        if (allInline(el)) { push(t, el); return; }
        for (var n = el.firstChild; n; n = n.nextSibling) {
          if (n.nodeType === 3) { var v = n.nodeValue; if (v && v.trim()) push(v, el); }
          else if (n.nodeType === 1) walk(n);
        }
      })(item);
      for (var i = 0; i < out.length; i++) {
        var se = styleEl(out[i].el), cs = getComputedStyle(se);
        out[i].fs = parseFloat(cs.fontSize) || 14;
        var fw = parseInt(cs.fontWeight, 10);
        if (isNaN(fw)) fw = cs.fontWeight === 'bold' ? 700 : 400;
        if (/^(STRONG|B|H[1-6])$/.test(se.tagName)) fw = Math.max(fw, 700);
        out[i].fw = fw;
        var hd = out[i].el.closest('h1,h2,h3,h4,h5,h6,[role=heading]');
        out[i].heading = !!(hd && item.contains(hd));
        var cls = '', e = se;
        for (var d = 0; d < 3 && e && e !== item.parentElement; d++, e = e.parentElement) cls += ' ' + (typeof e.className === 'string' ? e.className : '');
        out[i].cls = cls;
        var bt = false;
        for (var b = out[i].el; b && b !== item && !bt; b = b.parentElement) {
          if (b.tagName === 'BUTTON' || (b.getAttribute && b.getAttribute('role') === 'button')) bt = true;
          else if (typeof b.className === 'string' && RE_BTN_CLASS.test(b.className)) bt = true;
        }
        out[i].btn = bt; // text of a button ("Live Streams", "Watch"): not a title, not a live marker
      }
      return out;
    }
    var RE_BTN_CLASS = /(^|[\s_-])(btn|button|cta)([\s_-]|$)/i;
    var linesCache = {};
    function linesOf(item) { var k = uid(item); return linesCache[k] || (linesCache[k] = collectLines(item)); }
    var labelMap = {};
    var RE_LETTER = null;
    try { RE_LETTER = new RegExp('\\p{L}', 'u'); } catch (e) { RE_LETTER = /[A-Za-z\u00C0-\u024F\u0370-\u1FFF\u3040-\uD7FF]/; }
    /** Lines whose text is the same in (nearly) every row of a group of 3+ rows are labels (league name, "Watch"), never titles. */
    function computeLabels(all, selOf) {
      var by = {};
      all.forEach(function (el) { var k = selOf[uid(el)] || ''; (by[k] = by[k] || []).push(el); });
      Object.keys(by).forEach(function (k) {
        var rows = by[k];
        if (rows.length < 3) return;
        var sample = rows.slice(0, 60), cnt = {}, n = 0;
        sample.forEach(function (r) {
          var ls = linesOf(r);
          if (ls.length < 2) return;
          n++;
          var seen = {};
          ls.forEach(function (L) { var key = L.text.toLowerCase(); if (!seen[key]) { seen[key] = 1; cnt[key] = (cnt[key] || 0) + 1; } });
        });
        if (n < 3) return;
        var need = Math.max(3, Math.ceil(n * 0.85)), set = {}, any = false;
        Object.keys(cnt).forEach(function (key) { if (cnt[key] >= need) { set[key] = 1; any = true; } });
        if (any) rows.forEach(function (r) { labelMap[uid(r)] = set; });
      });
    }

    var RE_BADGE = /^(live|live now|on air|on-air|new|free|sold out|full|register|details|view|view all|more|read more|learn more|book now|get tickets|tickets|rsvp|today|tomorrow|tonight|upcoming|featured|popular)$/i;
    function isBadge(t) { return RE_BADGE.test(t.replace(/^[^A-Za-z0-9]+|[^A-Za-z0-9]+$/g, '')); }
    var RE_TITLE_CLASS = /(^|[\s_-])(title|name|heading|headline|summary)([\s_-]|$)/i;

    function pickTitle(lines, skip, labels, loose) {
      var best = -1e9, bi = -1;
      for (var i = 0; i < lines.length; i++) {
        if (skip[i]) continue;
        var L = lines[i], t = L.text;
        if (t.length < 2) continue;
        if (!loose && (!RE_LETTER.test(t) || (labels && labels[t.toLowerCase()]))) continue; // an icon, or a repeated label
        var s = 0;
        if (L.btn) s -= 150;
        if (RE_COUNTDOWN.test(t)) s -= 80;
        if (L.heading) s += 100;
        if (RE_TITLE_CLASS.test(L.cls)) s += 40;
        s += L.fs * 2;
        if (L.fw >= 600) s += 20;
        if (isBadge(t)) s -= 200;
        var k = kinds(t);
        if (k.date || k.time) s -= 50;
        if (/^[\d\s,.:%+\-]+$/.test(t)) s -= 100;
        s -= i * 0.5;
        if (s > best) { best = s; bi = i; }
      }
      return bi;
    }

    function findTime(item, lines, ov) {
      var res = { text: '', datetime: '', idx: {} };
      var tEl = ov && ov.time ? ov.time : item.querySelector('time');
      if (tEl) {
        res.datetime = dtAttr(tEl);
        var tt = oneLine(txt(tEl));
        if (tt) {
          res.text = tt;
          for (var i = 0; i < lines.length; i++) if (tEl.contains(lines[i].el) || (lines[i].el.contains(tEl) && lines[i].text === tt)) res.idx[i] = 1;
        }
      }
      if (!res.text && !(ov && ov.time)) { // "STARTS IN" and "1d 19h" in two lines: one time text
        for (var w = 0; w + 1 < lines.length; w++) {
          if (lines[w].text.length <= 16 && RE_IN_WORD.test(lines[w].text) && RE_COMPACT_ONLY.test(lines[w + 1].text)) {
            res.text = lines[w].text + ' ' + lines[w + 1].text; res.idx[w] = 1; res.idx[w + 1] = 1; break;
          }
        }
      }
      if (!res.text && !(ov && ov.time)) {
        var bi = -1, br = 0, i2;
        for (i2 = 0; i2 < lines.length; i2++) {
          var r = timeLineRank(lines[i2].text);
          if (r > br) { br = r; bi = i2; }
        }
        if (bi >= 0) {
          res.text = lines[bi].text; res.idx[bi] = 1;
          if (br < 3) { // "Sat, May 24" and "9:00 AM" in two neighbouring lines: join them
            var want = br === 1 ? 2 : 1;
            var nb = [bi - 1, bi + 1];
            for (var q = 0; q < nb.length; q++) {
              var j = nb[q];
              if (j >= 0 && j < lines.length && timeLineRank(lines[j].text) === want) {
                res.text = (j < bi ? lines[j].text + ' ' + lines[bi].text : lines[bi].text + ' ' + lines[j].text);
                res.idx[j] = 1; break;
              }
            }
          }
        }
      }
      if (!res.datetime) {
        res.datetime = dtAttr(item);
        if (!res.datetime) {
          var ds = item.querySelectorAll('[data-datetime],[data-start],[data-start-time],[data-starttime],[data-timestamp],[data-time],[data-date],[data-event-start]');
          for (var d = 0; d < ds.length && d < 20 && !res.datetime; d++) res.datetime = dtAttr(ds[d]);
        }
      }
      return res;
    }

    var RE_LIVE = /(^|[^a-z0-9])(live|on[\s-]?air|streaming[\s-]now|now[\s-]streaming)([^a-z0-9]|$)/i;
    var RE_LIVE_TITLE = /^[^A-Za-z0-9]*(live|on[\s-]?air)\s*(?:[:\-–—|•]|$)/i;
    function liveHintOf(item, lines, titleIdx, timeIdx) {
      if (titleIdx >= 0 && RE_LIVE_TITLE.test(lines[titleIdx].text)) return true;
      for (var i = 0; i < lines.length; i++) {
        if (i === titleIdx) continue;
        if (lines[i].btn) continue;
        if (lines[i].text.length <= 24 && RE_LIVE.test(lines[i].text)) return true;
      }
      var els = [item], more = item.querySelectorAll('*');
      for (var j = 0; j < more.length && j < 80; j++) els.push(more[j]);
      for (var k = 0; k < els.length; k++) {
        var el = els[k];
        var cls = typeof el.className === 'string' ? el.className : (el.getAttribute && el.getAttribute('class')) || '';
        if (cls && RE_LIVE.test(cls)) return true;
        var a = (el.getAttribute('aria-label') || '') + ' ' + (el.getAttribute('data-status') || '') + ' ' + (el.getAttribute('data-state') || '');
        if (a.trim() && RE_LIVE.test(a)) return true;
        var dl = (el.getAttribute('data-live') || '').toLowerCase();
        if (dl === 'true' || dl === '1') return true;
      }
      return false;
    }

    function imageOf(item) {
      var imgs = item.querySelectorAll('img');
      for (var i = 0; i < imgs.length; i++) {
        var im = imgs[i];
        var src = im.currentSrc || im.getAttribute('src') || im.getAttribute('data-src') || im.getAttribute('data-lazy-src') || '';
        if (!src || /^data:/i.test(src)) continue;
        if (layoutOk) { var r = im.getBoundingClientRect(); if (r.width > 0 && r.width < 20 && r.height < 20) continue; }
        var u = okAbs(src, baseOf(item));
        if (u) return u;
      }
      var els = [item], more = item.querySelectorAll('*');
      for (var j = 0; j < more.length && j < 30; j++) els.push(more[j]);
      for (var k = 0; k < els.length; k++) {
        var bg = getComputedStyle(els[k]).backgroundImage;
        if (bg && bg !== 'none') {
          var m = /url\((['"]?)(.*?)\1\)/.exec(bg);
          if (m && m[2] && !/^data:/i.test(m[2])) { var u2 = okAbs(m[2], baseOf(item)); if (u2) return u2; }
        }
      }
      return null;
    }
    function okAbs(u, base) {
      try { var x = new URL(u, base || document.baseURI); if (x.protocol === 'http:' || x.protocol === 'https:') return x.href.substring(0, 500); } catch (e) { }
      return '';
    }

    function sepText(t) { return /^(vs\.?|v\.?|versus)$/i.test(t) ? t.toLowerCase() : t; }
    function sameStyle(a, b) { return Math.abs(a.fs - b.fs) < 0.05 && Math.abs(a.fw - b.fw) < 100; }
    function isTimeish(t) { return timeLineRank(t) > 0 || RE_COUNTDOWN.test(t) || RE_REL_IN.test(t); }
    /** A line that can be one side of a matchup: has letters, is not a separator, score, time, badge or button. */
    function nameLike(L, labels) {
      var t = L.text;
      return RE_LETTER.test(t) && !RE_SEPARATOR.test(t) && !RE_SCORE.test(t) && !isTimeish(t) && !isBadge(t) && !L.btn && !(labels && labels[t.toLowerCase()]);
    }
    /**
     * Two team names with only short parts between them (VS, a score, a time, a status word) become "First vs Second".
     * Returns { text, upto } or null; every line between is marked used by the caller.
     */
    function pairTitle(lines, ti, tmIdx, labels) {
      var T = lines[ti];
      if (!nameLike(T, labels)) return null;
      var sep = '', between = 0;
      for (var k = ti + 1; k < lines.length && k <= ti + 7; k++) {
        var L = lines[k];
        if (tmIdx[k] || L.btn || (labels && labels[L.text.toLowerCase()])) { between++; continue; }
        if (RE_SEPARATOR.test(L.text)) { if (!sep) sep = L.text; between++; continue; }
        if (sameStyle(L, T) && nameLike(L, labels) && L.text.toLowerCase() !== T.text.toLowerCase()) {
          // adjacent same-style lines are a title and its subtitle, unless they share one class (two team names)
          if (!between && !(typeof L.el.className === 'string' && L.el.className && L.el.className === T.el.className)) return null; // two lines of the very same class = two names
          return { text: T.text + ' ' + (sep ? sepText(sep) : 'vs') + ' ' + L.text, upto: k };
        }
        if (L.text.length <= 12 || isTimeish(L.text) || RE_SCORE.test(L.text)) { between++; continue; }
        return null;
      }
      return null;
    }
    /** An empty full-card link (the whole card is clickable) names the card in its aria-label / title. */
    function overlayTitle(item) {
      var as = item.querySelectorAll('a[href]');
      for (var i = 0; i < as.length && i < 12; i++) {
        var a = as[i];
        if (oneLine(txt(a))) continue;
        var lab = oneLine(a.getAttribute('aria-label') || a.getAttribute('title') || '');
        if (lab.length < 4 || !RE_LETTER.test(lab)) continue;
        if (layoutOk) {
          var ra = a.getBoundingClientRect(), ri = item.getBoundingClientRect();
          if (ra.width < ri.width * 0.7 || ra.height < ri.height * 0.7) continue;
        }
        return lab;
      }
      return '';
    }

    /** ov: optional per-field overrides from a recipe (elements, plus section text / live boolean). */
    function readItem(item, ov, itemSet, strictLinks) {
      ov = ov || {};
      var lines = linesOf(item);
      if (!lines.length) return null;
      var labels = labelMap[uid(item)] || null;
      var tm = findTime(item, lines, ov);
      var titleIdx = -1, title = '', fromLabel = false;
      if (ov.title) {
        title = oneLine(txt(ov.title));
        for (var a = 0; a < lines.length; a++) if (ov.title.contains(lines[a].el)) { titleIdx = a; break; }
      }
      if (!title) {
        var ot = overlayTitle(item);
        if (ot) {
          title = ot; fromLabel = true;
          for (var oi = 0; oi < lines.length; oi++) if (lines[oi].text.toLowerCase() === ot.toLowerCase()) { titleIdx = oi; break; }
        }
      }
      if (!title) {
        var skip = {}, key;
        for (key in tm.idx) skip[key] = 1;
        titleIdx = pickTitle(lines, skip, labels, false);
        if (titleIdx < 0) titleIdx = pickTitle(lines, skip, null, false);
        if (titleIdx < 0) titleIdx = pickTitle(lines, {}, null, true);
        title = titleIdx >= 0 ? lines[titleIdx].text : '';
      }
      if (!title) return null;
      var used = {};
      if (titleIdx >= 0) used[titleIdx] = 1;
      if (!ov.title && !fromLabel && titleIdx >= 0) {
        // teams split over several elements: "Team A" / "vs" / "Team B" (same style) become one title
        if (titleIdx + 2 < lines.length && RE_SEPARATOR.test(lines[titleIdx + 1].text) && !tm.idx[titleIdx + 2] && !isBadge(lines[titleIdx + 2].text) &&
            nameLike(lines[titleIdx + 2], labels) && sameStyle(lines[titleIdx + 2], lines[titleIdx])) {
          title = title + ' ' + sepText(lines[titleIdx + 1].text) + ' ' + lines[titleIdx + 2].text;
          used[titleIdx + 1] = 1; used[titleIdx + 2] = 1;
        } else {
          var pr = pairTitle(lines, titleIdx, tm.idx, labels);
          if (!pr) {
            // The picked line may be a small label above the names ("League" over "Team A … Team B"): a pair of
            // same-style names anywhere in the row is the better title.
            for (var pi = 0; pi < lines.length && !pr; pi++) {
              if (pi === titleIdx || tm.idx[pi]) continue;
              var p2 = pairTitle(lines, pi, tm.idx, labels);
              if (p2) { pr = p2; delete used[titleIdx]; titleIdx = pi; used[pi] = 1; }
            }
          }
          if (pr) { title = pr.text; for (var u = titleIdx + 1; u <= pr.upto; u++) used[u] = 1; }
        }
      }
      var timeText = tm.text;
      if (!timeText && titleIdx >= 0) timeText = extractSub(lines[titleIdx].text); // "Match 8pm" as the only line
      if (!timeText && tm.datetime) timeText = tm.datetime;

      var subtitle = '';
      if (ov.subtitle) subtitle = oneLine(txt(ov.subtitle));
      else if (titleIdx >= 0 && !fromLabel) {
        for (var s = titleIdx + 1; s < lines.length; s++) {
          var L = lines[s];
          if (tm.idx[s] || used[s] || L.btn || RE_COUNTDOWN.test(L.text) || isBadge(L.text) || L.text === title || (labels && labels[L.text.toLowerCase()])) continue;
          if (L.text.length < 2) continue;
          subtitle = L.text.substring(0, 140);
          break;
        }
      }
      var url = '';
      if (ov.link) {
        var lel = ov.link;
        url = okLink(lel.getAttribute('href'), strictLinks, baseOf(lel)) || linkOf(lel, strictLinks, true);
      }
      if (!url) url = linkOf(item, strictLinks, true);
      var section = typeof ov.section === 'string' ? ov.section : findSection(item, itemSet || {});
      var live;
      if (typeof ov.live === 'boolean') live = ov.live; else live = liveHintOf(item, lines, titleIdx);
      return {
        title: title.substring(0, 160), subtitle: subtitle, url: url, timeText: timeText.substring(0, 100), datetime: tm.datetime,
        section: section, liveHint: live, imageUrl: imageOf(item)
      };
    }

    // ---------------------------------------------------------------- rows without any URL (JS click handlers)
    var CLICK_MARK = '#sl-click=';
    var clickRows = 0;
    function cssEsc(x) { try { return CSS.escape(x); } catch (e) { return String(x).replace(/[^a-zA-Z0-9_-]/g, '\\$&'); } }
    function selPart(el) {
      var s = el.tagName.toLowerCase(), t = tokens(el).slice(0, 4);
      for (var i = 0; i < t.length; i++) s += '.' + cssEsc(t[i]);
      return s;
    }
    /** A selector matching the rows of a group: tag + the classes they all share (parent tag/classes if none). */
    function rowSelector(items) {
      var common = tokens(items[0]);
      for (var i = 1; i < items.length && common.length; i++) {
        var tk = tokens(items[i]);
        common = common.filter(function (c) { return tk.indexOf(c) >= 0; });
      }
      var s = items[0].tagName.toLowerCase();
      for (var j = 0; j < common.length && j < 4; j++) s += '.' + cssEsc(common[j]);
      if (!common.length && items[0].parentElement) s = selPart(items[0].parentElement) + ' > ' + s;
      return s;
    }
    function matchesOf(sel) {
      var out = [];
      for (var d = 0; d < docs.length; d++) {
        try {
          var l = docs[d].querySelectorAll(sel);
          for (var i = 0; i < l.length; i++) if (visible(l[i])) out.push(l[i]);
        } catch (e) { return []; }
      }
      return out;
    }
    /** url for a row that has no link: page url + #sl-click=<json> (see StreamSniffer / click-row.js). */
    function clickUrl(it, sel, idx) {
      clickRows++;
      var spec = { t: it.title, i: idx, s: sel };
      if (location.hash) spec.h = location.hash; // a '#' can't appear twice in a URL, so the page's own hash rides in the spec
      return PAGE.replace(/#.*$/, '') + CLICK_MARK + encodeURIComponent(JSON.stringify(spec));
    }
    /** Unlinked row in a list where some rows do link: keep it only if it looks like an event (time or live). */
    function looksLikeEvent(it) { return !!(it.timeText || it.datetime || it.liveHint); }

    // ---------------------------------------------------------------- automatic mode
    function automatic() {
      var groups = buildGroups();
      for (var i = 0; i < groups.length; i++) scoreGroup(groups[i]);
      groups.sort(function (a, b) { return b.score - a.score; });
      var top = groups.slice(0, 25);
      var i2, j2;
      for (i2 = 0; i2 < top.length; i2++) if (top[i2].raw >= MIN_SCORE && wrapperLike(top[i2])) top[i2].score *= 0.3;
      for (i2 = 0; i2 < top.length; i2++) {
        for (j2 = i2 + 1; j2 < top.length; j2++) {
          var A = top[i2], B = top[j2];
          if (A.raw < MIN_SCORE || B.raw < MIN_SCORE || !related(A, B)) continue;
          var outer = outerOf(A, B) ? A : (outerOf(B, A) ? B : null);
          if (!outer) continue;
          var inner = outer === A ? B : A;
          if (inner.n > outer.n) outer.score *= 0.4; else inner.score *= 0.7;
        }
      }
      top.sort(function (a, b) { return b.score - a.score; });
      var desc = function (g) {
        return describe(g.items[0]) + ' in ' + describe(g.items[0].parentElement) + ' x' + g.n + ' score=' + Math.round(g.score) +
          ' time=' + Math.round(g.timeFrac * 100) + '% links=' + Math.round(g.uniqFrac * 100) + '%' + (g.chrome ? ' chrome=' + g.chrome : '');
      };
      var shown = [];
      for (var d = 0; d < top.length && d < 5; d++) shown.push(desc(top[d]));
      debug.push('groups considered ' + groups.length + ' [' + shown.join('; ') + ']');
      var best = top[0];
      if (!best || best.score < MIN_SCORE) {
        debug.push('no group scored >= ' + MIN_SCORE);
        return learnedRows() || looseRows() || { items: [], noLink: 0 };
      }
      var chosen = [best];
      for (var c = 1; c < top.length; c++) {
        var g = top[c], ok = g.score >= best.score * 0.75 && g.timeFrac >= 0.5;
        for (var q = 0; ok && q < chosen.length; q++) if (related(chosen[q], g)) ok = false;
        if (ok) chosen.push(g);
      }
      debug.push('chosen ' + chosen.length + ' group(s): ' + chosen.map(describe0).join(' + '));
      var all = [], itemSet = {};
      chosen.forEach(function (g) { g.items.forEach(function (it) { all.push(it); itemSet[uid(it)] = 1; }); });
      var selOf = {};
      chosen.forEach(function (g) { var sl = rowSelector(g.items); g.items.forEach(function (it) { selOf[uid(it)] = sl; }); });
      shape = shapeOf(best.items);
      var extra = stragglers(best, itemSet);
      if (extra.length) {
        debug.push(extra.length + ' event-like row(s) next to the list added');
        extra.forEach(function (el) { all.push(el); itemSet[uid(el)] = 1; });
      }
      all.sort(docOrder);
      return emit(all, selOf, itemSet, true);
    }

    /** Reads rows into items: dedupes, keeps unlinked rows as click rows when they look like events. */
    function emit(all, selOf, itemSet, requireEventIfSomeLinked) {
      var read = [], linked = 0, k;
      computeLabels(all, selOf);
      for (k = 0; k < all.length && read.length < MAX_ITEMS * 2; k++) {
        var rd = readItem(all[k], null, itemSet, true);
        if (!rd) continue;
        if (rd.url) linked++;
        read.push({ it: rd, row: all[k] });
      }
      var items = [], noLink = 0, kept = 0, seen = {}, matchCache = {};
      for (k = 0; k < read.length && items.length < MAX_ITEMS; k++) {
        var it = read[k].it, row = read[k].row, dk;
        if (!it.url) {
          noLink++;
          if (requireEventIfSomeLinked && linked > 0 && !looksLikeEvent(it)) continue;
          var sel = selOf[uid(row)] || '', idx = -1;
          if (sel) { var ms = matchCache[sel] || (matchCache[sel] = matchesOf(sel)); idx = ms.indexOf(row); if (idx < 0) sel = ''; }
          if (idx < 0) idx = all.indexOf(row);
          dk = 'click|' + it.title + '|' + it.timeText + '|' + it.section;
          if (seen[dk]) continue;
          seen[dk] = 1;
          it.url = clickUrl(it, sel, idx);
          kept++;
          items.push(it);
          continue;
        }
        dk = it.url + '|' + it.title + '|' + it.timeText + '|' + it.section;
        if (seen[dk]) continue;
        seen[dk] = 1;
        items.push(it);
      }
      if (noLink) debug.push(noLink + ' row(s) had no link of their own: ' + kept + ' kept as click rows, ' + (noLink - kept) + ' dropped');
      return { items: items, noLink: noLink };
    }

    // ---------------------------------------------------------------- learned shapes, lone events, stragglers
    var shape = '';
    /** A row selector anchored at its container, specific enough to recognise the same rows on sibling pages. */
    function shapeOf(items) {
      var s = rowSelector(items);
      var p = items[0].parentElement;
      if (s.indexOf('>') < 0 && p && p !== items[0].ownerDocument.body && tokens(p).length) s = selPart(p) + ' > ' + s;
      return s;
    }
    function distinctLinks(el, cap) {
      var as = el.tagName === 'A' ? [el] : [];
      var inner = el.querySelectorAll('a[href]');
      for (var i = 0; i < inner.length && i < 60; i++) as.push(inner[i]);
      var seen = {}, n = 0, base = baseOf(el);
      for (var j = 0; j < as.length; j++) {
        var u = okLink(as[j].getAttribute('href'), true, base);
        if (!u) continue;
        var nu = normUrl(u);
        if (nu === PAGE_N || seen[nu]) continue;
        seen[nu] = 1; n++;
        if (n >= cap) break;
      }
      return n;
    }
    /** From a link, the largest surrounding element that still holds only this one link (and stays inside [stop]). */
    function rowAround(a, stop, avoid) {
      var row = a, cap = Math.max(120, txt(a).length * 3);
      for (var guard = 0; guard < 8; guard++) {
        var p = row.parentElement;
        if (!p || p === stop || p === document.body || p === document.documentElement || p === p.ownerDocument.body) break;
        if (avoid && avoid(p)) break;
        if (distinctLinks(p, 2) > 1) break;
        if (txt(p).length > Math.min(600, cap)) break;
        if (p.querySelector('h1') || /^(MAIN|BODY)$/.test(p.tagName)) break;
        row = p;
      }
      return row;
    }
    function keepLone(el, it) {
      return it && it.title && (it.url || it.liveHint) && looksLikeEvent(it) && !chromeOf(el) && txt(el).length <= 600;
    }
    /** Rows matching a shape learned on this site's other pages (a page with a single event has nothing that repeats). */
    function learnedRows() {
      var list = [];
      try { if (learnedJson) list = JSON.parse(learnedJson) || []; } catch (e) { list = []; }
      for (var i = 0; i < list.length && i < 6; i++) {
        var sel = String(list[i] || '');
        if (!sel) continue;
        var rows = matchesOf(sel).filter(function (el) { return !chromeOf(el); });
        if (!rows.length || rows.length > MAX_ITEMS) continue;
        var itemSet = {}, selOf = {};
        rows.forEach(function (el) { itemSet[uid(el)] = 1; selOf[uid(el)] = sel; });
        var res = emit(rows, selOf, itemSet, false);
        if (res.items.length) { debug.push('used the row shape learned on this site: ' + sel + ' (' + res.items.length + ')'); return res; }
      }
      if (list.length) debug.push('learned row shape(s) matched nothing');
      return null;
    }
    /** No list at all: a few lone rows that link within the site and show a time or a live badge. */
    function looseRows() {
      var rows = [], seenRow = {}, seenUrl = {};
      for (var d = 0; d < docs.length; d++) {
        var as = docs[d].querySelectorAll('a[href]');
        for (var i = 0; i < as.length && i < 400; i++) {
          var a = as[i];
          var u = okLink(a.getAttribute('href'), true, baseOf(a));
          if (!u) continue;
          var nu = normUrl(u);
          if (nu === PAGE_N || seenUrl[nu]) continue;
          if (chromeOf(a) || !visible(a)) continue;
          seenUrl[nu] = 1;
          var row = rowAround(a, null, null);
          if (seenRow[uid(row)]) continue;
          var it = readItem(row, null, {}, true);
          if (!keepLone(row, it)) continue;
          seenRow[uid(row)] = 1;
          rows.push(row);
        }
      }
      if (!rows.length || rows.length > 6) return null;
      var itemSet = {};
      rows.forEach(function (el) { itemSet[uid(el)] = 1; });
      var res = emit(rows, {}, itemSet, false);
      if (res.items.length) { debug.push('no list; ' + res.items.length + ' lone event row(s)'); return res; }
      return null;
    }
    /** Event-like rows near the list but styled differently (a "live now" pair above it, say). */
    function stragglers(best, itemSet) {
      var items = best.items, p = items[0].parentElement, out = [];
      if (!p) return out;
      var area = p;
      for (var up = 0; up < 2; up++) {
        var q = area.parentElement;
        if (!q || q === q.ownerDocument.body || q === q.ownerDocument.documentElement || chromeOf(q)) break;
        area = q;
      }
      var maxLen = 0, urls = {};
      items.slice(0, 60).forEach(function (el) {
        maxLen = Math.max(maxLen, txt(el).length);
        var u = linkOf(el, true, true); if (u) urls[normUrl(u)] = 1;
      });
      function inList(el) {
        for (var e = el; e && e !== area; e = e.parentElement) if (itemSet[uid(e)]) return true;
        return false;
      }
      function holdsList(el) { for (var i = 0; i < items.length && i < 40; i++) if (el.contains(items[i])) return true; return false; }
      var as = area.querySelectorAll('a[href]'), seenRow = {};
      for (var i = 0; i < as.length && i < 300 && out.length < 20; i++) {
        var a = as[i];
        if (inList(a) || !visible(a)) continue;
        var u = okLink(a.getAttribute('href'), true, baseOf(a));
        if (!u) continue;
        var nu = normUrl(u);
        if (nu === PAGE_N || urls[nu]) continue;
        var row = rowAround(a, area, holdsList);
        if (seenRow[uid(row)] || holdsList(row)) continue;
        if (txt(row).length > Math.max(200, maxLen * 3)) continue;
        var it = readItem(row, null, itemSet, true);
        if (!keepLone(row, it)) continue;
        seenRow[uid(row)] = 1;
        urls[nu] = 1;
        out.push(row);
      }
      return out;
    }
    function describe0(g) { return describe(g.items[0]) + ' x' + g.n; }

    // ---------------------------------------------------------------- recipe mode
    function qs(root, sel) {
      if (!sel) return null;
      try { return root.querySelector(sel) || (root.matches && root.matches(sel) ? root : null); } catch (e) { return null; }
    }
    function recipeMode(r) {
      var rows;
      try { rows = Array.prototype.slice.call(document.querySelectorAll(r.item)); } catch (e) { debug.push('recipe: bad "item" selector'); return null; }
      rows = rows.filter(function (el) { try { return visible(el); } catch (e) { return false; } });
      var secs = [];
      if (r.section) { try { secs = Array.prototype.slice.call(document.querySelectorAll(r.section)); } catch (e) { debug.push('recipe: bad "section" selector'); } }
      var itemSet = {};
      rows.forEach(function (el) { itemSet[uid(el)] = 1; });
      var items = [], noLink = 0, seen = {};
      for (var i = 0; i < rows.length && items.length < MAX_ITEMS; i++) {
        var row = rows[i], ov = {};
        ov.title = qs(row, r.title); ov.subtitle = qs(row, r.subtitle); ov.time = qs(row, r.time); ov.link = qs(row, r.link);
        if (r.section) {
          var last = null;
          for (var s = 0; s < secs.length; s++) if (secs[s] !== row && (secs[s].compareDocumentPosition(row) & 4)) last = secs[s];
          if (last) ov.section = headingText(last);
        }
        if (r.live) { try { ov.live = !!(row.querySelector(r.live) || row.matches(r.live)); } catch (e) { } }
        var it = readItem(row, ov, itemSet, false);
        if (!it) continue;
        var dk;
        if (!it.url) {
          noLink++;
          var ri = rows.indexOf(row);
          dk = 'click|' + it.title + '|' + it.timeText;
          if (seen[dk]) continue;
          seen[dk] = 1;
          var rms = matchesOf(r.item);
          var rix = rms.indexOf(row);
          it.url = clickUrl(it, rix >= 0 ? r.item : '', rix >= 0 ? rix : ri);
          items.push(it);
          continue;
        }
        dk = it.url + '|' + it.title + '|' + it.timeText;
        if (seen[dk]) continue;
        seen[dk] = 1;
        items.push(it);
      }
      debug.push('recipe ' + (r.host || '') + ': ' + rows.length + ' row(s), ' + items.length + ' event(s)' + (noLink ? ', ' + noLink + ' without link (kept as click rows)' : ''));
      return { items: items, noLink: noLink };
    }

    // ---------------------------------------------------------------- page diagnostics (text size, iframes, loading)
    function diagnostics() {
      var out = { textLen: 0, loading: false, frames: [] };
      try {
        var d, i;
        for (d = 0; d < docs.length; d++) { if (docs[d].body) out.textLen += (docs[d].body.innerText || docs[d].body.textContent || '').replace(/\s+/g, ' ').trim().length; }
        var vw = document.documentElement.clientWidth || window.innerWidth || 1280, vh = window.innerHeight || document.documentElement.clientHeight || 720;
        var fs = document.querySelectorAll('iframe');
        for (i = 0; i < fs.length && out.frames.length < 12; i++) {
          var f = fs[i], r = f.getBoundingClientRect(), cs = getComputedStyle(f);
          var src = '';
          try { src = f.src || f.getAttribute('data-src') || f.getAttribute('data-lazy-src') || ''; if (src) src = new URL(src, document.baseURI).href; } catch (e) { src = ''; }
          var same = false;
          try { same = !!(f.contentDocument && f.contentDocument.body); } catch (e) { same = false; }
          var vis = cs.display !== 'none' && cs.visibility !== 'hidden' && r.width >= 100 && r.height >= 100;
          var pct = Math.round(Math.min(r.width, vw) * Math.min(r.height, vh) / (vw * vh) * 1000) / 10;
          out.frames.push({ src: src.substring(0, 500), w: Math.round(r.width), h: Math.round(r.height), pct: pct, same: same, vis: vis });
        }
        var ld = document.querySelectorAll('[aria-busy="true"],progress,[role=progressbar],[class*="spinner"],[class*="loader"],[class*="loading"],[class*="skeleton"],[id*="loading"],[id*="spinner"]');
        for (i = 0; i < ld.length && i < 10; i++) { try { if (visible(ld[i])) { out.loading = true; break; } } catch (e) { } }
        if (!out.loading && out.textLen < 200 && /\bloading\b|please wait/i.test(document.body ? document.body.innerText || '' : '')) out.loading = true;
        var big = null;
        out.frames.forEach(function (fr) { if (fr.vis && fr.src && (!big || fr.pct > big.pct)) big = fr; });
        var host = ''; if (big) { try { host = new URL(big.src).hostname; } catch (e) { host = big.src.substring(0, 40); } }
        debug.push('text ' + out.textLen + ' chars | iframes ' + out.frames.length + (big ? ' (largest ' + host + ' ' + big.pct + '%' + (big.same ? ' same-origin' : ' cross-origin') + ')' : '') +
          (docs.length > 1 ? ' | scanned ' + (docs.length - 1) + ' same-origin frame(s)' : '') + (out.loading ? ' | loading indicator' : '') +
          ' | click fallback ' + (clickRows ? 'used for ' + clickRows + ' row(s)' : 'not used'));
      } catch (e) { debug.push('diagnostics failed: ' + (e && e.message)); }
      return out;
    }

    // ---------------------------------------------------------------- main
    var recipe = null;
    try { if (recipeJson) recipe = JSON.parse(recipeJson); } catch (e) { debug.push('recipe JSON unreadable'); }
    if (recipe && (typeof recipe.item !== 'string' || !recipe.item)) recipe = null;

    var pageTitle = safeTitle();
    if (!pageTitle) { var h1 = document.querySelector('h1'); pageTitle = h1 ? oneLine(txt(h1)) : ''; }

    var result = null, method = 'automatic';
    if (!document.body) { debug.push('page has no body'); result = { items: [], noLink: 0 }; }
    else {
      if (recipe) {
        result = recipeMode(recipe);
        if (result && result.items.length) method = 'recipe';
        else { debug.push('recipe found nothing; falling back to automatic'); result = null; }
      }
      if (!result) result = automatic();
    }
    var dg = diagnostics();
    return JSON.stringify({
      pageTitle: pageTitle.substring(0, 160), pageUrl: String(PAGE), method: method, debug: debug.join(' | '),
      noLink: result.noLink, shape: shape, textLen: dg.textLen, loading: dg.loading, frames: dg.frames, clickRows: clickRows, items: result.items
    });
  }
})
