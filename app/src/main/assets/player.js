(function () {
  "use strict";
  var root = location.pathname.replace(/\/$/, "");
  var playlist = root + "/live.m3u8";
  var video = document.getElementById("video");
  var playButton = document.getElementById("play");
  var reloadButton = document.getElementById("reload");
  var nativeButton = document.getElementById("native");
  var status = document.getElementById("status");
  var details = document.getElementById("details");
  var live = document.getElementById("live");
  var direct = document.getElementById("direct");
  var hls = null, mode = "", ready = false, initialized = false, playing = false;
  var latest = -1, videoFrames = 0, lastVideoUpdate = Date.now(), errors = 0;
  var preferNative = /Tizen|Smart.?TV|SMART-TV/i.test(navigator.userAgent) &&
    !!video.canPlayType("application/vnd.apple.mpegurl");
  direct.href = playlist;

  function message(txt) { status.textContent = txt; }
  function detail(txt) { details.textContent = txt; }

  function playableNative() {
    return !!(video.canPlayType("application/vnd.apple.mpegurl") ||
      video.canPlayType("application/x-mpegURL"));
  }
  function playableMse() {
    return !!(window.Hls && window.Hls.isSupported && window.Hls.isSupported());
  }
  function clearPlayer() {
    if (hls) {
      try { hls.destroy(); } catch (e) {}
      hls = null;
    }
    try { video.pause(); video.removeAttribute("src"); video.load(); } catch (e) {}
    initialized = false;
    playing = false;
    live.style.display = "none";
    playButton.textContent = "Reproduzir ao vivo";
  }
  function started() {
    playing = true;
    errors = 0;
    live.style.display = "inline-block";
    playButton.textContent = "Pausar";
    message("Reproduzindo a transmissão ao vivo");
  }
  function play() {
    var result;
    try {
      video.muted = false;
      result = video.play();
      if (result && result.then) {
        result.then(started, function () {
          message("Toque em Reproduzir ao vivo para liberar vídeo e áudio.");
          playButton.textContent = "Reproduzir ao vivo";
        });
      } else {
        started();
      }
    } catch (e) {
      message("Não foi possível iniciar o vídeo: " + (e.message || "erro de reprodução"));
    }
  }
  function resumeAtLiveEdge() {
    if (!playing || video.paused) return;
    var edge = NaN;
    try {
      if (hls && typeof hls.liveSyncPosition === "number") edge = hls.liveSyncPosition;
      else if (video.seekable.length) edge = video.seekable.end(video.seekable.length - 1) - 2;
      if (isFinite(edge) && edge > 0 && edge - video.currentTime > 10) {
        video.currentTime = Math.max(0, edge);
      }
    } catch (e) {}
  }
  function errorLabel(data) {
    var info = data && (data.details || data.type) || "erro desconhecido";
    return String(info).slice(0, 110);
  }
  function initialize(nativeOnly) {
    if (!ready) {
      message("Aguardando os primeiros segmentos de vídeo no tablet…");
      return;
    }
    clearPlayer();
    var useNative = nativeOnly || (preferNative && playableNative());
    if (!useNative && playableMse()) {
      mode = "HLS.js";
      try {
        hls = new window.Hls({
          enableWorker: true,
          lowLatencyMode: false,
          liveSyncDurationCount: 2,
          liveMaxLatencyDurationCount: 5,
          maxBufferLength: 12,
          backBufferLength: 6,
          manifestLoadingTimeOut: 10000,
          fragLoadingTimeOut: 10000
        });
        hls.on(window.Hls.Events.MEDIA_ATTACHED, function () { hls.loadSource(playlist); });
        hls.on(window.Hls.Events.MANIFEST_PARSED, function () {
          initialized = true;
          message("Vídeo pronto. Toque em Reproduzir ao vivo.");
          playButton.disabled = false;
        });
        hls.on(window.Hls.Events.ERROR, function (event, data) {
          if (!data || !data.fatal) return;
          errors++;
          message("Erro no fluxo de vídeo: " + errorLabel(data));
          if (errors < 3 && data.type === window.Hls.ErrorTypes.NETWORK_ERROR) {
            try { hls.startLoad(); return; } catch (e) {}
          }
          if (errors < 3 && data.type === window.Hls.ErrorTypes.MEDIA_ERROR) {
            try { hls.recoverMediaError(); return; } catch (e) {}
          }
          if (playableNative()) {
            message("Tentando reprodução nativa da TV…");
            initialize(true);
          } else {
            detail("O player leu o link, mas não decodificou o fluxo. Verifique o formato H.264/AAC gerado no tablet.");
            playButton.disabled = false;
          }
        });
        hls.attachMedia(video);
        detail("Motor de vídeo: HLS.js · rede local · áudio habilitado");
      } catch (e) {
        message("Falha ao inicializar HLS.js: " + (e.message || "erro"));
        if (playableNative()) initialize(true);
      }
    } else if (playableNative()) {
      mode = "nativo";
      initialized = true;
      video.src = playlist + "?r=" + Date.now();
      video.load();
      detail("Motor de vídeo: player nativo · rede local · áudio habilitado");
      message("Player nativo pronto. Toque em Reproduzir ao vivo.");
      playButton.disabled = false;
    } else {
      mode = "incompatível";
      message("Este navegador não oferece HLS nativo nem MediaSource para HLS.js.");
      detail("Tente Chrome ou Firefox no tablet; na TV, use o player nativo se disponível.");
      playButton.disabled = true;
    }
  }

  function updateStatus() {
    var xhr = new XMLHttpRequest();
    xhr.onreadystatechange = function () {
      if (xhr.readyState !== 4) return;
      if (xhr.status !== 200) {
        message("Sem resposta do tablet. Verifique se a transmissão continua ativa.");
        return;
      }
      try {
        var data = JSON.parse(xhr.responseText);
        var wasReady = ready;
        ready = !!data.ready;
        latest = Number(data.latest);
        videoFrames = Number(data.frames || 0);
        if (!initialized && ready && !wasReady) {
          message("Transmissão encontrada. Preparando player…");
          initialize(false);
        } else if (!initialized && ready && !hls && mode !== "incompatível") {
          initialize(false);
        } else if (!ready && !initialized) {
          message("Aguardando vídeo do tablet…");
          detail("Quadros: " + videoFrames + " · segmentos disponíveis: " + data.segments);
        }
        if (ready && initialized && playing && video.readyState >= 2) {
          resumeAtLiveEdge();
        }
        lastVideoUpdate = Date.now();
      } catch (e) {
        message("Resposta inválida do servidor de vídeo.");
      }
    };
    xhr.open("GET", root + "/live-status?noCache=" + Date.now(), true);
    xhr.timeout = 5000;
    xhr.send();
  }

  playButton.onclick = function () {
    if (!initialized) {
      initialize(false);
      return;
    }
    if (playing && !video.paused) {
      video.pause();
      playing = false;
      live.style.display = "none";
      playButton.textContent = "Reproduzir ao vivo";
      message("Transmissão pausada");
    } else {
      play();
    }
  };
  reloadButton.onclick = function () {
    message("Reconectando…");
    initialize(false);
  };
  nativeButton.onclick = function () {
    if (mode === "nativo") {
      preferNative = false;
      message("Tentando HLS.js…");
      initialize(false);
    } else {
      if (!playableNative()) {
        message("Este navegador não tem player HLS nativo. Use HLS.js.");
      } else {
        preferNative = true;
        message("Tentando player nativo…");
        initialize(true);
      }
    }
  };
  video.addEventListener("playing", started);
  video.addEventListener("waiting", function () {
    if (playing) message("Carregando o vídeo ao vivo…");
  });
  video.addEventListener("error", function () {
    if (mode === "nativo") {
      message("Player nativo não conseguiu reproduzir HLS.");
      if (playableMse()) {
        preferNative = false;
        initialize(false);
      } else {
        detail("O fluxo pode ter problema de codec ou formato; esta TV não oferece HLS.js.");
      }
    } else if (!hls) {
      message("O vídeo não pôde ser reproduzido.");
    }
  });
  setInterval(updateStatus, 2200);
  setInterval(resumeAtLiveEdge, 5000);
  updateStatus();
}());