package com.deepfx.clock

import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.Choreographer
import android.view.SurfaceHolder
import java.util.concurrent.Executors

class DeepFxWallpaperService:WallpaperService() {
    override fun onCreateEngine():Engine=DeepEngine()
    inner class DeepEngine:Engine(),Choreographer.FrameCallback {
        private val renderer=LayerRenderer()
        private val frames=Choreographer.getInstance()
        private val worker=Executors.newSingleThreadExecutor()
        private val main=Handler(Looper.getMainLooper())
        private var scene:Scene?=null
        private var settings=ClockSettings()
        private var id=""
        private var visible=false
        private var surfaceReady=false
        private var destroyed=false
        private var loading=false
        private var lastFrame=0L
        private var lastCheck=0L
        private val started=SystemClock.elapsedRealtime()
        override fun onCreate(holder:SurfaceHolder) { super.onCreate(holder); reload() }
        override fun onSurfaceCreated(holder:SurfaceHolder) { super.onSurfaceCreated(holder); surfaceReady=true; schedule() }
        override fun onSurfaceChanged(holder:SurfaceHolder,format:Int,width:Int,height:Int) { super.onSurfaceChanged(holder,format,width,height); draw() }
        override fun onSurfaceRedrawNeeded(holder:SurfaceHolder) { draw() }
        override fun onSurfaceDestroyed(holder:SurfaceHolder) { surfaceReady=false; frames.removeFrameCallback(this); super.onSurfaceDestroyed(holder) }
        override fun onVisibilityChanged(value:Boolean) { visible=value; frames.removeFrameCallback(this); if(value) { reload(); schedule() } }
        private fun schedule() { if(visible && surfaceReady && !destroyed) { frames.removeFrameCallback(this); frames.postFrameCallback(this) } }
        override fun doFrame(nanos:Long) {
            if(!visible || !surfaceReady || destroyed) return
            if(nanos-lastFrame>=1_000_000_000L/settings.fps) { lastFrame=nanos; draw() }
            val now=SystemClock.elapsedRealtime()
            if(now-lastCheck>=1000) { lastCheck=now; reload() }
            frames.postFrameCallback(this)
        }
        private fun reload() {
            if(loading || destroyed) return
            loading=true
            val previous=id
            worker.execute {
                var loaded:SceneStore.Loaded?=null
                var updated:ClockSettings?=null
                var failure:String?=null
                try {
                    val rec=SceneStore.record(this@DeepFxWallpaperService)
                    if(rec!=null) {
                        updated=ClockSettings.parse(rec.getJSONObject("clock").toString())
                        if(rec.getString("scene")!=previous) loaded=SceneStore.load(this@DeepFxWallpaperService)
                    }
                } catch(e:Exception) { failure=e.message }
                val result=loaded; val config=updated
                main.post {
                    loading=false
                    if(destroyed) result?.scene?.close()
                    else {
                        if(failure!=null) android.util.Log.w("DeepFX-Clock","Scene reload failed: $failure")
                        if(result!=null) { scene?.close(); scene=result.scene; id=result.id; settings=result.settings }
                        else if(config!=null) settings=config
                        if(surfaceReady) draw()
                    }
                }
            }
        }
        private fun draw() {
            if(!surfaceReady || destroyed || !surfaceHolder.surface.isValid) return
            var canvas:Canvas?=null
            try {
                canvas=surfaceHolder.lockCanvas()
                if(canvas!=null) renderer.draw(canvas,scene,settings,(SystemClock.elapsedRealtime()-started)/1000f)
            } catch(e:RuntimeException) { android.util.Log.w("DeepFX-Clock","Wallpaper surface unavailable",e) }
            finally { if(canvas!=null) try { surfaceHolder.unlockCanvasAndPost(canvas) } catch(_:RuntimeException) {} }
        }
        override fun onDestroy() {
            destroyed=true; visible=false; frames.removeFrameCallback(this)
            worker.shutdown(); scene?.close(); scene=null
            super.onDestroy()
        }
    }
}
