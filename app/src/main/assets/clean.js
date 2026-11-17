// StreamLink clean browser player: plays one already-found HLS stream with hls.js on a blank page.
// Talks to the app through the "SL" bridge (see CleanPlayerActivity) and offers window.SLC to it.
(function () {
  'use strict';
  var B = window.SL;
  var v = document.getElementById('v');
  var cfg = {};
  try { cfg = JSON.parse(B.config()); } catch (e) { cfg = {}; }
  var gen = cfg.gen | 0;

  var hls = null;
  var failed = false, everPlayed = false, userPaused = false, isLive = false;
  var mediaRecovers = 0, swapped = false, netRecovers = 0, lastRecoverAt = 0;
  var playTries = 0;
  var t0 = Date.now(), lastTime = -1, lastProgress = Date.now();
  var counts = {}, onceKeys = {};

  function log(m) { try { B.log(String(m)); } catch (e) {} }
  // Noisy events: the first few in full, then only every 25th with a count.
  function lim(key, msg) {
    var n = counts[key] = (counts[key] || 0) + 1;
    if (n <= 3) log(msg); else if (n % 25 === 0) log(msg + ' (x' + n + ')');
  }
  function once(key, msg) { if (!onceKeys[key]) { onceKeys[key] = 1; log(msg); } }
  function short(u) {
    try { var p = String(u).split('?')[0].split('/'); return p[2] + '/…/' + p[p.length - 1].slice(0, 40); }
    catch (e) { return String(u); }
  }
  function fail(reason) {
    if (failed) return;
    failed = true;
    log('Giving up: ' + reason);
    try { if (hls) hls.destroy(); } catch (e) {}
    hls = null;
    try { v.pause(); } catch (e) {}
    try { B.failed(gen, String(reason)); } catch (e) {}
  }

  window.addEventListener('error', function (e) {
    lim('jserr', 'Page script error: ' + (e && e.message));
  });

  function tryPlay() {
    if (failed || userPaused) return;
    try {
      v.muted = false; v.volume = 1;
      var p = v.play();
      if (p && p.catch) {
        p.catch(function (err) {
          lim('play', 'play() was refused: ' + err);
          if (++playTries < 6) setTimeout(tryPlay, 1000);
        });
      }
    } catch (e) { lim('play', 'play() threw: ' + e); }
  }

  function levelLabel(l) {
    var res = l.height ? l.height + 'p' : 'Video';
    return l.bitrate ? res + ' · ' + (l.bitrate / 1e6).toFixed(1) + ' Mbps' : res;
  }
  // Level indexes, best first (same order the phone's quality list uses).
  function order() {
    var idx = [];
    if (!hls) return idx;
    for (var i = 0; i < hls.levels.length; i++) idx.push(i);
    idx.sort(function (a, b) {
      var A = hls.levels[a], Bl = hls.levels[b];
      return ((Bl.height || 0) - (A.height || 0)) || ((Bl.bitrate || 0) - (A.bitrate || 0));
    });
    return idx;
  }

  function onError(d) {
    var msg = d.type + ' / ' + d.details +
      (d.response && d.response.code ? ' (server said ' + d.response.code + ')' : '') +
      (d.url ? ' ' + short(d.url) : '') +
      (d.reason ? ': ' + d.reason : '') +
      (d.error && d.error.message && !d.reason ? ': ' + d.error.message : '');
    if (!d.fatal) { lim('nf-' + d.details, 'hls.js (recoverable): ' + msg); return; }
    log('hls.js FATAL: ' + msg);
    lastRecoverAt = Date.now();
    if (d.type === Hls.ErrorTypes.MEDIA_ERROR) {
      if (mediaRecovers === 0) {
        mediaRecovers = 1;
        log('Trying to recover from the media error');
        hls.recoverMediaError();
      } else if (!swapped) {
        swapped = true;
        log('Trying again with the audio codec swapped');
        hls.swapAudioCodec();
        hls.recoverMediaError();
      } else {
        fail('media error that recovery could not fix (' + d.details + ')');
      }
    } else if (d.type === Hls.ErrorTypes.NETWORK_ERROR) {
      var code = d.response && d.response.code;
      var refused = code === 401 || code === 403 || code === 404;
      if (!refused && netRecovers < 1) {
        netRecovers++;
        log('Network trouble; restarting the download once');
        hls.startLoad();
      } else {
        fail('network error (' + d.details + (code ? ', server said ' + code : '') + ')');
      }
    } else {
      fail('unrecoverable error (' + d.details + ')');
    }
  }

  function start() {
    if (typeof Hls === 'undefined') return fail('hls.js is not bundled in this build');
    if (!Hls.isSupported()) return fail('this WebView has no Media Source Extensions');
    // Tuned for a weak TV box: worker on, no low-latency mode, ~3 segments behind live,
    // modest buffer both ways.
    hls = new Hls({
      enableWorker: true,
      lowLatencyMode: false,
      liveSyncDurationCount: 3,
      maxBufferLength: 30,
      maxMaxBufferLength: 60,
      backBufferLength: 10
    });
    log('hls.js ' + Hls.version + ' started');
    var E = Hls.Events;
    hls.on(E.MEDIA_ATTACHED, function () { hls.loadSource(cfg.url); });
    hls.on(E.MANIFEST_PARSED, function (ev, d) {
      var ls = (d.levels || []).slice(0, 6).map(levelLabel).join('; ');
      log('Playlist parsed: ' + (d.levels || []).length + ' quality level(s)' + (ls ? ' (' + ls + ')' : '') +
        ', ' + ((d.audioTracks || []).length) + ' audio track(s)');
      tryPlay();
    });
    hls.on(E.LEVEL_LOADED, function (ev, d) {
      var det = d.details || {};
      isLive = !!det.live;
      once('lvl-loaded', 'Level playlist: ' + (isLive ? 'live' : 'on-demand') + ', ' +
        (det.fragments ? det.fragments.length : '?') + ' chunks, target duration ' + det.targetduration + 's');
    });
    hls.on(E.LEVEL_SWITCHED, function (ev, d) {
      var l = hls.levels[d.level];
      lim('lvl-switch', 'Quality level now ' + (l ? levelLabel(l) : d.level));
    });
    hls.on(E.FRAG_BUFFERED, function () { once('frag', 'First chunk buffered'); });
    hls.on(E.ERROR, function (ev, d) { try { onError(d); } catch (e) { fail('error handler threw: ' + e); } });
    hls.attachMedia(v);
  }

  // ---- Video element events ----
  v.addEventListener('playing', function () {
    if (!everPlayed) {
      everPlayed = true;
      log('Video is playing (' + v.videoWidth + 'x' + v.videoHeight + ')');
      try { B.started(gen); } catch (e) {}
    }
  });
  v.addEventListener('waiting', function () {
    if (everPlayed) lim('waiting', 'Buffering at ' + v.currentTime.toFixed(1) + 's (video is waiting for data)');
  });
  v.addEventListener('stalled', function () { lim('stalled', 'Browser reports the download stalled'); });
  v.addEventListener('error', function () {
    var e = v.error;
    lim('velerr', 'Video element error ' + (e ? e.code + ' ' + (e.message || '') : '?'));
  });
  v.addEventListener('ended', function () {
    if (isLive && everPlayed) fail('the live stream ended');
    else log('Video finished');
  });

  // ---- Watchdog: no progress for 20s while not paused, or never started ----
  setInterval(function () {
    if (failed) return;
    var now = Date.now(), t = v.currentTime;
    if (v.paused || v.ended) {
      lastProgress = now; lastTime = t;
    } else if (Math.abs(t - lastTime) > 0.05) {
      lastTime = t; lastProgress = now;
    } else if (now - lastProgress > 20000) {
      return fail('no playback progress for 20s');
    }
    if (!everPlayed && !userPaused && now - t0 > 25000) return fail('the video never started (25s)');
    // 60s of smooth playback after a recovery: forgive earlier errors.
    if (lastRecoverAt && now - lastRecoverAt > 60000 && now - lastProgress < 3000) {
      lastRecoverAt = 0; mediaRecovers = 0; swapped = false; netRecovers = 0;
    }
  }, 1000);

  // ---- API for the app ----
  window.SLC = {
    play: function () { userPaused = false; tryPlay(); },
    pause: function () { userPaused = true; try { v.pause(); } catch (e) {} },
    toggle: function () { if (v.paused) window.SLC.play(); else window.SLC.pause(); },
    live: function () {
      var p = hls && hls.liveSyncPosition;
      if ((p === null || p === undefined || !isFinite(p)) && v.seekable && v.seekable.length) {
        p = v.seekable.end(v.seekable.length - 1) - 3;
      }
      if (p !== null && p !== undefined && isFinite(p)) {
        v.currentTime = Math.max(0, p);
        log('Jumped to the live point');
      }
      window.SLC.play();
    },
    quality: function (pos) {
      if (!hls) return;
      if (pos < 0) { hls.currentLevel = -1; log('Quality set to Auto'); return; }
      var o = order();
      if (pos < o.length) { hls.currentLevel = o[pos]; log('Quality set to ' + levelLabel(hls.levels[o[pos]])); }
    },
    state: function () {
      var o = order();
      var q = -1;
      if (hls && hls.manualLevel >= 0) q = o.indexOf(hls.manualLevel);
      var ready = v.readyState >= 3;
      return {
        playing: !v.paused && !v.ended && ready,
        buffering: !v.paused && !v.ended && !ready,
        live: isLive,
        levels: o.map(function (i) { return levelLabel(hls.levels[i]); }),
        quality: q,
        time: v.currentTime
      };
    }
  };

  v.muted = false; v.volume = 1;
  start();
})();
