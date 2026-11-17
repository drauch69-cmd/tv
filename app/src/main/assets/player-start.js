/*
 * StreamLink "start helper". Runs at document start in EVERY frame of the hidden page loader,
 * including a player frame from another site (which the app cannot otherwise script).
 * Many players only start after a press on their start/play button. This finds that button by
 * STRUCTURE (a clickable element near the middle of the player area, much smaller than it),
 * never by its wording, and presses it. It also starts any <video> muted.
 * Stops as soon as a video plays, or after a number of tries. Navigation, popups and redirects a
 * press might cause are blocked by the app.
 */
(function () {
  if (window.__slStart) return;
  window.__slStart = 1;
  var tries = 0, MAX = 14, EVERY = 2500;
  var KNOWN = '.vjs-big-play-button,.jw-icon-display,.jw-display-icon-container,.plyr__control--overlaid,' +
    '.fp-play,.play-button,.playbtn,#play,button[aria-label="Play"],button[title="Play"],[class*="play-btn"]';

  function rectOf(e) {
    if (!e || !e.getBoundingClientRect) return null;
    var r = e.getBoundingClientRect();
    if (r.width < 8 || r.height < 8) return null;
    var cs = getComputedStyle(e);
    if (cs.visibility === 'hidden' || cs.display === 'none' || parseFloat(cs.opacity) < 0.05) return null;
    if (r.bottom <= 0 || r.right <= 0 || r.top >= innerHeight || r.left >= innerWidth) return null;
    return r;
  }
  function playing() {
    var vs = document.getElementsByTagName('video');
    for (var i = 0; i < vs.length; i++) if (!vs[i].paused && vs[i].readyState >= 2) return true;
    return false;
  }
  function startVideos() {
    var vs = document.getElementsByTagName('video');
    for (var i = 0; i < vs.length; i++) {
      try { vs[i].muted = true; var p = vs[i].play(); if (p && p.catch) p.catch(function () {}); } catch (e) {}
    }
  }
  /** The player box: the largest visible video; else a large 16:9-ish box holding a video, canvas or
   *  frame; else, inside a frame that fills most of itself with nothing else, the frame itself. */
  function playerBox() {
    var best = null, ba = 0, i, r;
    var vs = document.getElementsByTagName('video');
    for (i = 0; i < vs.length; i++) {
      r = rectOf(vs[i]);
      if (r && r.width * r.height > ba && r.width * r.height >= 20000) { ba = r.width * r.height; best = r; }
    }
    if (best) return best;
    var all = document.querySelectorAll('div,section,figure,main,article');
    var vp = innerWidth * innerHeight;
    for (i = 0; i < all.length && i < 3000; i++) {
      r = rectOf(all[i]);
      if (!r) continue;
      var a = r.width * r.height, ar = r.width / r.height;
      if (a < vp * 0.12 || a < 20000 || ar < 1.3 || ar > 2.4) continue;
      if (!all[i].querySelector('video,canvas,iframe,object,embed')) continue;
      if ((all[i].innerText || '').length > 400) continue;
      if (a > ba) { ba = a; best = r; }
    }
    if (best) return best;
    if (window !== window.top && (document.body && (document.body.innerText || '').length < 400)) {
      return { left: 0, top: 0, right: innerWidth, bottom: innerHeight, width: innerWidth, height: innerHeight };
    }
    return null;
  }
  function sameSiteLink(a) {
    try {
      var h = new URL(a.href, location.href).hostname.replace(/^www\./, '');
      var me = (location.hostname || '').replace(/^www\./, '');
      return h === me || h.slice(-(me.length + 1)) === '.' + me || me.slice(-(h.length + 1)) === '.' + h;
    } catch (e) { return false; }
  }
  /** The clickable element an element belongs to, or null. */
  function clickable(e, box) {
    for (var n = e, d = 0; n && n !== document.body && n !== document.documentElement && d < 6; n = n.parentElement, d++) {
      var tag = n.tagName;
      if (tag === 'VIDEO' || tag === 'IFRAME' || tag === 'CANVAS') return null;
      var r = rectOf(n);
      if (!r) continue;
      if (r.width > box.width * 0.6 || r.height > box.height * 0.6) return null; // as big as the player: not a button
      if (tag === 'A') return (n.getAttribute('href') || '#').charAt(0) === '#' || sameSiteLink(n) ? n : null;
      if (tag === 'BUTTON' || n.getAttribute('role') === 'button' || n.onclick || getComputedStyle(n).cursor === 'pointer') return n;
    }
    return null;
  }
  function pick(box) {
    var known = document.querySelectorAll(KNOWN);
    for (var k = 0; k < known.length; k++) if (rectOf(known[k])) return known[k];
    var cx = (box.left + box.right) / 2, cy = (box.top + box.bottom) / 2;
    var F = [0.5, 0.4, 0.6, 0.3, 0.7], best = null, bd = 1e9, seen = [];
    for (var a = 0; a < F.length; a++) for (var b = 0; b < F.length; b++) {
      var x = box.left + box.width * F[a], y = box.top + box.height * F[b];
      if (x < 0 || y < 0 || x >= innerWidth || y >= innerHeight) continue;
      var st = document.elementsFromPoint(x, y);
      for (var s = 0; s < st.length && s < 8; s++) {
        var c = clickable(st[s], box);
        if (!c || seen.indexOf(c) >= 0) continue;
        seen.push(c);
        var r = c.getBoundingClientRect();
        var dx = (r.left + r.right) / 2 - cx, dy = (r.top + r.bottom) / 2 - cy;
        var dist = Math.sqrt(dx * dx + dy * dy);
        if (dist < bd) { bd = dist; best = c; }
      }
    }
    // Only a button near the middle: a corner icon is more likely "mute" or "fullscreen".
    return best && bd < Math.min(box.width, box.height) * 0.35 ? best : null;
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
  function step() {
    if (playing() || ++tries > MAX) return;
    startVideos();
    var box = playerBox();
    if (box) {
      var el = pick(box);
      if (el) {
        press(el);
        window.__slStartPressed = (window.__slStartPressed || 0) + 1;
      }
    }
    setTimeout(step, EVERY);
  }
  function begin() { setTimeout(step, 1200); }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', begin);
  else begin();
})();
