/**
 * deepfx-hook.js — Deep FX Clock editor extension.
 *
 * Loaded after the editor page and after depth-cutout-hook.js, so it can:
 *   1. capture the picked photo and hand the clean background layer to the bridge
 *   2. inject the slow-mo motion panel and a WYSIWYG three-layer preview
 *   3. wrap DepthAndroid.apply() so Apply stores the layers and opens the live
 *      wallpaper picker for ClockVeilWallpaperService
 *
 * Everything is defensive: if any part fails the original editor keeps working.
 */
(function () {
  "use strict";
  if (typeof DepthAndroid === "undefined") return;

  var KEY = "deepfx.motion.v1";
  var MOTION_DEFAULTS = {
    driftAmplitude: 1.0,
    driftSpeed: 1.0,
    driftDirection: "horizontal",
    parallaxStrength: 0.55,
    fps: 30
  };

  var motion = readMotion();
  var photoDataUrl = null;
  var photoImage = null;
  var pseudoDepth = null;
  var settings = {};
  var previewRaf = 0;
  var phase = 0;

  function readMotion() {
    try {
      var raw = localStorage.getItem(KEY);
      if (!raw) return Object.assign({}, MOTION_DEFAULTS);
      var parsed = JSON.parse(raw);
      var out = Object.assign({}, MOTION_DEFAULTS);
      for (var k in MOTION_DEFAULTS) {
        if (parsed[k] !== undefined && parsed[k] !== null) out[k] = parsed[k];
      }
      return out;
    } catch (e) {
      return Object.assign({}, MOTION_DEFAULTS);
    }
  }

  function saveMotion() {
    try { localStorage.setItem(KEY, JSON.stringify(motion)); } catch (e) {}
  }

  function loadSettings() {
    try {
      var raw = DepthAndroid.getSettings ? DepthAndroid.getSettings() : "{}";
      settings = JSON.parse(raw || "{}") || {};
    } catch (e) {
      settings = {};
    }
    return settings;
  }

  /* ---------------------------------------------------------------- layers */

  function stripPrefix(base64) {
    return base64.indexOf(",") >= 0 ? base64.slice(base64.indexOf(",") + 1) : base64;
  }

  function downscale(file, maxSide, callback) {
    var reader = new FileReader();
    reader.onload = function () {
      var img = new Image();
      img.onload = function () {
        var scale = Math.min(1, maxSide / Math.max(img.width, img.height));
        var cv = document.createElement("canvas");
        cv.width = Math.max(1, Math.round(img.width * scale));
        cv.height = Math.max(1, Math.round(img.height * scale));
        cv.getContext("2d").drawImage(img, 0, 0, cv.width, cv.height);
        callback(cv.toDataURL("image/jpeg", 0.92), cv.toDataURL("image/jpeg", 0.6));
      };
      img.onerror = function () { callback(null, null); };
      img.src = reader.result;
    };
    reader.onerror = function () { callback(null, null); };
    reader.readAsDataURL(file);
  }

  function capturePhoto(file) {
    if (!file || !/^image\//.test(file.type || "")) return;
    downscale(file, 2200, function (full, preview) {
      if (!full) return;
      photoDataUrl = preview;
      var img = new Image();
      img.onload = function () {
        photoImage = img;
        pseudoDepth = null;
        loadSettings();
        startPreview();
      };
      img.src = preview;
      try {
        var ok = DepthAndroid.savePhoto(stripPrefix(full));
        log(ok ? "background layer saved" : "background layer rejected");
      } catch (e) {
        log("savePhoto failed: " + e);
      }
    });
  }

  document.addEventListener("change", function (event) {
    var target = event.target;
    if (!target || target.tagName !== "INPUT" || target.type !== "file") return;
    var file = target.files && target.files[0];
    if (file) capturePhoto(file);
  }, true);

  /* Pseudo depth for the preview: average luminance per strip column. */
  function buildPseudoDepth(strips) {
    if (!photoImage) return null;
    var cv = document.createElement("canvas");
    cv.width = strips;
    cv.height = 24;
    var ctx = cv.getContext("2d");
    ctx.drawImage(photoImage, 0, 0, strips, 24);
    var data;
    try { data = ctx.getImageData(0, 0, strips, 24).data; } catch (e) { return null; }
    var values = new Array(strips);
    for (var x = 0; x < strips; x++) {
      var sum = 0;
      for (var y = 0; y < 24; y++) {
        var i = (y * strips + x) * 4;
        sum += (0.299 * data[i] + 0.587 * data[i + 1] + 0.114 * data[i + 2]) / 255;
      }
      values[x] = sum / 24;
    }
    return values;
  }

  /* ---------------------------------------------------------------- preview */

  function drawPreview(canvas) {
    var ctx = canvas.getContext("2d");
    var w = canvas.width;
    var h = canvas.height;
    ctx.fillStyle = "#05060a";
    ctx.fillRect(0, 0, w, h);

    var strips = 48;
    var source = photoImage;
    if (!source) {
      ctx.fillStyle = "rgba(255,255,255,.55)";
      ctx.font = "13px sans-serif";
      ctx.textAlign = "center";
      ctx.fillText("Import a photo to preview the depth motion", w / 2, h / 2);
      return;
    }
    if (!pseudoDepth) pseudoDepth = buildPseudoDepth(strips);

    var scale = Math.max(w / source.width, h / source.height) * 1.06;
    var dw = source.width * scale;
    var dh = source.height * scale;
    var dx = (w - dw) / 2;
    var dy = (h - dh) / 2;
    var maxOffset = Math.min(w * 0.035, 42) * (motion.parallaxStrength || 0.55) * (motion.driftAmplitude || 1);
    var wave = Math.sin(phase * Math.PI * 2);
    var vertical = motion.driftDirection === "vertical" || motion.driftDirection === "diagonal";
    var horizontal = motion.driftDirection !== "vertical";

    for (var i = 0; i < strips; i++) {
      var weight = ((pseudoDepth ? pseudoDepth[i] : 0.5) - 0.5) * 2;
      var ox = horizontal ? maxOffset * weight * wave : 0;
      var oy = vertical ? maxOffset * 0.5 * weight * wave : 0;
      var sx = (i * source.width) / strips;
      var sw = source.width / strips;
      ctx.drawImage(
        source, sx, 0, sw, source.height,
        dx + (i * dw) / strips + ox, dy + oy, dw / strips, dh
      );
    }

    drawClockPreview(ctx, w, h);

    var cutout = window.__depthCutoutCanvas;
    if (cutout) {
      var subj = (cfgNumber("subjectScale", 100) / 100) || 1;
      try {
        if (subj !== 1) {
          var cw = dw * subj;
          var ch = dh * subj;
          ctx.drawImage(cutout, dx + (dw - cw) / 2, dy + (dh - ch) / 2, cw, ch);
        } else {
          ctx.drawImage(cutout, dx, dy, dw, dh);
        }
      } catch (e) {}
    }

    var dim = cfgNumber("bgDim", 18) / 100;
    if (dim > 0) {
      ctx.fillStyle = "rgba(0,0,0," + Math.min(0.85, dim) + ")";
      ctx.fillRect(0, 0, w, h);
    }
  }

  function cfgNumber(key, fallback) {
    var value = settings ? Number(settings[key]) : NaN;
    return isFinite(value) ? value : fallback;
  }

  function drawClockPreview(ctx, w, h) {
    var fmt24 = settings.fmt24 === true;
    var showSeconds = settings.showSeconds === true;
    var now = new Date();
    var hours = now.getHours();
    var suffix = hours >= 12 ? "PM" : "AM";
    if (!fmt24) hours = hours % 12 || 12;
    var sep = now.getSeconds() % 2 === 0 ? ":" : " ";
    var text = pad2(hours) + sep + pad2(now.getMinutes());
    if (showSeconds) text += sep + pad2(now.getSeconds());

    var size = Math.max(8, (cfgNumber("size", 480) / 1000) * h);
    var sx = cfgNumber("stretchX", 100) / 100;
    var sy = cfgNumber("stretchY", 100) / 100;
    var ink = settings.ink || "#ffffff";
    var glow = cfgNumber("glowStr", 0) / 100;
    var shadow = cfgNumber("shadowStr", 35) / 100;
    var tracking = cfgNumber("tracking", 0);
    var opacity = cfgNumber("opacity", 100) / 100;

    ctx.save();
    ctx.globalAlpha = Math.max(0, Math.min(1, opacity));
    ctx.translate(cfgNumber("clockX", 0.5) * w, cfgNumber("clockY", 0.4) * h);
    ctx.scale(sx, sy);
    ctx.font = "bold " + size + "px sans-serif-condensed, sans-serif";
    ctx.textAlign = "center";
    ctx.textBaseline = "alphabetic";
    if (glow > 0 || shadow > 0) {
      ctx.shadowColor = "rgba(0,0,0,.6)";
      ctx.shadowBlur = size * (0.02 + 0.06 * glow);
      ctx.shadowOffsetY = size * 0.02 * shadow;
    }
    ctx.fillStyle = ink;
    drawTracked(ctx, text, 0, 0, tracking);
    if (!fmt24) {
      ctx.font = "bold " + size * 0.22 + "px sans-serif-condensed, sans-serif";
      ctx.textAlign = "left";
      drawTracked(ctx, suffix, size * 0.62, 0, tracking * 0.5);
    }
    ctx.restore();

    if (settings.dateEnabled !== false) {
      var dateSize = Math.max(8, (cfgNumber("dateSize", 34) / 1000) * h);
      ctx.save();
      ctx.globalAlpha = Math.max(0, Math.min(1, opacity)) * 0.78;
      ctx.fillStyle = settings.dateInk || ink;
      ctx.textAlign = "center";
      ctx.font = dateSize.toFixed(1) + "px sans-serif";
      ctx.translate(cfgNumber("dateX", 0.5) * w, cfgNumber("dateY", 0.27) * h);
      ctx.scale(cfgNumber("dateStretchX", 100) / 100, 1);
      drawTracked(ctx, dateLabel(now, settings), 0, 0, cfgNumber("dateTracking", 6));
      ctx.restore();
    }
  }

  function dateLabel(now, cfg) {
    var long = (cfg.dateFmt || "long") === "long";
    var opts = long
      ? { weekday: "short", month: "long", day: "numeric" }
      : { weekday: "short", month: "short", day: "numeric" };
    var label = now.toLocaleDateString("en-US", opts);
    return cfg.dateUpper === false ? label : label.toUpperCase();
  }

  function fontSizeOf(ctx) {
    var match = /(\d+(?:\.\d+)?)px/.exec(ctx.font);
    return match ? parseFloat(match[1]) : 0;
  }

  function drawTracked(ctx, text, x, y, tracking) {
    var step = (tracking / 220) * fontSizeOf(ctx);
    if (!step) {
      ctx.fillText(text, x, y);
      return;
    }
    var widths = [];
    var total = step * (text.length - 1);
    for (var i = 0; i < text.length; i++) {
      widths.push(ctx.measureText(text[i]).width);
      total += widths[i];
    }
    var cursor = x - total / 2;
    var prevAlign = ctx.textAlign;
    ctx.textAlign = "left";
    for (var j = 0; j < text.length; j++) {
      ctx.fillText(text[j], cursor, y);
      cursor += widths[j] + step;
    }
    ctx.textAlign = prevAlign;
  }

  function pad2(value) {
    return (value < 10 ? "0" : "") + value;
  }

  function startPreview() {
    var canvas = document.getElementById("deepfx-preview");
    if (!canvas) return;
    if (previewRaf) cancelAnimationFrame(previewRaf);
    var last = performance.now();
    var loop = function (now) {
      var dt = Math.min(64, now - last);
      last = now;
      var period = 16000 / Math.max(0.05, motion.driftSpeed || 1);
      phase = (phase + dt / period) % 1;
      try { drawPreview(canvas); } catch (e) { log("preview failed: " + e); }
      previewRaf = requestAnimationFrame(loop);
    };
    previewRaf = requestAnimationFrame(loop);
  }

  /* ------------------------------------------------------------------ panel */

  function injectPanel() {
    if (document.getElementById("deepfx-panel")) return;

    var style = document.createElement("style");
    style.textContent =
      "#deepfx-fab{position:fixed;right:12px;bottom:96px;z-index:2147483000;padding:10px 14px;border-radius:22px;" +
      "background:#111a2b;color:#cfe3ff;border:1px solid #2a4a7a;font:600 12px sans-serif;letter-spacing:.06em}" +
      "#deepfx-panel{position:fixed;left:8px;right:8px;bottom:8px;z-index:2147483001;max-height:70vh;overflow:auto;" +
      "background:rgba(8,12,20,.97);border:1px solid #24405f;border-radius:16px;padding:12px;color:#dce8f7;" +
      "font:12px sans-serif;box-shadow:0 12px 40px rgba(0,0,0,.6)}" +
      "#deepfx-panel h4{margin:0 0 8px;font:700 12px sans-serif;letter-spacing:.12em;color:#8fb6e8;text-transform:uppercase}" +
      "#deepfx-panel label{display:block;margin:8px 0 2px;color:#9db4cd}" +
      "#deepfx-panel input[type=range]{width:100%}" +
      "#deepfx-panel select{width:100%;background:#0d1522;color:#dce8f7;border:1px solid #24405f;border-radius:8px;padding:6px}" +
      "#deepfx-panel .row{display:flex;gap:8px;margin-top:10px}" +
      "#deepfx-panel button{flex:1;padding:10px;border-radius:10px;border:1px solid #2a4a7a;background:#152036;color:#cfe3ff;font:600 12px sans-serif}" +
      "#deepfx-panel button.primary{background:#1f5fbf;border-color:#3d82e0;color:#fff}" +
      "#deepfx-preview{width:100%;height:190px;border-radius:12px;border:1px solid #24405f;background:#05060a;display:block;margin-top:8px}";
    document.head.appendChild(style);

    var fab = document.createElement("button");
    fab.id = "deepfx-fab";
    fab.textContent = "DEEP FX";
    fab.onclick = function () {
      var panel = document.getElementById("deepfx-panel");
      if (panel) {
        panel.remove();
        if (previewRaf) cancelAnimationFrame(previewRaf);
        previewRaf = 0;
        return;
      }
      buildPanel();
    };
    document.body.appendChild(fab);
  }

  function buildPanel() {
    loadSettings();
    var panel = document.createElement("div");
    panel.id = "deepfx-panel";
    panel.innerHTML =
      "<h4>Cinematic depth motion</h4>" +
      "<canvas id='deepfx-preview' width='720' height='380'></canvas>" +
      "<label>Motion amount <span id='deepfx-ampv'></span></label>" +
      "<input id='deepfx-amp' type='range' min='0' max='3' step='0.05'>" +
      "<label>Drift speed <span id='deepfx-spdv'></span></label>" +
      "<input id='deepfx-spd' type='range' min='0.1' max='3' step='0.05'>" +
      "<label>Depth strength <span id='deepfx-strv'></span></label>" +
      "<input id='deepfx-str' type='range' min='0' max='2' step='0.05'>" +
      "<label>Direction</label>" +
      "<select id='deepfx-dir'>" +
      "<option value='horizontal'>Horizontal</option>" +
      "<option value='vertical'>Vertical</option>" +
      "<option value='diagonal'>Diagonal</option>" +
      "</select>" +
      "<label>Render rate <span id='deepfx-fpsv'></span></label>" +
      "<input id='deepfx-fps' type='range' min='10' max='60' step='1'>" +
      "<div class='row'>" +
      "<button id='deepfx-live' class='primary'>Set live wallpaper</button>" +
      "<button id='deepfx-static'>Static wallpaper</button>" +
      "</div>" +
      "<div class='row'><button id='deepfx-close'>Close</button></div>";
    document.body.appendChild(panel);

    var amp = panel.querySelector("#deepfx-amp");
    var spd = panel.querySelector("#deepfx-spd");
    var str = panel.querySelector("#deepfx-str");
    var dir = panel.querySelector("#deepfx-dir");
    var fps = panel.querySelector("#deepfx-fps");

    function syncLabels() {
      panel.querySelector("#deepfx-ampv").textContent = Number(motion.driftAmplitude).toFixed(2);
      panel.querySelector("#deepfx-spdv").textContent = Number(motion.driftSpeed).toFixed(2) + "x";
      panel.querySelector("#deepfx-strv").textContent = Number(motion.parallaxStrength).toFixed(2);
      panel.querySelector("#deepfx-fpsv").textContent = motion.fps + " fps";
    }

    amp.value = motion.driftAmplitude;
    spd.value = motion.driftSpeed;
    str.value = motion.parallaxStrength;
    dir.value = motion.driftDirection;
    fps.value = motion.fps;
    syncLabels();

    function commit() {
      motion.driftAmplitude = Number(amp.value);
      motion.driftSpeed = Number(spd.value);
      motion.parallaxStrength = Number(str.value);
      motion.driftDirection = dir.value;
      motion.fps = Number(fps.value);
      saveMotion();
      syncLabels();
      pushMotion();
    }
    [amp, spd, str, dir, fps].forEach(function (el) {
      el.addEventListener("input", commit);
      el.addEventListener("change", commit);
    });

    panel.querySelector("#deepfx-close").onclick = function () { fabToggle(); };
    panel.querySelector("#deepfx-live").onclick = function () {
      pushMotion();
      try { DepthAndroid.applyLive(); } catch (e) { log("applyLive failed: " + e); }
    };
    panel.querySelector("#deepfx-static").onclick = function () {
      pushMotion();
      try { DepthAndroid.applyStatic(); } catch (e) { log("applyStatic failed: " + e); }
    };

    startPreview();
    pushMotion();
  }

  function fabToggle() {
    var fab = document.getElementById("deepfx-fab");
    if (fab) fab.click();
  }

  function pushMotion() {
    try {
      if (typeof DepthAndroid.saveMotion === "function") {
        DepthAndroid.saveMotion(JSON.stringify(motion));
      }
    } catch (e) {}
  }

  /* ------------------------------------------------------------------ bridge */

  if (typeof DepthAndroid.startLive !== "function") {
    DepthAndroid.startLive = function () {
      try { DepthAndroid.applyLive(); } catch (e) {}
    };
  }
  if (typeof DepthAndroid.hasWallpaper !== "function") {
    DepthAndroid.hasWallpaper = function () {
      try { return DepthAndroid.hasContent(); } catch (e) { return false; }
    };
  }

  var _apply = DepthAndroid.apply.bind(DepthAndroid);
  DepthAndroid.apply = function (base64Image, settingsJson) {
    var result;
    try {
      result = _apply(base64Image, settingsJson);
    } catch (e) {
      log("apply failed: " + e);
      return "error:" + e;
    }
    try {
      loadSettings();
      pushMotion();
      DepthAndroid.applyLive();
    } catch (e) {
      log("post-apply step failed: " + e);
    }
    return result;
  };

  function log(message) {
    try { if (typeof DepthAndroid.log === "function") DepthAndroid.log("[deepfx] " + message); } catch (e) {}
  }

  function boot() {
    injectPanel();
    loadSettings();
    log("editor ready");
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", boot);
  } else {
    boot();
  }
})();
