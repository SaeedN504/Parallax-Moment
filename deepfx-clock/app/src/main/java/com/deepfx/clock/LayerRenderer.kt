package com.deepfx.clock

import android.content.Context
import android.graphics.*
import android.util.AtomicFile
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

class Scene(val background:Bitmap,val cutout:Bitmap,val depth:Bitmap):Closeable {
    val stripDepth=FloatArray(64) { i ->
        val x=((i+.5f)/64*depth.width).toInt().coerceIn(0,depth.width-1)
        var sum=0f
        for(y in 0 until depth.height) sum+=Color.red(depth.getPixel(x,y))/255f
        sum/depth.height
    }
    override fun close() { background.recycle(); cutout.recycle(); depth.recycle() }
}

/** Atomic settings.json is the commit marker: incomplete image sets never become active. */
object SceneStore {
    data class Loaded(val scene:Scene,val settings:ClockSettings,val id:String)
    private fun root(c:Context)=File(c.filesDir,"scenes").apply { mkdirs() }
    @Synchronized fun record(c:Context):JSONObject? = try {
        JSONObject(AtomicFile(File(c.filesDir,"settings.json")).openRead().bufferedReader().use { it.readText() })
    } catch(_:Exception) { null }
    @Synchronized fun settings(c:Context):ClockSettings = record(c)?.let { ClockSettings.parse(it.getJSONObject("clock").toString()) } ?: ClockSettings()
    @Synchronized fun writeSettings(c:Context,s:ClockSettings,id:String?=null) {
        val sceneId=id ?: record(c)?.optString("scene") ?: return
        require(Regex("[a-f0-9-]{36}").matches(sceneId))
        val value=JSONObject().put("scene",sceneId).put("clock",JSONObject(s.validated().json())).toString()
        val file=AtomicFile(File(c.filesDir,"settings.json")); val out=file.startWrite()
        try { out.write(value.toByteArray(Charsets.UTF_8)); file.finishWrite(out) }
        catch(e:Exception) { file.failWrite(out); throw e }
    }
    fun saveBitmap(file:File,b:Bitmap,format:Bitmap.CompressFormat) {
        file.outputStream().use { require(b.compress(format,94,it)) { "Unable to save image" } }
    }
    @Synchronized fun save(c:Context,photo:Bitmap,mask:Bitmap,depth:Bitmap,s:ClockSettings):String {
        val id=UUID.randomUUID().toString(); val dir=File(root(c),id).apply { mkdirs() }
        try {
            saveBitmap(File(dir,"bg.jpg"),photo,Bitmap.CompressFormat.JPEG)
            saveBitmap(File(dir,"mask.png"),mask,Bitmap.CompressFormat.PNG)
            saveBitmap(File(dir,"depth.png"),depth,Bitmap.CompressFormat.PNG)
            val cut=Inference.cutout(photo,mask,s)
            try { saveBitmap(File(dir,"cutout.png"),cut,Bitmap.CompressFormat.PNG) } finally { cut.recycle() }
            writeSettings(c,s,id)
            root(c).listFiles()?.filter { it.name!=id }?.forEach { it.deleteRecursively() }
            return id
        } catch(e:Exception) { dir.deleteRecursively(); throw e }
    }
    @Synchronized fun refine(c:Context,s:ClockSettings) {
        val rec=record(c) ?: return; val dir=File(root(c),rec.getString("scene"))
        val photo=BitmapFactory.decodeFile(File(dir,"bg.jpg").path) ?: error("Missing photo")
        val mask=BitmapFactory.decodeFile(File(dir,"mask.png").path) ?: run { photo.recycle(); error("Missing mask") }
        val depth=BitmapFactory.decodeFile(File(dir,"depth.png").path) ?: run { photo.recycle(); mask.recycle(); error("Missing depth") }
        try { save(c,photo,mask,depth,s) } finally { photo.recycle(); mask.recycle(); depth.recycle() }
    }
    @Synchronized fun load(c:Context):Loaded? {
        val rec=record(c) ?: return
        val id=rec.getString("scene"); require(Regex("[a-f0-9-]{36}").matches(id))
        val dir=File(root(c),id); val bitmaps=mutableListOf<Bitmap>()
        try {
            for(name in listOf("bg.jpg","cutout.png","depth.png")) bitmaps+=BitmapFactory.decodeFile(File(dir,name).path) ?: error("Missing $name")
            return Loaded(Scene(bitmaps[0],bitmaps[1],bitmaps[2]),ClockSettings.parse(rec.getJSONObject("clock").toString()),id)
        } catch(e:Exception) { bitmaps.forEach { it.recycle() }; throw e }
    }
}

/** Both the interactive native preview and wallpaper call this exact drawing code. */
class LayerRenderer {
    private val imagePaint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val textPaint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val src=Rect(); private val dst=RectF()
    private val calendar=Calendar.getInstance()
    private val dateFormat=SimpleDateFormat("EEE, dd MMM yyyy",Locale.getDefault())
    private val segmentMasks=intArrayOf(0x3f,0x06,0x5b,0x4f,0x66,0x6d,0x7d,0x07,0x7f,0x6f)
    fun draw(canvas:Canvas,scene:Scene?,settings:ClockSettings,seconds:Float,now:Long=System.currentTimeMillis()) {
        canvas.drawColor(Color.rgb(8,10,18))
        if(scene==null) return
        val w=canvas.width.toFloat(); val h=canvas.height.toFloat()
        val photo=scene.background
        val fit=max(w/photo.width,h/photo.height)*1.10f
        val rw=photo.width*fit; val rh=photo.height*fit; val left=(w-rw)/2; val top=(h-rh)/2
        val wave=sin(seconds/settings.speed*(2*Math.PI)).toFloat()
        val amplitude=min(w,h)*settings.amplitude
        dst.set(left,top,left+rw,top+rh)
        canvas.drawBitmap(photo,null,dst,imagePaint)
        for(i in 0 until 64) {
            val u0=i/64f; val u1=(i+1)/64f
            val depth=scene.stripDepth[i]
            val delta=wave*amplitude*(1-settings.depthStrength+depth*settings.depthStrength)
            val dx=if(settings.direction=="vertical") 0f else delta
            val dy=if(settings.direction=="horizontal") 0f else delta
            src.set((u0*photo.width).toInt(),0,ceil(u1*photo.width).toInt().coerceAtMost(photo.width),photo.height)
            dst.set(left+u0*rw+dx,top+dy,left+u1*rw+dx+1,top+rh+dy)
            canvas.drawBitmap(photo,src,dst,imagePaint)
        }
        drawClock(canvas,settings,now)
        dst.set(left,top,left+rw,top+rh)
        canvas.drawBitmap(scene.cutout,null,dst,imagePaint)
    }
    private fun drawClock(c:Canvas,s:ClockSettings,now:Long) {
        calendar.timeInMillis=now
        val w=c.width.toFloat(); val h=c.height.toFloat(); val size=s.scale*h
        val p=textPaint; p.reset(); p.isAntiAlias=true
        p.color=Color.parseColor(s.color); p.alpha=(s.opacity*255).roundToInt()
        p.textSize=size; p.typeface=Typeface.create("sans-serif-condensed",Typeface.NORMAL)
        if(s.glow>0) p.setShadowLayer(size*.1f*s.glow,0f,0f,p.color)
        val text=ClockSettings.timeText(calendar.get(Calendar.HOUR_OF_DAY),calendar.get(Calendar.MINUTE),s.format24)
        val colonVisible=calendar.get(Calendar.SECOND)%2==0
        if(s.style=="segments") drawSegments(c,text,s.x*w,s.y*h,size,s.spacing,colonVisible,p)
        else {
            val widths=text.map { p.measureText(it.toString()) }
            val gap=size*s.spacing; var x=s.x*w-(widths.sum()+gap*(text.length-1))/2
            for(i in text.indices) {
                if(text[i]!=':' || colonVisible) c.drawText(text[i].toString(),x,s.y*h,p)
                x+=widths[i]+gap
            }
        }
        p.clearShadowLayer(); p.textSize=size*.20f; p.textAlign=Paint.Align.CENTER
        if(!s.format24) c.drawText(if(calendar.get(Calendar.HOUR_OF_DAY)>=12) "PM" else "AM",s.badgeX*w,s.badgeY*h,p)
        if(s.showDate) { p.textSize=h*.024f; c.drawText(dateFormat.format(Date(now)),s.dateX*w,s.dateY*h,p) }
    }
    private fun drawSegments(c:Canvas,text:String,cx:Float,baseline:Float,size:Float,spacing:Float,colon:Boolean,p:Paint) {
        val digitW=size*.5f; val gap=size*(.09f+spacing)
        fun width(ch:Char)=if(ch==':') size*.15f else digitW
        var x=cx-(text.sumOf { width(it).toDouble() }.toFloat()+gap*(text.length-1))/2
        val y=baseline-size*.8f; val hh=size*.8f; val thick=size*.055f
        for(ch in text) {
            if(ch==':') {
                if(colon) { c.drawCircle(x+size*.075f,y+hh*.32f,thick,p); c.drawCircle(x+size*.075f,y+hh*.72f,thick,p) }
            } else {
                val mask=segmentMasks[ch-'0']
                fun bar(bit:Int,l:Float,t:Float,r:Float,b:Float) { if(mask and (1 shl bit)!=0) c.drawRoundRect(x+l,y+t,x+r,y+b,thick*.3f,thick*.3f,p) }
                bar(0,thick,0f,digitW-thick,thick)
                bar(1,digitW-thick,thick,digitW,hh/2)
                bar(2,digitW-thick,hh/2,digitW,hh-thick)
                bar(3,thick,hh-thick,digitW-thick,hh)
                bar(4,0f,hh/2,thick,hh-thick)
                bar(5,0f,thick,thick,hh/2)
                bar(6,thick,hh/2-thick/2,digitW-thick,hh/2+thick/2)
            }
            x+=width(ch)+gap
        }
    }
}
