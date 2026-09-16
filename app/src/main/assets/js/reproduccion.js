// BubuADS Estable — Modulo 2: Segundo plano, pagina y audio
// Mantiene la reproduccion en segundo plano, detecta el tipo de pagina
// (para acotar el CSS) y fuerza el audio siempre activo.
(function () {
  'use strict';
  if (window.__bubuReproduccion) { return; }
  window.__bubuReproduccion = true;

  // ===================== REPRODUCCION EN SEGUNDO PLANO =====================
  // Sistema anti-pause agresivo:
  // 1. Override document.hidden / visibilityState / hasFocus
  // 2. Bloquear listeners visibilitychange
  // 3. Override HTMLVideoElement.prototype.pause (NUNCA permite pausa en bg)
  // 4. Override HTMLVideoElement.prototype.play (siempre funciona)
  // 5. Parchear CADA video nuevo que YouTube cree (MutationObserver)
  // 6. Forzar play cada 1 segundo via setInterval (backup)

  // ---- Propiedades del documento ----
  try {
    Object.defineProperty(document, 'hidden', {
      get: function () { return false; },
      configurable: true
    });
    Object.defineProperty(document, 'visibilityState', {
      get: function () { return 'visible'; },
      configurable: true
    });
  } catch (e) {}
  try {
    document.hasFocus = function () { return true; };
  } catch (e) {}

  // ---- Bloquear SOLO el listener de visibilitychange del document ----
  // NO bloquear EventTarget.prototype.addEventListener globalmente porque
  // YouTube usa visibilitychange internamente para controles del player (fullscreen, etc.)
  try {
    var origDocAddEvent = document.addEventListener.bind(document);
    document.addEventListener = function (type, fn, opt) {
      if (type === 'visibilitychange') { return; }
      return origDocAddEvent(type, fn, opt);
    };
  } catch (e) {}

  // ---- Flags de estado ----
  // __bubuBg: true cuando la app esta en background (Kotlin lo pone en onPause)
  // __bubuUserPaused: true cuando el usuario pauso manualmente
  if (typeof window.__bubuBg === 'undefined') { window.__bubuBg = false; }
  if (typeof window.__bubuUserPaused === 'undefined') { window.__bubuUserPaused = false; }

  // ---- Override de pause/play en el PROTOTYPE ----
  // Esto afecta a TODOS los video elements, incluyendo los que YouTube cree despues.
  var _origPause = HTMLVideoElement.prototype.pause;
  HTMLVideoElement.prototype.pause = function () {
    // Bloquear pause solo si:
    // 1. La app esta en background (__bubuBg === true)
    // 2. El usuario NO pauso manualmente (__bubuUserPaused === false)
    if (window.__bubuBg && !window.__bubuUserPaused) {
      return undefined;
    }
    return _origPause.apply(this, arguments);
  };

  var _origPlay = HTMLVideoElement.prototype.play;
  HTMLVideoElement.prototype.play = function () {
    return _origPlay.apply(this, arguments);
  };

  // ---- Parchear video elements individuales ----
  // YouTube a veces captura la referencia original de pause ANTES de nuestro
  // override del prototype. Parcheamos cada instancia directamente.
  function patchVideoInstance(v) {
    if (!v || v.__jamosPatched) return;
    try {
      Object.defineProperty(v, '__jamosPatched', { value: true, configurable: false });
      var instPause = v.pause;
      v.pause = function () {
        if (window.__bubuBg && !window.__bubuUserPaused) {
          return undefined;
        }
        return instPause.apply(this, arguments);
      };
    } catch (e) {}
  }

  // Parchear video elements existentes
  function patchAllVideos() {
    var vids = document.querySelectorAll('video');
    for (var i = 0; i < vids.length; i++) { patchVideoInstance(vids[i]); }
  }
  patchAllVideos();

  // MutationObserver: parchear video nuevos cuando YouTube los cree
  // Limpiar observer previo si existiera (re-inyeccion)
  if (window.__bubuVideoObserver) { window.__bubuVideoObserver.disconnect(); }
  try {
    var _videoObserver = new MutationObserver(function (mutations) {
      for (var i = 0; i < mutations.length; i++) {
        var nodes = mutations[i].addedNodes;
        for (var j = 0; j < nodes.length; j++) {
          var node = nodes[j];
          if (node.nodeName === 'VIDEO') { patchVideoInstance(node); }
          if (node.querySelectorAll) {
            var vids = node.querySelectorAll('video');
            for (var k = 0; k < vids.length; k++) { patchVideoInstance(vids[k]); }
          }
        }
      }
    });
    _videoObserver.observe(document.documentElement || document.body || document, {
      childList: true, subtree: true
    });
    window.__bubuVideoObserver = _videoObserver;
  } catch (e) {}

  // ---- Metadata del video actual ----
  function getVideoInfo() {
    var info = { title: '', artist: '', thumbnail: '' };
    try {
      var titleEl =
        document.querySelector('h1.title') ||
        document.querySelector('#title h1') ||
        document.querySelector('h1.ytd-watch-metadata yt-formatted-string') ||
        document.querySelector('.title.ytd-watch-metadata yt-formatted-string') ||
        document.querySelector('ytd-watch-metadata h1 yt-formatted-string') ||
        document.querySelector('#info-contents h1');
      if (titleEl && titleEl.textContent.trim()) {
        info.title = titleEl.textContent.trim();
      } else {
        var dt = document.title || '';
        var sep = dt.indexOf(' - ');
        info.title = sep > 0 ? dt.substring(0, sep).trim() : dt.trim();
      }
      var artistEl =
        document.querySelector('#channel-name a') ||
        document.querySelector('#owner #channel-name a') ||
        document.querySelector('#channel-name yt-formatted-string a') ||
        document.querySelector('ytd-channel-name yt-formatted-string a') ||
        document.querySelector('.media-item-byline a');
      if (artistEl) info.artist = (artistEl.textContent || '').trim();
      var vid = null;
      var urlMatch = location.pathname.match(/\/(?:shorts\/|watch\?v=|v\/)([\w-]{11})/);
      if (urlMatch) { vid = urlMatch[1]; }
      if (!vid) {
        var q = new URLSearchParams(location.search).get('v');
        if (q && q.length === 11) { vid = q; }
      }
      if (vid) {
        info.thumbnail = 'https://i.ytimg.com/vi/' + vid + '/hqdefault.jpg';
      }
    } catch (e) {}
    return info;
  }

  // ---- Bridge: reportar estado al servicio cada 2 segundos ----
  var checkAndNotify = function () {
    var v = document.querySelector('video');
    var playing = v && !v.paused && v.currentTime > 0 && !v.ended;
    try {
      var vi = getVideoInfo();
      var payload = JSON.stringify({
        playing: playing,
        title: vi.title,
        artist: vi.artist,
        thumbnail: vi.thumbnail,
        position: v ? Math.floor(v.currentTime * 1000) : 0,
        duration: v && v.duration > 0 ? Math.floor(v.duration * 1000) : 0
      });
      if (window.BubuBridge) {
        window.BubuBridge.onPlaybackStateChanged(payload);
      }
    } catch (e) {}

    // Forzar play si estamos en background y el video se pauso solo
    if (window.__bubuBg && !window.__bubuUserPaused &&
        v && v.paused && !v.ended) {
      try {
        v.muted = false;
        v.play().catch(function () {});
      } catch (e) {}
    }
  };

  if (window.__bubuNotifyInterval) { clearInterval(window.__bubuNotifyInterval); }
  window.__bubuNotifyInterval = setInterval(checkAndNotify, 1000);

  // ---- Force-play ultra-agresivo para background ----
  // Este intervalo corre cada 500ms y fuerza play SIN importar el estado
  // Solo activo cuando __bubuBg es true
  if (window.__bubuBgForceInterval) { clearInterval(window.__bubuBgForceInterval); }
  window.__bubuBgForceInterval = setInterval(function () {
    if (!window.__bubuBg || window.__bubuUserPaused) return;
    try {
      var v = document.querySelector('video');
      if (v && v.paused && !v.ended) {
        v.muted = false;
        v.play().catch(function () {});
      }
    } catch (e) {}
  }, 500);


  // ---- Detector de fullscreen (comunica a Kotlin via bridge) ----
  if (window.__bubuFsInterval) { clearInterval(window.__bubuFsInterval); }
  var _lastFs = false;
  window.__bubuFsInterval = setInterval(function () {
    try {
      var isFs = false;
      // 1. Fullscreen nativo del navegador (API fullscreen) - vale en cualquier pagina
      var el = document.fullscreenElement || document.webkitFullscreenElement;
      if (el && el.tagName) isFs = true;
      // En Shorts el video llena la pantalla por diseño: NO es fullscreen.
      // Solo se considera fullscreen si se uso la API nativa (arriba).
      if (!isFs && currentPageType() !== 'shorts') {
        // 2. YouTube theater mode / expanded player
        var mp = document.querySelector('#movie_player');
        if (mp) {
          var cls = mp.className || '';
          if (cls.indexOf('ytp-fullscreen') >= 0 || cls.indexOf('theater') >= 0) isFs = true;
        }
        // 3. Video occupies > 95% width AND > 75% height
        if (!isFs) {
          var v = document.querySelector('video');
          if (v && v.getBoundingClientRect) {
            var r = v.getBoundingClientRect();
            if (r.width >= window.innerWidth * 0.95 && r.height >= window.innerHeight * 0.75) isFs = true;
          }
        }
        // 4. YouTube pone la clase 'fullscreen' en el player container
        if (!isFs) {
          var pc = document.querySelector('[class*="fullscreen-mode"],[class*="player-fullscreen"]');
          if (pc) isFs = true;
        }
      }
      if (isFs !== _lastFs) {
        _lastFs = isFs;
        if (window.BubuBridge && window.BubuBridge.onFullscreenChanged) {
          window.BubuBridge.onFullscreenChanged(isFs);
        }
      }
    } catch (e) {}
  }, 1500);

  // ---- Bloquear intentos de YouTube de mostrar barras del sistema ----
  try {
    var _origFocus = HTMLElement.prototype.focus;
    HTMLElement.prototype.focus = function () {
      if (this === document.body || this === document.documentElement) { return; }
      return _origFocus.apply(this, arguments);
    };
  } catch (e) {}

  // ---- Detectar tipo de pagina y anadir clase al documento ----
  // Permite acotar el CSS a cada tipo de pagina (home, watch, shorts, search).
  function currentPageType() {
    var p = location.pathname || '';
    if (p.indexOf('/shorts') !== -1) return 'shorts';
    if (p.indexOf('/watch') !== -1 || p.indexOf('/embed') !== -1) return 'watch';
    if (p.indexOf('/results') !== -1 || p.indexOf('/search') !== -1) return 'search';
    return 'home';
  }

  function updatePageClass() {
    try {
      var type = 'bubu-page-' + currentPageType();
      var classes = ['bubu-page-home', 'bubu-page-watch', 'bubu-page-shorts', 'bubu-page-search'];
      var targets = [
        document.documentElement,
        document.body,
        document.querySelector('ytm-app'),
        document.querySelector('ytd-app')
      ];
      for (var i = 0; i < targets.length; i++) {
        var t = targets[i];
        if (!t || !t.classList) continue;
        for (var c = 0; c < classes.length; c++) { t.classList.remove(classes[c]); }
        t.classList.add(type);
      }
    } catch (e) {}
  }
  updatePageClass();

  // Re-detectar en navegacion SPA (YouTube usa pushState/replaceState).
  try {
    if (!window.__bubuHistoryPatched) {
      window.__bubuHistoryPatched = true;
      var _bubuPush = history.pushState, _bubuReplace = history.replaceState;
      history.pushState = function () { var r = _bubuPush.apply(this, arguments); updatePageClass(); return r; };
      history.replaceState = function () { var r = _bubuReplace.apply(this, arguments); updatePageClass(); return r; };
      window.addEventListener('popstate', updatePageClass);
    }
  } catch (e) {}

  // MutationObserver para re-detectar pagina cuando YouTube cambia el DOM (SPA)
  try {
    if (window.__bubuPageObserver) { window.__bubuPageObserver.disconnect(); }
    window.__bubuPageObserver = new MutationObserver(function () {
      updatePageClass();
    });
    window.__bubuPageObserver.observe(document.documentElement, {
      childList: true, subtree: true
    });
  } catch (e) {}

  // === AUDIO SIEMPRE ACTIVO (solo watch) ===
  // YouTube silencia el autoplay (video.muted=true) y muestra el boton
  // blanco .ytp-unmute. Forzamos muted=false para que suene y ocultamos
  // ese boton por CSS. mediaPlaybackRequiresUserGesture=false ya permite
  // el autoplay con sonido en el WebView.
  function forceAudio() {
    try {
      if (currentPageType() !== 'watch') return;
      var v = document.querySelector('video');
      if (v && v.muted) v.muted = false;
    } catch (e) {}
  }
  forceAudio();
  if (window.__bubuForceAudio) { clearInterval(window.__bubuForceAudio); }
  window.__bubuForceAudio = setInterval(forceAudio, 700);

})();
