package com.deepfx.clock

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.*

/** Models run sequentially on the Activity worker, never on the UI or wallpaper thread. */
class Inference(private val context: Context) {
    fun depth(photo: Bitmap): Bitmap {
        val bytes=context.assets.open("midas.tflite").use { it.readBytes() }
        val model=ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); rewind() }
        Interpreter(model,Interpreter.Options().apply { setNumThreads(2) }).use { session ->
            val shape=session.getInputTensor(0).shape()
            require(shape.contentEquals(intArrayOf(1,256,256,3))) { "Unsupported MiDaS input shape" }
            require(session.getOutputTensor(0).numElements()==256*256) { "Unsupported MiDaS output shape" }
            val scaled=Bitmap.createScaledBitmap(photo,256,256,true)
            val pixels=IntArray(256*256); scaled.getPixels(pixels,0,256,0,0,256,256)
            if(scaled !== photo) scaled.recycle()
            val input=ByteBuffer.allocateDirect(pixels.size*12).order(ByteOrder.nativeOrder())
            for(p in pixels) { input.putFloat(Color.red(p)/127.5f-1); input.putFloat(Color.green(p)/127.5f-1); input.putFloat(Color.blue(p)/127.5f-1) }
            input.rewind()
            val output=ByteBuffer.allocateDirect(pixels.size*4).order(ByteOrder.nativeOrder())
            session.run(input,output); output.rewind()
            val raw=FloatArray(pixels.size); output.asFloatBuffer().get(raw)
            return grayscale(raw,256,256)
        }
    }
    fun mask(photo: Bitmap): Bitmap {
        val env=OrtEnvironment.getEnvironment()
        val bytes=context.assets.open("isnet.onnx").use { it.readBytes() }
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            env.createSession(bytes,options).use { session ->
                val name=session.inputNames.first()
                val info=session.inputInfo.getValue(name).info as TensorInfo
                val shape=info.shape
                require(shape.size==4 && (shape[1]==3L || shape[1]<0)) { "Unsupported segmentation input layout" }
                val h=if(shape[2]>0) shape[2].toInt() else 1024
                val w=if(shape[3]>0) shape[3].toInt() else 1024
                require(w in 256..2048 && h in 256..2048) { "Unsupported segmentation dimensions" }
                val scaled=Bitmap.createScaledBitmap(photo,w,h,true)
                val pixels=IntArray(w*h); scaled.getPixels(pixels,0,w,0,0,w,h)
                if(scaled !== photo) scaled.recycle()
                val input=FloatArray(w*h*3)
                for(i in pixels.indices) {
                    input[i]=(Color.red(pixels[i])/255f-.5f)
                    input[w*h+i]=(Color.green(pixels[i])/255f-.5f)
                    input[2*w*h+i]=(Color.blue(pixels[i])/255f-.5f)
                }
                OnnxTensor.createTensor(env,FloatBuffer.wrap(input),longArrayOf(1,3,h.toLong(),w.toLong())).use { tensor ->
                    session.run(mapOf(name to tensor)).use { result ->
                        val output=result[0] as OnnxTensor
                        val dims=output.info.shape
                        require(dims.size>=2) { "Invalid segmentation output" }
                        val oh=dims[dims.size-2].toInt(); val ow=dims.last().toInt()
                        val buffer=output.floatBuffer
                        require(buffer.remaining()>=ow*oh) { "Incomplete segmentation output" }
                        val raw=FloatArray(ow*oh); buffer.get(raw)
                        return grayscale(raw,ow,oh)
                    }
                }
            }
        }
    }
    private fun grayscale(raw: FloatArray,w:Int,h:Int): Bitmap {
        require(raw.all { it.isFinite() }) { "AI model returned invalid values" }
        val lo=raw.minOrNull() ?: 0f; val hi=raw.maxOrNull() ?: 1f
        require(hi-lo>1e-6f) { "AI model returned a flat map; try another photo" }
        val pixels=IntArray(raw.size) { i ->
            val v=((raw[i]-lo)/(hi-lo)*255).roundToInt().coerceIn(0,255)
            Color.rgb(v,v,v)
        }
        return Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888)
    }
    companion object {
        fun cutout(photo:Bitmap,mask:Bitmap,settings:ClockSettings):Bitmap {
            val scaled=Bitmap.createScaledBitmap(mask,photo.width,photo.height,true)
            val pixels=IntArray(photo.width*photo.height)
            val alpha=IntArray(pixels.size)
            photo.getPixels(pixels,0,photo.width,0,0,photo.width,photo.height)
            scaled.getPixels(alpha,0,photo.width,0,0,photo.width,photo.height)
            if(scaled !== mask) scaled.recycle()
            val softness=.015f+settings.feather*.025f
            for(i in pixels.indices) {
                val value=Color.red(alpha[i])/255f
                val t=((value-settings.threshold+softness)/(2*softness)).coerceIn(0f,1f)
                val a=(t*t*(3-2*t)*255).roundToInt()
                pixels[i]=(pixels[i] and 0x00ffffff) or (a shl 24)
            }
            return Bitmap.createBitmap(pixels,photo.width,photo.height,Bitmap.Config.ARGB_8888)
        }
    }
}
