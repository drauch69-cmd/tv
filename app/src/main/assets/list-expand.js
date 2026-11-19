/*
 * StreamLink "stream lists": presses "show more" buttons before the list is read.
 *
 * ONE function expression, evaluated by IndexReader.kt in the top frame as  (<this file>)()  and returning
 * a JSON string  { pressed: n, candidates: m }.
 *
 * Some index pages render only the first rows of each section and put a plain button right after the rows
 * that renders the rest (no navigation). An "expander" is found by STRUCTURE only (never by its words):
 *  - a <button>, [role=button] or an <a> with no real href (none, "#", "javascript:"); visible, enabled, short text;
 *  - not in nav/header/footer/tablist/menu/toolbar, not a tab, not already expanded (aria-pressed/expanded=true),
 *    not a submit button in a form;
 *  - not one of a row of buttons (2+ same-kind siblings = tabs, filters, pagination);
 *  - sits right after (or as the last child beside) a group of 5+ same-shaped clickable rows.
 * At most 4 are pressed, each once. A real link is never pressed.
 */
(function () {
  'use strict';
  var MAX_PRESS = 4, MIN_ROWS = 5, MAX_TEXT = 30;
  var SKIP_ANCESTOR = 'nav,header,footer,[role=navigation],[role=tablist],[role=menu],[role=menubar],[role=toolbar]';

  function realHref(el) {
    if (el.tagName !== 'A') return false;
    var h = (el.getAttribute('href') || '').trim().toLowerCase();
    return !(h === '' || h === '#' || h.indexOf('javascript:') === 0);
  }
  function isClickableKind(el) {
    if (!el || el.nodeType !== 1) return false;
    if (el.tagName === 'BUTTON') return true;
    if (el.getAttribute('role') === 'button') return true;
    return el.tagName === 'A' && !realHref(el);
  }
  function visible(el) {
    try {
      if (!el.getClientRects().length) return false;
      var r = el.getBoundingClientRect();
      if (r.width < 2 || r.height < 2) return false;
      var cs = getComputedStyle(el);
      return cs.visibility !== 'hidden' && cs.display !== 'none' && cs.opacity !== '0';
    } catch (e) { return false; }
  }
  function textOf(el) { return (el.innerText || el.textContent || el.getAttribute('aria-label') || '').replace(/\s+/g, ' ').trim(); }
  function sameKind(a, b) { return a.tagName === b.tagName && isClickableKind(b); }

  // the button sits in a row of 2+ other same-kind clickables (tabs, filters, pagination)?
  function inButtonRow(btn) {
    var p = btn.parentElement;
    if (!p) return false;
    var n = 0, i, k;
    for (i = 0; i < p.children.length; i++) if (p.children[i] !== btn && sameKind(btn, p.children[i])) n++;
    if (n >= 2) return true;
    // a lone button in a wrapper: look at the wrapper's siblings (each a wrapper holding one such button, or the button)
    if (p.children.length === 1 && p.parentElement) {
      var gp = p.parentElement, m = 0;
      for (i = 0; i < gp.children.length; i++) {
        var c = gp.children[i];
        if (c === p) continue;
        if (sameKind(btn, c)) { m++; continue; }
        for (k = 0; k < c.children.length; k++) if (sameKind(btn, c.children[k])) { m++; break; }
      }
      if (m >= 2) return true;
    }
    return false;
  }

  function rowish(el) {
    if (el.tagName === 'A') return realHref(el) || el.hasAttribute('onclick');
    if (el.hasAttribute('onclick') || el.hasAttribute('data-href') || el.hasAttribute('tabindex')) return true;
    var role = el.getAttribute('role');
    if (role === 'link' || role === 'button') return true;
    try { return getComputedStyle(el).cursor === 'pointer'; } catch (e) { return false; }
  }
  function sig(el) { return el.tagName + '.' + (el.className && el.className.baseVal === undefined ? String(el.className) : ''); }

  // 5+ children of [container] (ignoring [skip]) with the same tag + class that look clickable
  function hasRowGroup(container, skip) {
    var counts = {}, i, c, s;
    for (i = 0; i < container.children.length; i++) {
      c = container.children[i];
      if (c === skip || !rowish(c)) continue;
      s = sig(c);
      counts[s] = (counts[s] || 0) + 1;
      if (counts[s] >= MIN_ROWS) return true;
    }
    return false;
  }
  function hasRowsWithin(root) {
    if (hasRowGroup(root, null)) return true;
    var all = root.querySelectorAll('*'), n = Math.min(all.length, 600), i;
    for (i = 0; i < n; i++) if (all[i].children.length >= MIN_ROWS && hasRowGroup(all[i], null)) return true;
    return false;
  }
  // rows right before the button: its own siblings, or inside one of the few previous siblings (also one wrapper up)
  function followsRows(btn) {
    var node = btn, level, prev, seen;
    if (btn.parentElement && hasRowGroup(btn.parentElement, btn)) return true;
    for (level = 0; level < 2 && node.parentElement; level++) {
      prev = node.previousElementSibling;
      for (seen = 0; prev && seen < 3; seen++, prev = prev.previousElementSibling) {
        if (isHeadingLike(prev)) continue;
        if (hasRowsWithin(prev)) return true;
        break; // only the nearest content sibling counts
      }
      if (node.parentElement.children.length > 1) break; // not a lone wrapper: don't climb further
      node = node.parentElement;
    }
    return false;
  }
  function isHeadingLike(el) { return /^H[1-6]$/.test(el.tagName); }

  var all = document.querySelectorAll('button,[role=button],a'), cands = [], i, el;
  for (i = 0; i < all.length; i++) {
    el = all[i];
    if (!isClickableKind(el)) continue;
    if (el.disabled || el.getAttribute('aria-disabled') === 'true') continue;
    if (el.getAttribute('role') === 'tab') continue;
    if (el.getAttribute('aria-pressed') === 'true' || el.getAttribute('aria-expanded') === 'true') continue;
    if (el.tagName === 'BUTTON' && el.closest('form') && (el.getAttribute('type') || 'submit').toLowerCase() !== 'button') continue;
    if (el.closest(SKIP_ANCESTOR)) continue;
    var t = textOf(el);
    if (!t || t.length > MAX_TEXT) continue;
    if (!visible(el)) continue;
    cands.push(el);
  }
  var pressed = 0, done = [];
  for (i = 0; i < cands.length && pressed < MAX_PRESS; i++) {
    el = cands[i];
    if (done.indexOf(el) >= 0) continue;
    if (inButtonRow(el)) continue;
    if (!followsRows(el)) continue;
    done.push(el);
    try { el.click(); pressed++; } catch (e) { /* ignore */ }
  }
  return JSON.stringify({ pressed: pressed, candidates: cands.length });
})
