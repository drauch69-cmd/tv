/*
 * StreamLink: reads the page's OWN numbered source addresses, if it shows any.
 *
 * ONE function expression, evaluated in the TOP document as  (<this file>)("start")  and, a moment later,
 * (<this file>)("read"). "start" begins the work (it needs a few timer ticks, and evaluateJavascript cannot
 * wait for a Promise) and answers {started:true}; "read" answers {done:false} until the work has finished, then
 *   { done: true, urls: [ { url, label, index } ] }   (at most 8; never the page's own current address)
 *
 * Two shapes, found by STRUCTURE only (never by wording):
 *  1. A visible <select> with 2..12 options near the player (within ~600 px of the largest visible landscape
 *     video/iframe), outside nav/header/footer/aside-like containers. Each option other than the one showing is
 *     chosen in turn (value set, "input" and "change" fired); if the page then rewrites its own address to the
 *     SAME origin and path with only the query different, that address is recorded. Afterwards the original
 *     option is chosen again and the original address restored (history.replaceState, no reload).
 *  2. <a href> links (outside nav/header/footer/aside-like containers) whose address has the page's origin and
 *     path and differs from the page's address only by the value of ONE query parameter, with small integer
 *     values (0..20) and at least 2 distinct values among them.
 * Labels are only for display (the option text, the link text, or "Source N").
 */
(function (mode) {
  'use strict';
  var MAX = 8, MIN_AREA = 40000, NEAR = 600, TICK_MS = 30, MAX_LABEL = 40;

  function textOf(el) { return String(el.innerText != null ? el.innerText : el.textContent || '').replace(/\s+/g, ' ').trim(); }
  function label(t) {
    t = String(t || '').replace(/\s+/g, ' ').trim();
    return t.length <= MAX_LABEL ? t : t.slice(0, MAX_LABEL - 1).trim() + '…';
  }
  function visibleRect(e) {
    if (!e || !e.getBoundingClientRect) return null;
    var r = e.getBoundingClientRect();
    if (r.width < 4 || r.height < 4) return null;
    var cs = getComputedStyle(e);
    if (cs.visibility === 'hidden' || cs.display === 'none' || parseFloat(cs.opacity) < 0.05) return null;
    return r;
  }
  var RE_CHROME_CLASS = /(^|[\s_-])(nav|navbar|navigation|menu|topbar|footer|sidebar|breadcrumbs?|pagination|pager|dropdown|cookie|consent|banner|ads?|advert|sponsor|sponsored|promo)([\s_-]|$)/i;
  function chromeOf(el) {
    for (var e = el; e && e !== document.body && e !== document.documentElement; e = e.parentElement) {
      var tg = e.tagName;
      if (tg === 'NAV' || tg === 'ASIDE') return tg.toLowerCase();
      if ((tg === 'HEADER' || tg === 'FOOTER') && !(e.parentElement && e.parentElement.closest('main,article,section'))) return tg.toLowerCase();
      var r = (e.getAttribute('role') || '').toLowerCase();
      if (r === 'navigation' || r === 'banner' || r === 'contentinfo' || r === 'complementary' || r === 'menubar') return 'role=' + r;
      var c = (typeof e.className === 'string' ? e.className : '') + ' ' + (e.id || '');
      if (RE_CHROME_CLASS.test(c)) return 'class';
    }
    return '';
  }
  function pinned(e) {
    for (var n = e; n && n !== document.body; n = n.parentElement) {
      var p = getComputedStyle(n).position;
      if (p === 'fixed' || p === 'sticky') return true;
    }
    return false;
  }

  /** Rect of the largest visible landscape video/iframe (a tall narrow panel such as chat is never the player), or null. */
  function anchor() {
    var best = null, ba = 0, els = document.querySelectorAll('video,iframe');
    for (var i = 0; i < els.length; i++) {
      var r = visibleRect(els[i]);
      if (!r || r.width / r.height < 1.1) continue;
      var a = r.width * r.height;
      if (a < MIN_AREA || a <= ba || pinned(els[i])) continue;
      ba = a; best = r;
    }
    return best;
  }
  function vGap(r, ar) { return r.top > ar.bottom ? r.top - ar.bottom : (ar.top > r.bottom ? ar.top - r.bottom : 0); }

  /** The same page with only the query different (origin and path equal)? Returns the new address, else ''. */
  function sameBase(href, base) {
    try {
      var u = new URL(href, base.href);
      if (u.origin !== base.origin || u.pathname !== base.pathname || u.search === base.search) return '';
      return u.origin + u.pathname + u.search;
    } catch (e) { return ''; }
  }

  function tick() { return new Promise(function (res) { setTimeout(res, TICK_MS); }); }
  function fire(el, type) {
    try { el.dispatchEvent(new Event(type, { bubbles: true })); } catch (e) {}
  }

  function fromSelects(base, out) {
    var ar = anchor();
    if (!ar) return Promise.resolve();
    var sels = Array.prototype.slice.call(document.querySelectorAll('select')).filter(function (s) {
      var r = visibleRect(s);
      return r && s.options.length >= 2 && s.options.length <= 12 && !chromeOf(s) && vGap(r, ar) <= NEAR;
    });
    var chain = Promise.resolve();
    sels.forEach(function (sel) {
      chain = chain.then(function () {
        var startIdx = sel.selectedIndex, startHref = location.href, seq = Promise.resolve();
        for (var i = 0; i < sel.options.length; i++) (function (i) {
          if (i === startIdx) return;
          seq = seq.then(function () {
            if (out.length >= MAX) return;
            sel.selectedIndex = i;
            fire(sel, 'input'); fire(sel, 'change');
            return Promise.resolve().then(tick).then(function () {
              var now = location.href;
              if (now !== startHref) {
                var u = sameBase(now, base);
                if (u && !out.some(function (o) { return o.url === u; })) out.push({ url: u, label: label(sel.options[i].text), index: i });
                // Each option is judged against the untouched address.
                try { history.replaceState(history.state, '', startHref); } catch (e) {}
              }
            });
          });
        })(i);
        return seq.then(function () {
          sel.selectedIndex = startIdx;
          fire(sel, 'input'); fire(sel, 'change');
          return tick();
        }).then(function () {
          if (location.href !== startHref) { try { history.replaceState(history.state, '', startHref); } catch (e) {} }
        });
      });
    });
    return chain;
  }

  function fromLinks(base, out) {
    var byKey = {}, as = document.querySelectorAll('a[href]');
    for (var i = 0; i < as.length && i < 2000; i++) {
      var a = as[i], h = a.getAttribute('href') || '';
      if (!h || h.charAt(0) === '#' || /^(javascript|mailto|tel):/i.test(h)) continue;
      var u;
      try { u = new URL(h, base.href); } catch (e) { continue; }
      if (u.origin !== base.origin || u.pathname !== base.pathname) continue;
      var keys = {};
      u.searchParams.forEach(function (v, k) { keys[k] = 1; });
      base.searchParams.forEach(function (v, k) { keys[k] = 1; });
      var diff = Object.keys(keys).filter(function (k) { return u.searchParams.get(k) !== base.searchParams.get(k); });
      if (diff.length !== 1) continue;
      var key = diff[0], val = u.searchParams.get(key);
      if (val === null || !/^\d{1,2}$/.test(val) || parseInt(val, 10) > 20) continue;
      if (!visibleRect(a) || chromeOf(a)) continue;
      var full = u.origin + u.pathname + u.search;
      (byKey[key] = byKey[key] || []).push({ url: full, text: textOf(a), val: parseInt(val, 10) });
    }
    var bestKey = null, bestN = 0;
    Object.keys(byKey).forEach(function (k) {
      var vals = {};
      byKey[k].forEach(function (x) { vals[x.val] = 1; });
      var baseVal = base.searchParams.get(k);
      var n = Object.keys(vals).length + (baseVal !== null && /^\d{1,2}$/.test(baseVal) && !vals[parseInt(baseVal, 10)] ? 1 : 0);
      if (n >= 2 && n > bestN) { bestN = n; bestKey = k; }
    });
    if (bestKey === null) return;
    var seen = {};
    byKey[bestKey].forEach(function (x) {
      if (seen[x.url] || out.length >= MAX || out.some(function (o) { return o.url === x.url; })) return;
      seen[x.url] = 1;
      out.push({ url: x.url, label: label(x.text) || ('Source ' + (out.length + 1)), index: x.val });
    });
  }

  function run() {
    var base = new URL(location.href), out = [];
    return fromSelects(base, out).then(function () {
      if (out.length < MAX) fromLinks(base, out);
      return out.slice(0, MAX);
    });
  }

  try {
    if (mode === 'start') {
      window.__slPageSources = null;
      run().then(function (urls) { window.__slPageSources = { done: true, urls: urls }; },
                 function () { window.__slPageSources = { done: true, urls: [] }; });
      return JSON.stringify({ started: true });
    }
    return JSON.stringify(window.__slPageSources || { done: false });
  } catch (e) {
    return JSON.stringify({ done: true, urls: [], why: 'error: ' + (e && e.message ? e.message : e) });
  }
})
