package com.deepfx.clock

import android.graphics.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LayerRendererTest {
    @Test fun subjectOccludesClockAndStaysFixed() {
        val bg=Bitmap.createBitmap(200,300,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val cut=Bitmap.createBitmap(200,300,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val depth=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val scene=Scene(bg,cut,depth)
        val renderer=LayerRenderer()
        val a=Bitmap.createBitmap(200,300,Bitmap.Config.ARGB_8888)
        val b=Bitmap.createBitmap(200,300,Bitmap.Config.ARGB_8888)
        renderer.draw(Canvas(a),scene,ClockSettings(),0f,0L)
        renderer.draw(Canvas(b),scene,ClockSettings(),3f,0L)
        assertEquals(Color.BLUE,a.getPixel(100,105))
        assertTrue(a.sameAs(b))
        scene.close(); a.recycle(); b.recycle()
    }
    @Test fun clockDoesNotFollowBackgroundMotion() {
        val bg=Bitmap.createBitmap(200,300,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        val cut=Bitmap.createBitmap(200,300,Bitmap.Config.ARGB_8888)
        val depth=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val scene=Scene(bg,cut,depth)
        val renderer=LayerRenderer()
        val a=Bitmap.createBitmap(200,300,Bitmap.Config.ARGB_8888)
        val b=Bitmap.createBitmap(200,300,Bitmap.Config.ARGB_8888)
        renderer.draw(Canvas(a),scene,ClockSettings(),0f,0L)
        renderer.draw(Canvas(b),scene,ClockSettings(),3f,0L)
        assertTrue(a.sameAs(b))
        val pixels=IntArray(200*300); a.getPixels(pixels,0,200,0,0,200,300)
        assertTrue(pixels.any { Color.red(it)>200 })
        scene.close(); a.recycle(); b.recycle()
    }
}
