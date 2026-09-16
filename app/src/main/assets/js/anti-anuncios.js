// BubuADS Estable — Modulo 1: Bloqueo de anuncios (JS)
// La Capa 1 (red) y la Capa 2 (CSS cosmetico) las aplica Kotlin. Aqui se
// eliminan nodos de anuncios, se saltan los anuncios de video y se podan
// las respuestas JSON. Se inyecta SIEMPRE en cada pagina.
(function () {
  'use strict';
  if (window.__bubuAntiAnuncios) { return; }
  window.__bubuAntiAnuncios = true;

  var HIDE_SELECTOR = [
    'ytd-ad-slot-renderer', 'ytd-display-ad-renderer', 'ytd-in-feed-ad-layout-renderer',
    'ytd-promoted-sparkles-web-renderer', 'ytd-video-masthead-ad-v3-renderer',
    'ytd-banner-promo-renderer', 'ytd-companion-slot-renderer',
    'ytm-ad-slot-renderer', 'ytm-in-feed-ad-layout-renderer',
    'ytm-promoted-sparkles-web-renderer', 'ytm-video-masthead-ad-v3-renderer',
    'ytm-companion-slot-renderer', 'ytm-logo-ad-renderer', 'ytm-display-ad-renderer',
    '#masthead-ad', '#player-ads',
    '.ytp-ad-module', '.ytp-ad-player-overlay', '.ytp-ad-text-overlay',
    '.ytp-ad-image-overlay', '.ytp-ad-skip-button-container', '.ytp-paid-content-overlay',
    '.ytp-ad-badge', '.ytp-ad-overlay-slot', '.ytp-ad-survey-ui',
    '.ytp-ad-blocker-message', '.ytp-ce-element',
    'ytm-consent-bump-v2-renderer', 'ytd-consent-bump-v2-renderer',
    '#consent-bump', '#cookie-notice'
  ].join(',');

  function nuke() {
    var els = document.querySelectorAll(HIDE_SELECTOR);
    for (var i = 0; i < els.length; i++) {
      var e = els[i];
      if (e && e.parentNode) {
        try { e.parentNode.removeChild(e); } catch (err) {}
      }
    }
  }

  function skipAd() {
    var player = document.querySelector('.html5-video-player, ytd-player');
    if (!player) { return; }
    var adActive = player.classList.contains('ad-showing') || player.classList.contains('ad-interrupting');
    if (!adActive && !document.querySelector('.ytp-ad-player-overlay')) { return; }
    var v = document.querySelector('video');
    if (v) {
      try {
        // Silenciar SOLO durante el salto del anuncio y restaurar despues,
        // para no dejar el video real sin sonido.
        var wasMuted = v.muted;
        v.muted = true;
        if (!v.paused && v.duration > 0 && v.currentTime < v.duration - 0.1) {
          v.currentTime = v.duration;
        }
        if (v.paused) {
          v.play().catch(function () {});
        }
        if (!wasMuted) {
          setTimeout(function () { try { v.muted = false; } catch (e) {} }, 400);
        }
      } catch (err) {}
    }
    var skip = document.querySelector(
      '.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button, ' +
      '.ytp-ad-skip-button-container button, #movie_player button[aria-label*="Skip"], ' +
      'button[aria-label*="Skip"], button[aria-label*="Saltar"], button[aria-label*="Omitir"]'
    );
    if (skip) { try { skip.click(); } catch (err) {} }
  }

  // ---- json-prune ----
  var AD_KEYS = {
    adPlacements: 1, adSlots: 1, playerAds: 1,
    adBreakHeartbeatParams: 1, adBreakEndpoint: 1
  };

  function pruneAds(obj, depth) {
    if (depth === undefined) { depth = 0; }
    if (depth > 12) { return; }
    if (obj === null || typeof obj !== 'object') { return; }
    if (Object.prototype.toString.call(obj) === '[object Array]') {
      for (var i = 0; i < obj.length; i++) { pruneAds(obj[i], depth + 1); }
      return;
    }
    for (var k in obj) {
      if (!Object.prototype.hasOwnProperty.call(obj, k)) { continue; }
      if (AD_KEYS[k]) { delete obj[k]; }
      else { pruneAds(obj[k], depth + 1); }
    }
  }

  var origParse = JSON.parse;
  JSON.parse = function (text, reviver) {
    var data = origParse.call(JSON, text, reviver);
    try { pruneAds(data); } catch (err) {}
    return data;
  };

  // ---- isInlinePlaybackNoAd ----
  function isPlaybackApiUrl(u) {
    if (!u || u.indexOf('youtubei/v1/') === -1) { return false; }
    if (u.indexOf('ad_break') !== -1 || u.indexOf('log_event') !== -1 ||
        u.indexOf('/config') !== -1 || u.indexOf('att/get') !== -1) { return false; }
    return u.indexOf('/player') !== -1 || u.indexOf('/get_watch') !== -1 ||
           u.indexOf('/reel_watch_sequence') !== -1;
  }

  function injectNoAd(bodyText) {
    var marker = '"contentPlaybackContext":{';
    var idx = bodyText.indexOf(marker);
    if (idx >= 0) {
      return bodyText.slice(0, idx + marker.length) +
        '"isInlinePlaybackNoAd":true,' + bodyText.slice(idx + marker.length);
    }
    var trimmed = bodyText.trim();
    if (trimmed.charAt(0) === '{' && trimmed.charAt(trimmed.length - 1) === '}') {
      var inner = trimmed.slice(1, trimmed.length - 1).trim();
      var payload = '"contentPlaybackContext":{"isInlinePlaybackNoAd":true}';
      // Evita coma final si el objeto estaba vacio ({}).
      return inner.length === 0 ? '{' + payload + '}' : '{' + payload + ',' + inner + '}';
    }
    return bodyText;
  }

  var origFetch = window.fetch;
  if (origFetch) {
    window.fetch = function (input, init) {
      try {
        var url = typeof input === 'string' ? input : (input && input.url);
        var method = (init && init.method) || (input && input.method) || 'GET';
        if (method === 'POST' && isPlaybackApiUrl(url) && init && typeof init.body === 'string') {
          init = Object.assign({}, init, { body: injectNoAd(init.body) });
        }
      } catch (err) {}
      return origFetch.call(this, input, init).then(function (resp) {
        try {
          var ct = (resp.headers.get('content-type') || '');
          if (ct.indexOf('json') === -1) { return resp; }
          return resp.clone().text().then(function (text) {
            try {
              var data = origParse.call(JSON, text);
              pruneAds(data);
              var h = new Headers();
              resp.headers.forEach(function (v, k) {
                if (k === 'content-length' || k === 'content-encoding' || k === 'transfer-encoding') { return; }
                h.append(k, v);
              });
              return new Response(JSON.stringify(data), {
                status: resp.status, statusText: resp.statusText, headers: h
              });
            } catch (err) { return resp; }
          });
        } catch (err) { return resp; }
      });
    };
  }

  var xhrOpen = XMLHttpRequest.prototype.open;
  var xhrSend = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open = function (method, url) {
    this.__bubuUrl = url;
    this.__bubuMethod = method;
    return xhrOpen.apply(this, arguments);
  };
  XMLHttpRequest.prototype.send = function (body) {
    try {
      if (this.__bubuMethod === 'POST' && isPlaybackApiUrl(this.__bubuUrl) && typeof body === 'string') {
        body = injectNoAd(body);
      }
    } catch (err) {}
    return xhrSend.call(this, body);
  };

  // ---- Poda de globals ----
  var globalsPruned = false;
  function pruneGlobals() {
    if (globalsPruned) { return; }
    try {
      if (window.ytInitialPlayerResponse) { pruneAds(window.ytInitialPlayerResponse); }
      if (window.ytInitialData) { pruneAds(window.ytInitialData); }
      globalsPruned = true;
    } catch (e) {}
  }

  // ---- Ciclo de limpieza de anuncios ----
  if (window.__bubuAdsInterval) { clearInterval(window.__bubuAdsInterval); }
  window.__bubuAdsInterval = setInterval(function () { nuke(); skipAd(); pruneGlobals(); }, 2000);
  document.addEventListener('DOMContentLoaded', function () { nuke(); pruneGlobals(); });
  nuke();
  pruneGlobals();
})();
