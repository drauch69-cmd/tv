/*
 * StreamLink: opens one event row of a list page by clicking it (rows that have no URL in the DOM).
 *
 * ONE function expression. StreamSniffer.kt evaluates it as  (<this file>)(<spec JSON string>)
 * where spec = {t: title, i: index among the rows matching s, s: CSS selector of the rows (may be "")}
 * (the fragment IndexReader's list-extract.js put after the page URL: "#sl-click=<json>").
 * Returns a JSON string:
 *   { ok: true, how: "title"|"index"|"text", x, y, iw }   x,y = centre of the row in CSS px of the top
 *                                                            window (so Kotlin can also tap there), iw = window.innerWidth
 *   { ok: false, why: "..." }
 * Same-origin iframes are searched too. The click is a short mouse sequence plus element.click() on
 * the title element inside the row (so handlers on the row, on the title, or delegated all see it).
 */
(function (specJson) {
  'use strict';
  try { return run(); } catch (e) { return JSON.stringify({ ok: false, why: 'error: ' + (e && e.message ? e.message : e) }); }

  function run() {
    var spec = JSON.parse(specJson);
    var title = norm(spec.t || '');
    var sel = typeof spec.s === 'string' ? spec.s : '';
    var idx = typeof spec.i === 'number' ? spec.i : -1;
    if (!title && !sel) return JSON.stringify({ ok: false, why: 'empty spec' });

    var docs = [document];
    (function collect(doc, depth) {
      if (depth > 2) return;
      var fs = doc.querySelectorAll('iframe');
      for (var i = 0; i < fs.length && docs.length < 8; i++) {
        try { var d = fs[i].contentDocument; if (d && d.body) { docs.push(d); collect(d, depth + 1); } } catch (e) { }
      }
    })(document, 0);

    function norm(s) { return String(s || '').replace(/[ \s]+/g, ' ').trim().toLowerCase(); }
    function visible(el) {
      var cs = getComputedStyle(el);
      if (cs.display === 'none' || cs.visibility === 'hidden') return false;
      return el.getClientRects().length > 0;
    }
    function textOf(el) { return String(el.innerText != null ? el.innerText : el.textContent || ''); }

    var cands = [], i, d;
    if (sel) {
      for (d = 0; d < docs.length; d++) {
        try {
          var l = docs[d].querySelectorAll(sel);
          for (i = 0; i < l.length; i++) if (visible(l[i])) cands.push(l[i]);
        } catch (e) { }
      }
    }

    var row = null, how = '';
    // 1. exact title among the candidates (a line of the row equals it, or the whole row text does)
    if (title && cands.length) {
      var exact = [], part = [];
      cands.forEach(function (c, n) {
        var raw = textOf(c), whole = norm(raw);
        var lines = raw.split('\n').map(norm);
        if (whole === title || lines.indexOf(title) >= 0) exact.push(n);
        else if (whole.indexOf(title) >= 0) part.push(n);
      });
      var pool = exact.length ? exact : part;
      if (pool.length) {
        var pick = pool.indexOf(idx) >= 0 ? idx : pool[0];
        row = cands[pick]; how = 'title';
      }
    }
    // 2. index
    if (!row && cands.length && idx >= 0 && idx < cands.length) { row = cands[idx]; how = 'index'; }
    // 3. no usable selector: the smallest visible element whose text is the title, then its clickable ancestor
    if (!row && title) {
      var best = null, bestLen = 1e9;
      for (d = 0; d < docs.length; d++) {
        var all = docs[d].body.getElementsByTagName('*');
        for (i = 0; i < all.length && i < 20000; i++) {
          var el = all[i];
          if (/^(SCRIPT|STYLE|NOSCRIPT|TEMPLATE|HEAD|TITLE)$/i.test(el.tagName)) continue;
          var t = norm(textOf(el));
          if (t.length >= bestLen || t.indexOf(title) < 0 || t.length > title.length * 3 + 40) continue;
          if (!visible(el)) continue;
          best = el; bestLen = t.length;
        }
      }
      if (best) {
        row = best; how = 'text';
        for (var e = best, g = 0; e && g < 6; e = e.parentElement, g++) {
          var cur = '';
          try { cur = getComputedStyle(e).cursor; } catch (x) { }
          if (e.tagName === 'A' || e.tagName === 'BUTTON' || e.getAttribute('onclick') || e.getAttribute('role') === 'button' || cur === 'pointer') { row = e; break; }
        }
      }
    }
    if (!row) return JSON.stringify({ ok: false, why: cands.length ? 'no row matched among ' + cands.length : 'no rows found' });

    try { row.scrollIntoView({ block: 'center', inline: 'nearest' }); } catch (e) { }

    // the element that holds the title text (click that, the event bubbles up through the row)
    var target = row;
    if (title) {
      var inner = row.getElementsByTagName('*'), bl = 1e9;
      for (i = 0; i < inner.length && i < 400; i++) {
        var tx = norm(textOf(inner[i]));
        if (tx && tx.length < bl && (tx === title || tx.indexOf(title) >= 0) && visible(inner[i])) { target = inner[i]; bl = tx.length; }
      }
    }
    var rect = target.getBoundingClientRect();
    var cx = rect.left + rect.width / 2, cy = rect.top + rect.height / 2;
    var win = target.ownerDocument.defaultView;
    function fire(type, Ctor) {
      try {
        target.dispatchEvent(new (Ctor || win.MouseEvent)(type, { bubbles: true, cancelable: true, view: win, clientX: cx, clientY: cy, button: 0 }));
      } catch (e) { }
    }
    if (win.PointerEvent) fire('pointerdown', win.PointerEvent);
    fire('mousedown');
    if (win.PointerEvent) fire('pointerup', win.PointerEvent);
    fire('mouseup');
    target.click();

    // centre of the row in top-window coordinates (add the offsets of the iframes it sits in)
    var rr = row.getBoundingClientRect();
    var x = rr.left + rr.width / 2, y = rr.top + rr.height / 2;
    try {
      for (var w = win; w && w !== window && w.frameElement; w = w.parent) {
        var fr = w.frameElement.getBoundingClientRect();
        x += fr.left; y += fr.top;
      }
    } catch (e) { }
    return JSON.stringify({ ok: true, how: how, x: Math.round(x), y: Math.round(y), iw: window.innerWidth });
  }
})
