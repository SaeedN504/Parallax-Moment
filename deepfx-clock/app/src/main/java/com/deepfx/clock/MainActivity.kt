package com.deepfx.clock

import android.annotation.SuppressLint
import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.view.*
import android.webkit.*
import android.widget.*
import androidx.exifinterface.media.ExifInterface
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors
import kotlin.math.*

class MainActivity:Activity() {
    private lateinit var web:WebView
    private lateinit var preview:Preview
    private val worker=Executors.newSingleThreadExecutor()
    private val handler=Handler(Looper.getMainLooper())
    @Volatile private var config=ClockSettings()
    private var scene:Scene?=null
    private var busy=false
    private var target="clock"
    private var pendingSave:Runnable?=null
    private var refinedThreshold=.5f
    private var refinedFeather=1f
    private var resumed=false
    private val pickerCode=41

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(8,10,18)) }
        root.setOnApplyWindowInsetsListener { v,insets ->
            @Suppress("DEPRECATION")
            v.setPadding(insets.systemWindowInsetLeft,insets.systemWindowInsetTop,insets.systemWindowInsetRight,insets.systemWindowInsetBottom)
            insets
        }
        val frame=FrameLayout(this)
        preview=Preview()
        frame.addView(preview,FrameLayout.LayoutParams(1,1,Gravity.CENTER))
        frame.addOnLayoutChangeListener { _,l,t,r,b,_,_,_,_ ->
            val ratio=resources.displayMetrics.widthPixels.toFloat()/resources.displayMetrics.heightPixels
            val ph=min(b-t,((r-l)/ratio).toInt()).coerceAtLeast(1)
            val pw=(ph*ratio).toInt().coerceAtLeast(1)
            if(preview.layoutParams.width!=pw || preview.layoutParams.height!=ph)
                preview.layoutParams=FrameLayout.LayoutParams(pw,ph,Gravity.CENTER)
        }
        root.addView(frame,LinearLayout.LayoutParams(-1,0,1.2f))
        web=WebView(this).apply { setBackgroundColor(Color.rgb(12,16,30)) }
        val loader=WebViewAssetLoader.Builder().addPathHandler("/assets/",WebViewAssetLoader.AssetsPathHandler(this)).build()
        web.settings.apply {
            javaScriptEnabled=true; allowFileAccess=false; allowContentAccess=false
            mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
            domStorageEnabled=false
        }
        web.webViewClient=object:WebViewClient() {
            override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):WebResourceResponse? =
                loader.shouldInterceptRequest(request.url) ?: WebResourceResponse("text/plain","UTF-8",403,"Blocked",emptyMap(),ByteArrayInputStream(ByteArray(0)))
            override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean = true
            override fun onPageFinished(view:WebView,url:String) { syncUi(); status("Import a photo. AI processing stays on this device.") }
        }
        web.addJavascriptInterface(Bridge(),"DeepFX")
        root.addView(web,LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root); root.requestApplyInsets()
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html")
        work("Loading saved scene…") {
            val loaded=SceneStore.load(this)
            if(loaded!=null) runOnUiThread {
                if(isDestroyed) loaded.scene.close() else {
                    scene?.close(); scene=loaded.scene; config=loaded.settings
                    refinedThreshold=config.threshold; refinedFeather=config.feather
                    preview.invalidate(); syncUi()
                }
            }
        }
    }
    private fun status(message:String) {
        runOnUiThread { if(!isDestroyed) web.evaluateJavascript("window.setStatus&&setStatus(${JSONObject.quote(message)})",null) }
    }
    private fun syncUi() { if(!isDestroyed) web.evaluateJavascript("window.receiveSettings&&receiveSettings(${config.json()})",null) }
    private fun work(message:String,task:()->Unit) {
        if(busy) { status("Please wait for the current operation."); return }
        busy=true; status(message)
        worker.execute {
            var error:String?=null
            try { task() } catch(e:Exception) { error=e.message ?: "Operation failed" }
            catch(_:OutOfMemoryError) { error="Not enough memory for this photo. Close other apps and try a smaller image." }
            val failure=error
            runOnUiThread { busy=false; if(!isDestroyed) status(failure ?: "Ready — changes are saved on this device.") }
        }
    }
    private fun reloadPreview() {
        val loaded=SceneStore.load(this) ?: return
        runOnUiThread {
            if(isDestroyed) loaded.scene.close() else {
                scene?.close(); scene=loaded.scene; preview.invalidate()
                refinedThreshold=loaded.settings.threshold; refinedFeather=loaded.settings.feather
            }
        }
    }
    private fun persist() {
        if(busy) { pendingSave=Runnable { persist() }.also { handler.postDelayed(it,500) }; return }
        val value=config
        val refine=value.threshold!=refinedThreshold || value.feather!=refinedFeather
        work(if(refine) "Refining cutout…" else "Saving settings…") {
            if(refine) { SceneStore.refine(this,value); reloadPreview() }
            else SceneStore.writeSettings(this,value)
        }
    }
    inner class Bridge {
        @JavascriptInterface fun pick() { runOnUiThread {
            if(busy) { status("Please wait for AI processing."); return@runOnUiThread }
            val intent=if(Build.VERSION.SDK_INT>=33) Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*")
                else Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
            try { startActivityForResult(intent,pickerCode) } catch(_:Exception) { status("No system photo picker is available.") }
        } }
        @JavascriptInterface fun update(json:String) {
            val value=try { ClockSettings.parse(json) } catch(_:Exception) { status("Invalid settings"); return }
            runOnUiThread {
                config=value; preview.invalidate()
                pendingSave?.let { handler.removeCallbacks(it) }
                pendingSave=Runnable { persist() }.also { handler.postDelayed(it,350) }
            }
        }
        @JavascriptInterface fun editTarget(value:String) { runOnUiThread { target=if(value in setOf("clock","date","badge")) value else "clock"; preview.invalidate() } }
        @JavascriptInterface fun applyLive() { runOnUiThread {
            if(busy || scene==null) { status("Import a photo and wait for processing first."); return@runOnUiThread }
            pendingSave?.let { handler.removeCallbacks(it) }
            work("Saving live wallpaper…") {
                SceneStore.refine(this@MainActivity,config); reloadPreview()
                runOnUiThread {
                    if(!isDestroyed) try {
                        startActivity(Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,ComponentName(this@MainActivity,DeepFxWallpaperService::class.java)))
                    } catch(_:Exception) { status("This launcher cannot open the live wallpaper picker.") }
                }
            }
        } }
        @JavascriptInterface fun applyStatic() { runOnUiThread {
            if(busy || scene==null) { status("Import a photo and wait for processing first."); return@runOnUiThread }
            pendingSave?.let { handler.removeCallbacks(it) }
            val value=config
            work("Rendering static wallpaper…") {
                SceneStore.refine(this@MainActivity,value); reloadPreview()
                val loaded=SceneStore.load(this@MainActivity) ?: error("No saved scene")
                val dm=resources.displayMetrics
                val bitmap=Bitmap.createBitmap(dm.widthPixels,dm.heightPixels,Bitmap.Config.ARGB_8888)
                try {
                    LayerRenderer().draw(Canvas(bitmap),loaded.scene,value,0f)
                    SceneStore.saveBitmap(File(filesDir,"depth-wallpaper.jpg"),bitmap,Bitmap.CompressFormat.JPEG)
                    WallpaperManager.getInstance(this@MainActivity).setBitmap(bitmap,null,true,WallpaperManager.FLAG_SYSTEM)
                } finally { bitmap.recycle(); loaded.scene.close() }
            }
        } }
    }
    @Deprecated("Activity result compatibility for API 26")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode!=pickerCode || resultCode!=RESULT_OK) return
        val uri=data?.data ?: return
        work("Generating subject cutout and depth offline…") {
            val photo=decode(uri)
            var mask:Bitmap?=null; var depth:Bitmap?=null
            try {
                val inference=Inference(this)
                mask=inference.mask(photo); status("Subject isolated. Generating MiDaS depth…")
                depth=inference.depth(photo)
                SceneStore.save(this,photo,mask,depth,config)
                reloadPreview()
            } finally { photo.recycle(); mask?.recycle(); depth?.recycle() }
        }
    }
    private fun decode(uri:Uri):Bitmap {
        if(Build.VERSION.SDK_INT>=28) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver,uri)) { decoder,info,_ ->
                val factor=min(1f,1600f/max(info.size.width,info.size.height))
                decoder.setTargetSize(max(1,(info.size.width*factor).toInt()),max(1,(info.size.height*factor).toInt()))
                decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val opts=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it,null,opts) }
        require(opts.outWidth>0 && opts.outHeight>0) { "Unsupported image" }
        var sample=1
        while(max(opts.outWidth,opts.outHeight)/sample>1600) sample*=2
        val photo=contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it,null,BitmapFactory.Options().apply { inSampleSize=sample }) } ?: error("Cannot decode image")
        val orientation=contentResolver.openInputStream(uri).use { stream -> if(stream==null) 1 else ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION,1) }
        val matrix=Matrix()
        when(orientation) {
            2->matrix.setScale(-1f,1f); 3->matrix.setRotate(180f); 4->matrix.setScale(1f,-1f)
            5->{ matrix.setRotate(90f); matrix.postScale(-1f,1f) }
            6->matrix.setRotate(90f)
            7->{ matrix.setRotate(-90f); matrix.postScale(-1f,1f) }
            8->matrix.setRotate(-90f)
        }
        val rotated=Bitmap.createBitmap(photo,0,0,photo.width,photo.height,matrix,true)
        if(rotated !== photo) photo.recycle()
        return rotated
    }
    inner class Preview:View(this@MainActivity) {
        private val renderer=LayerRenderer()
        private val started=SystemClock.elapsedRealtime()
        private val handle=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.CYAN; style=Paint.Style.STROKE; strokeWidth=2f }
        private val scaleGesture=ScaleGestureDetector(context,object:ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector:ScaleGestureDetector):Boolean {
                if(target=="clock") { config=config.copy(scale=config.scale*detector.scaleFactor).validated(); invalidate(); syncUi() }
                return true
            }
        })
        override fun onDraw(canvas:Canvas) {
            super.onDraw(canvas)
            renderer.draw(canvas,scene,config,(SystemClock.elapsedRealtime()-started)/1000f)
            if(scene!=null) {
                val x=when(target) { "date"->config.dateX; "badge"->config.badgeX; else->config.x }*width
                val y=when(target) { "date"->config.dateY; "badge"->config.badgeY; else->config.y }*height
                canvas.drawCircle(x,y,10f,handle)
            }
            if(resumed) postInvalidateDelayed(1000L/config.fps)
        }
        override fun performClick():Boolean { super.performClick(); return true }
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event:MotionEvent):Boolean {
            scaleGesture.onTouchEvent(event)
            if(scene==null) return true
            if(!scaleGesture.isInProgress && event.pointerCount==1 && (event.actionMasked==MotionEvent.ACTION_DOWN || event.actionMasked==MotionEvent.ACTION_MOVE)) {
                val x=(event.x/width).coerceIn(0f,1f); val y=(event.y/height).coerceIn(0f,1f)
                config=when(target) { "date"->config.copy(dateX=x,dateY=y); "badge"->config.copy(badgeX=x,badgeY=y); else->config.copy(x=x,y=y) }
                invalidate(); syncUi()
            }
            if(event.actionMasked==MotionEvent.ACTION_UP) { performClick(); pendingSave?.let { handler.removeCallbacks(it) }; pendingSave=Runnable { persist() }.also { handler.postDelayed(it,350) } }
            return true
        }
    }
    override fun onResume() { super.onResume(); resumed=true; if(::preview.isInitialized) preview.invalidate(); if(::web.isInitialized) web.onResume() }
    override fun onPause() { resumed=false; if(::web.isInitialized) web.onPause(); super.onPause() }
    override fun onDestroy() {
        resumed=false; handler.removeCallbacksAndMessages(null); worker.shutdown()
        scene?.close(); scene=null
        if(::web.isInitialized) { web.removeJavascriptInterface("DeepFX"); web.destroy() }
        super.onDestroy()
    }
}
