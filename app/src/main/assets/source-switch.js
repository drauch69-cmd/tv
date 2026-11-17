/*
 * StreamLink: finds the row of "alternate stream" buttons next to a page's player and clicks them.
 *
 * ONE function expression. StreamSniffer.kt evaluates it as  (<this file>)("find")  or
 * (<this file>)("click 2")  in the TOP document and gets a JSON string back:
 *   find  -> { found: true, count, active (index or -1), labels: [...], xs: [...], ys: [...], iw }
 *            xs/ys = centre of each item in CSS px of the window (Kotlin can tap there), iw = innerWidth
 *            { found: false, why: "..." } when there is no switcher
 *   click -> { ok: true } | { ok: false, why: "..." }
 *
 * Detected by STRUCTURE only, never by wording: 2..8 clickable siblings of similar height and shape with
 * short text, sitting within ~350 px above/below (or overlapping the edge of) the largest visible
 * video/iframe, outside nav/header/footer/aside-like containers, and not links to other sites (ads).
 * Labels are only returned for display.
 */
(function (mode) {
  'use strict';
  var NEAR = 350, MIN_PLAYER_AREA = 40000, MAX_TEXT = 40;

  function run() {
    var m = /^click\s+(\d+)$/.exec(String(mode || ''));
    if (m) return JSON.stringify(clickItem(parseInt(m[1], 10)));
    return JSON.stringify(find());
  }

  function visibleRect(e) {
    if (!e || !e.getBoundingClientRect) return null;
    var r = e.getBoundingClientRect();
    if (r.width < 4 || r.height < 4) return null;
    var cs = getComputedStyle(e);
    if (cs.visibility === 'hidden' || cs.display === 'none' || parseFloat(cs.opacity) < 0.05) return null;
    return r;
  }
  function textOf(el) { return String(el.innerText != null ? el.innerText : el.textContent || '').replace(/\s+/g, ' ').trim(); }

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

  function sameSite(host) {
    var a = String(host || '').replace(/^www\./, ''), b = (location.hostname || '').replace(/^www\./, '');
    return a === b || a.slice(-(b.length + 1)) === '.' + b || b.slice(-(a.length + 1)) === '.' + a;
  }
  /** An element that is (or holds) a link to another site: an ad, not an alternate stream. */
  function externalLink(el) {
    var as = el.tagName === 'A' ? [el] : Array.prototype.slice.call(el.querySelectorAll('a[href]'), 0, 3);
    for (var i = 0; i < as.length; i++) {
      var h = as[i].getAttribute('href') || '';
      if (!h || h.charAt(0) === '#' || /^javascript:/i.test(h)) continue;
      try {
        var u = new URL(h, location.href);
        if (u.protocol !== 'http:' && u.protocol !== 'https:') return true;
        if (!sameSite(u.hostname)) return true;
      } catch (e) { return true; }
    }
    return false;
  }

  function playerBox() {
    var best = null, ba = 0, els = document.querySelectorAll('video,iframe');
    for (var i = 0; i < els.length; i++) {
      var r = visibleRect(els[i]);
      if (!r) continue;
      var a = r.width * r.height;
      if (a >= MIN_PLAYER_AREA && a > ba) { ba = a; best = r; }
    }
    return best;
  }

  var CLICKABLE_SEL = 'button,a[href],[role=button],[role=tab],[onclick],input[type=button],input[type=submit]';
  function clickables(box) {
    var set = [], seen = new Set(), i, e;
    var lo = box.top - NEAR - 60, hi = box.bottom + NEAR + 60;
    var all = document.querySelectorAll('body *');
    for (i = 0; i < all.length && i < 6000; i++) {
      e = all[i];
      var tg = e.tagName;
      if (tg === 'VIDEO' || tg === 'IFRAME' || tg === 'CANVAS' || tg === 'SCRIPT' || tg === 'STYLE' || tg === 'SVG' || tg === 'PATH') continue;
      var r = e.getBoundingClientRect();
      if (r.width < 4 || r.height < 4 || r.bottom < lo || r.top > hi) continue;
      var ok = e.matches(CLICKABLE_SEL);
      if (!ok && (tg === 'DIV' || tg === 'SPAN' || tg === 'LI' || tg === 'LABEL')) {
        ok = getComputedStyle(e).cursor === 'pointer' && (!e.parentElement || getComputedStyle(e.parentElement).cursor !== 'pointer');
      }
      if (ok && visibleRect(e) && !e.querySelector('video,iframe,canvas')) { set.push(e); seen.add(e); }
    }
    // Outermost clickables only (a button's inner span is not a second button).
    return set.filter(function (el) {
      for (var p = el.parentElement; p && p !== document.body; p = p.parentElement) if (seen.has(p)) return false;
      return true;
    });
  }

  /** The element that sits directly in the shared parent: the clickable itself, or its wrapper (li, div). */
  function unitOf(el, count) {
    var u = el;
    for (var d = 0; d < 2; d++) {
      var p = u.parentElement;
      if (!p || p === document.body || p === document.documentElement) break;
      if (count(p) !== 1) break;
      u = p;
    }
    return u;
  }

  function classSet(el) { return (typeof el.className === 'string' ? el.className : '').split(/\s+/).filter(Boolean); }
  function sig(el) {
    var cs = getComputedStyle(el);
    return [cs.backgroundColor, cs.color, cs.fontWeight, cs.borderTopColor, cs.borderBottomColor, cs.textDecorationLine, cs.opacity].join('|');
  }
  function stateAttr(el) {
    var t = el.matches('[aria-selected=true],[aria-current]:not([aria-current=false]),[aria-pressed=true]') ? el : el.querySelector('[aria-selected=true],[aria-current]:not([aria-current=false]),[aria-pressed=true]');
    return !!t;
  }
  var RE_STATE_CLASS = /(^|[\s_-])(active|selected|current|on|checked)([\s_-]|$)/i;

  function analyse(items, box) {
    // Similar size and shape, short text.
    var tag = items[0].unit.tagName, hs = items.map(function (i) { return i.rect.height; });
    var hmax = Math.max.apply(null, hs), hmin = Math.min.apply(null, hs);
    if (hmax <= 0 || (hmax - hmin) / hmax > 0.3) return null;
    var k, common = null;
    for (k = 0; k < items.length; k++) {
      if (items[k].unit.tagName !== tag) return null;
      var cl = classSet(items[k].unit);
      common = common === null ? cl : common.filter(function (c) { return cl.indexOf(c) >= 0; });
      var t = items[k].text;
      if (!t || t.length > MAX_TEXT) return null;
    }
    // Not stacked on top of each other (a list of articles): same row or same column band.
    var top = Infinity, bottom = -Infinity, left = Infinity, right = -Infinity;
    items.forEach(function (i) { top = Math.min(top, i.rect.top); bottom = Math.max(bottom, i.rect.bottom); left = Math.min(left, i.rect.left); right = Math.max(right, i.rect.right); });
    var allClassless = items.every(function (i) { return classSet(i.unit).length === 0; });
    if (!allClassless && common.length === 0) {
      // No shared class: only fine when all but one member have the very same class list (the odd one is the active one).
      var tally = {}, mx = 0;
      items.forEach(function (i) { var key = classSet(i.unit).sort().join(' '); tally[key] = (tally[key] || 0) + 1; mx = Math.max(mx, tally[key]); });
      if (mx < items.length - 1) return null;
    }
    // Location against the player box.
    if (right <= box.left || left >= box.right) return null;
    var gap = bottom <= box.top ? box.top - bottom : top >= box.bottom ? top - box.bottom : 0;
    if (gap > NEAR) return null;
    if (top >= box.top && bottom <= box.bottom && left >= box.left && right <= box.right) return null; // inside the player: its controls
    if (chromeOf(items[0].unit)) return null;
    var n = items.length, i2;

    // The active member: explicit state, else the one member that looks different from all the rest.
    var active = -1, hinted = [];
    for (i2 = 0; i2 < n; i2++) {
      if (stateAttr(items[i2].unit) || stateAttr(items[i2].el)) hinted.push(i2);
    }
    if (hinted.length === 1) active = hinted[0];
    var oneDifferent = false;
    if (active < 0) {
      var sigs = items.map(function (i) { return sig(i.unit) + '#' + sig(i.el); }), cnt = {};
      sigs.forEach(function (s) { cnt[s] = (cnt[s] || 0) + 1; });
      var odd = [], kinds = Object.keys(cnt);
      if (n >= 3 && kinds.length === 2) for (i2 = 0; i2 < n; i2++) if (cnt[sigs[i2]] === 1) odd.push(i2);
      if (odd.length === 1) { active = odd[0]; oneDifferent = true; }
      if (active < 0) {
        var cls = items.map(function (i) { return RE_STATE_CLASS.test(classSet(i.unit).concat(classSet(i.el)).join(' ')); }), who = [];
        cls.forEach(function (b, ix) { if (b) who.push(ix); });
        if (who.length === 1) active = who[0];
      }
    } else oneDifferent = true;
    // Labels that differ only in their numbers ("A-1", "A-2", ...): a numbered row of alternatives.
    var norm = items.map(function (i) { return i.text.replace(/\d+/g, '#'); });
    var numbered = norm.every(function (s) { return s === norm[0]; }) && /#/.test(norm[0]);
    if (active < 0 && !numbered) return null; // a row of unrelated buttons (share, like, report ...)
    var score = (active >= 0 ? 40 : 0) + (numbered ? 30 : 0) + (n >= 3 && n <= 6 ? 10 : 0) + (oneDifferent ? 10 : 0) + (NEAR - gap) / NEAR * 20;
    return { active: active, score: score };
  }

  function find() {
    var box = playerBox();
    if (!box) return { found: false, why: 'no player box' };
    var cl = clickables(box);
    if (cl.length < 2) return { found: false, why: 'fewer than 2 clickable elements near the player' };
    var set = new Set(cl);
    var count = function (p) { var c = 0; set.forEach(function (x) { if (p.contains(x)) c++; }); return c; };
    var groups = new Map();
    cl.forEach(function (el) {
      var unit = unitOf(el, count), p = unit.parentElement;
      if (!p) return;
      if (!groups.has(p)) groups.set(p, []);
      groups.get(p).push({ el: el, unit: unit });
    });
    var best = null;
    groups.forEach(function (members) {
      var items = members.map(function (x) {
        var r = visibleRect(x.unit);
        return r ? { el: x.el, unit: x.unit, rect: r, text: textOf(x.unit) } : null;
      }).filter(Boolean);
      var before = items.length;
      items = items.filter(function (i) { return !externalLink(i.el); });
      if (items.length < 2 || before - items.length >= items.length) return; // mostly links to other sites: ads
      if (items.length > 8) return;
      var res = analyse(items, box);
      if (res && (!best || res.score > best.score)) best = { items: items, active: res.active, score: res.score };
    });
    if (!best) return { found: false, why: 'no row of alternate links near the player' };
    window.__slSwitch = { targets: best.items.map(function (i) { return i.el; }) };
    return {
      found: true,
      count: best.items.length,
      active: best.active,
      labels: best.items.map(function (i) { return i.text; }),
      xs: best.items.map(function (i) { return Math.round(i.rect.left + i.rect.width / 2); }),
      ys: best.items.map(function (i) { return Math.round(i.rect.top + i.rect.height / 2); }),
      iw: innerWidth
    };
  }

  function press(el) {
    var r = el.getBoundingClientRect(), x = (r.left + r.right) / 2, y = (r.top + r.bottom) / 2;
    var o = { bubbles: true, cancelable: true, view: window, clientX: x, clientY: y, button: 0 };
    try { el.dispatchEvent(new PointerEvent('pointerdown', o)); } catch (e) {}
    try { el.dispatchEvent(new MouseEvent('mousedown', o)); } catch (e) {}
    try { el.dispatchEvent(new PointerEvent('pointerup', o)); } catch (e) {}
    try { el.dispatchEvent(new MouseEvent('mouseup', o)); } catch (e) {}
    try { el.click(); } catch (e) {}
  }

  function clickItem(i) {
    var sw = window.__slSwitch, targets = sw && sw.targets;
    if (!targets || !targets.every(function (t) { return t.isConnected; })) {
      var f = find();
      if (!f.found) return { ok: false, why: 'switcher is gone' };
      targets = window.__slSwitch.targets;
    }
    if (i < 0 || i >= targets.length) return { ok: false, why: 'no such item' };
    press(targets[i]);
    return { ok: true };
  }

  try { return run(); } catch (e) { return JSON.stringify({ found: false, ok: false, why: 'error: ' + (e && e.message ? e.message : e) }); }
})
