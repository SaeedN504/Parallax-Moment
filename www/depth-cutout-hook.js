/**
 * depth-cutout-hook.js
 *
 * Intercepts DepthAndroid.apply() and sends the subject cutout PNG
 * to saveCutout() first, so the live wallpaper can render the clock
 * behind the subject (iPhone-style depth effect).
 *
 * SETUP (two steps in index.html):
 *
 * 1. Add this script AFTER the main app JS has loaded:
 *    <script src="depth-cutout-hook.js"><\/script>
 *
 * 2. Wherever your segmentation/cutout result is ready, store it:
 *    window.__depthCutoutCanvas = myCanvasOrImageWithCutout;
 *
 *    The canvas/image must be full-image dimensions, with the subject
 *    opaque and the background transparent (ARGB PNG).
 *    If you have the cutout as an ImageBitmap or Image, draw it onto
 *    an offscreen canvas first and store that canvas.
 */
(function () {
  "use strict";

  // Wait for the native bridge to be injected
  if (typeof DepthAndroid === "undefined") return;

  var _realApply = DepthAndroid.apply.bind(DepthAndroid);

  /**
   * Helper: export a canvas or image element as a raw base64 PNG
   * (no data-URL prefix, which is what the Java bridge expects).
   */
  function canvasToBase64(source, width, height) {
    var cv;
    if (source instanceof HTMLCanvasElement) {
      cv = source;
    } else {
      // ImageBitmap, HTMLImageElement, etc.
      cv = document.createElement("canvas");
      cv.width = width || source.width;
      cv.height = height || source.height;
      cv.getContext("2d").drawImage(source, 0, 0, cv.width, cv.height);
    }
    var dataUrl = cv.toDataURL("image/png");
    return dataUrl.slice(dataUrl.indexOf(",") + 1);
  }

  /**
   * Wrapped apply: sends the cutout first, then the wallpaper.
   */
  DepthAndroid.apply = function (base64Image, settingsJson) {
    try {
      var src = window.__depthCutoutCanvas;
      if (src && typeof DepthAndroid.saveCutout === "function") {
        var b64 = canvasToBase64(src);
        DepthAndroid.saveCutout(b64);
      }
    } catch (e) {
      console.warn("[depth-hook] saveCutout failed:", e);
    }
    return _realApply(base64Image, settingsJson);
  };

  console.log("[depth-hook] saveCutout bridge active");
})();
