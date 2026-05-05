package com.aliashraf69420.rcbowlingtracker.processing

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.Surface
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

object VideoProcessor {

    data class Result(
        val outputVideoUri: String,
        val elapsedMs: Long,
        val pinsKnockedDown: Int,
        val pinEvents: List<Map<String, Any>>,
        val carPath: List<Map<String, Any>>
    )

    private const val PROCESS_EVERY_N_FRAMES = 1
    private const val ENCODE_BIT_RATE        = 8_000_000
    private const val ENCODE_I_FRAME_INTERVAL = 1

    // -------------------------------------------------------------------------
    // Public entry point
    // -------------------------------------------------------------------------

    fun process(
        context: Context,
        inputUriString: String,
        onProgress: (stage: String, percent: Int, message: String) -> Unit
    ): Result {
        val startMs = System.currentTimeMillis()

        onProgress("decoding", 5, "Copying input video…")
        val tempInput = copyToTemp(context, inputUriString)

        onProgress("preprocessing", 15, "Initialising detector…")
        val detector    = TFLiteDetector(context)
        val tracker     = PinTracker()
        val carPathOut  = mutableListOf<Map<String, Any>>()
        val outputFile  = File(context.cacheDir, "rc_bowl_out_${System.currentTimeMillis()}.mp4")

        onProgress("inference", 20, "Processing video…")
        runVideoProcessing(
            inputPath    = tempInput.absolutePath,
            outputFile   = outputFile,
            detector     = detector,
            tracker      = tracker,
            carPathOut   = carPathOut,
            onProgress   = { pct, msg -> onProgress("inference", 20 + (pct * 60f).toInt(), msg) }
        )

        detector.close()
        tempInput.delete()

        onProgress("rendering", 85, "Saving to gallery…")
        val galleryUri = saveToGallery(context, outputFile)
        outputFile.delete()

        onProgress("rendering", 100, "Done!")

        val pinEvents = tracker.tracks
            .filter { it.confirmedFallen && it.fallOrder != null }
            .sortedBy { it.fallOrder }
            .map { t -> mapOf<String, Any>(
                "pinTrackId" to t.trackId,
                "order"      to t.fallOrder!!,
                "timeMs"     to ((t.fallPresentationUs ?: 0L) / 1000L).toInt()
            )}

        return Result(
            outputVideoUri  = galleryUri,
            elapsedMs       = System.currentTimeMillis() - startMs,
            pinsKnockedDown = tracker.tracks.count { it.confirmedFallen },
            pinEvents       = pinEvents,
            carPath         = carPathOut
        )
    }

    // -------------------------------------------------------------------------
    // Decode → infer → track → annotate → encode loop
    // -------------------------------------------------------------------------

    private fun runVideoProcessing(
        inputPath:  String,
        outputFile: File,
        detector:   TFLiteDetector,
        tracker:    PinTracker,
        carPathOut: MutableList<Map<String, Any>>,
        onProgress: (Float, String) -> Unit
    ) {
        // ------ video metadata ------
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(inputPath)
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            ?.toIntOrNull() ?: 0
        retriever.release()

        // ------ extractor / decoder ------
        val extractor = MediaExtractor()
        extractor.setDataSource(inputPath)
        val videoTrack = (0 until extractor.trackCount).first { i ->
            extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        }
        extractor.selectTrack(videoTrack)
        val inputFmt   = extractor.getTrackFormat(videoTrack)
        val mime       = inputFmt.getString(MediaFormat.KEY_MIME)!!
        // H.264 requires even dimensions; some cameras report odd values.
        val videoW     = inputFmt.getInteger(MediaFormat.KEY_WIDTH)  and -2
        val videoH     = inputFmt.getInteger(MediaFormat.KEY_HEIGHT) and -2
        val fps        = if (inputFmt.containsKey(MediaFormat.KEY_FRAME_RATE))
                             inputFmt.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat().coerceAtLeast(1f)
                         else 30f
        val durationUs = if (inputFmt.containsKey(MediaFormat.KEY_DURATION))
                             inputFmt.getLong(MediaFormat.KEY_DURATION)
                         else 0L
        val totalFrames = if (durationUs > 0) (durationUs / 1_000_000f * fps).toInt() else 0

        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(inputFmt, null, null, 0)
        decoder.start()

        // ------ encoder + muxer ------
        // Surface input mode: the GPU handles all pixel format/stride details.
        // This eliminates the row-shift corruption caused by stride mismatches.
        val encFmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, videoW, videoH).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE,     ENCODE_BIT_RATE)
            setInteger(MediaFormat.KEY_FRAME_RATE,   fps.toInt().coerceIn(1, 60))
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, ENCODE_I_FRAME_INTERVAL)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(encFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = encoder.createInputSurface()
        encoder.start()
        val eglHelper = EglSurfaceHelper(inputSurface)

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        if (rotation != 0) muxer.setOrientationHint(rotation)

        var muxerTrackIndex = -1
        var muxerStarted    = false
        var muxerHasData    = false

        val decBufInfo = MediaCodec.BufferInfo()
        val encBufInfo = MediaCodec.BufferInfo()

        var feedDone           = false
        var decodeDone         = false
        var encodeDone         = false
        var frameIdx           = 0
        var lastPresentationUs = 0L

        // ----------------------------------------------------------------
        // All resources must be cleaned up in try-finally.
        // The critical ordering is:
        //   1. encoder.stop/release  (independent of muxer)
        //   2. decoder.stop/release  (independent of muxer)
        //   3. extractor.release     (independent of muxer)
        //   4. muxer.stop/release    (must be LAST — Android's release()
        //                             internally calls stop() if still in
        //                             STARTED state, and stop() throws if
        //                             the internal native writer fails.)
        // Without try-finally, ANY exception thrown inside the while loop
        // bypasses all cleanup.  The GC finalizer then calls release() which
        // calls stop() on a half-written file → "Failed to stop the muxer".
        // ----------------------------------------------------------------
        try {
            while (!encodeDone) {

                // ---- feed compressed data to decoder ----
                if (!feedDone) {
                    val inIdx = decoder.dequeueInputBuffer(0)
                    if (inIdx >= 0) {
                        val inBuf    = decoder.getInputBuffer(inIdx)!!
                        val sampleSz = extractor.readSampleData(inBuf, 0)
                        if (sampleSz < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            feedDone = true
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, sampleSz, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                // ---- drain one decoded frame ----
                if (!decodeDone) {
                    val outIdx = decoder.dequeueOutputBuffer(decBufInfo, 10_000L)
                    if (outIdx >= 0) {
                        val presentationUs = decBufInfo.presentationTimeUs
                        val isEos          = decBufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0

                        // getOutputImage() returns null on many hardware decoders even
                        // in ByteBuffer mode. Fall back to raw ByteBuffer conversion.
                        val img    = decoder.getOutputImage(outIdx)
                        val bitmap = if (img != null) {
                            yuv420ToBitmap(img)
                        } else {
                            val rawBuf = decoder.getOutputBuffer(outIdx)
                            if (rawBuf != null) yuvBufferToBitmap(rawBuf, decoder.outputFormat)
                            else null
                        }
                        decoder.releaseOutputBuffer(outIdx, false)   // invalidates img

                        if (bitmap != null) {
                            // Enforce strictly monotonic timestamps — non-monotonic PTS
                            // corrupts the native MP4 muxer and causes nativeStop() to
                            // throw IllegalStateException.
                            val safePts = if (presentationUs > lastPresentationUs) presentationUs
                                          else lastPresentationUs + 1L

                            val annotated = processFrame(
                                bitmap, frameIdx, safePts,
                                detector, tracker, carPathOut, fps
                            )

                            // Draw bitmap onto encoder's input surface via EGL.
                            eglHelper.drawFrame(annotated, safePts * 1000L) // µs → ns
                            lastPresentationUs = safePts
                            annotated.recycle()
                            frameIdx++

                            if (totalFrames > 0 && frameIdx % 15 == 0) {
                                val pct = (frameIdx.toFloat() / totalFrames).coerceIn(0f, 1f)
                                onProgress(pct, "Frame $frameIdx / $totalFrames")
                            }
                        }

                        if (isEos) {
                            decodeDone = true
                            encoder.signalEndOfInputStream()
                        }
                    }
                }

                // ---- drain encoded output to muxer ----
                drainEncoder@ while (true) {
                    val encOutIdx = encoder.dequeueOutputBuffer(encBufInfo, 0)
                    when {
                        encOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            muxerTrackIndex = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        encOutIdx >= 0 -> {
                            val buf      = encoder.getOutputBuffer(encOutIdx)!!
                            val isConfig = encBufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            if (!isConfig && muxerStarted && encBufInfo.size > 0) {
                                muxer.writeSampleData(muxerTrackIndex, buf, encBufInfo)
                                muxerHasData = true
                            }
                            encoder.releaseOutputBuffer(encOutIdx, false)
                            if (encBufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                encodeDone = true
                                break@drainEncoder
                            }
                        }
                        else -> break@drainEncoder
                    }
                }
            }
        } finally {
            // Stop codec resources first (independent of muxer).
            runCatching { eglHelper.release() }
            runCatching { inputSurface.release() }
            runCatching { encoder.stop()      }
            runCatching { encoder.release()   }
            runCatching { decoder.stop()      }
            runCatching { decoder.release()   }
            runCatching { extractor.release() }

            // Muxer MUST be last. stop() is only safe when started + has data.
            // release() calls stop() internally when in STARTED state, so we
            // must call stop() ourselves first (with data guard) to avoid the
            // "Failed to stop the muxer" error from the internal stop() in release().
            if (muxerStarted) {
                if (muxerHasData) {
                    runCatching { muxer.stop() }.onFailure { e ->
                        android.util.Log.e("VideoProcessor", "muxer.stop() failed: ${e.message}", e)
                    }
                }
            }
            runCatching { muxer.release() }
        }
    }

    // -------------------------------------------------------------------------
    // Per-frame inference + annotation
    // -------------------------------------------------------------------------

    private fun processFrame(
        bitmap:        Bitmap,
        frameIdx:      Int,
        presentationUs: Long,
        detector:      TFLiteDetector,
        tracker:       PinTracker,
        carPathOut:    MutableList<Map<String, Any>>,
        fps:           Float
    ): Bitmap {
        val detections = if (frameIdx % PROCESS_EVERY_N_FRAMES == 0)
            detector.detect(bitmap) else emptyList()

        if (frameIdx % 30 == 0) {
            android.util.Log.d("VideoProcessor", "frame $frameIdx: ${detections.size} dets, bitmap=${bitmap.width}x${bitmap.height} — " +
                detections.joinToString { d ->
                    "${TFLiteDetector.CLASS_NAMES[d.classId]} ${"%.2f".format(d.conf)} " +
                    "[${d.box[0].toInt()},${d.box[1].toInt()},${d.box[2].toInt()},${d.box[3].toInt()}]"
                })
        }

        val pinDets  = detections.filter { it.classId == TFLiteDetector.FALLEN_ID  || it.classId == TFLiteDetector.STANDING_ID }
        val carDets  = detections.filter { it.classId == TFLiteDetector.CAR_ID }
        val ballDets = detections.filter { it.classId == TFLiteDetector.BALL_ID }

        if (frameIdx % PROCESS_EVERY_N_FRAMES == 0) {
            tracker.update(frameIdx, pinDets, fps)

            // Record ALL car detections for path (notebook appends every car center)
            for (det in carDets) {
                val cx = (det.box[0] + det.box[2]) / 2f
                val cy = (det.box[1] + det.box[3]) / 2f
                carPathOut.add(mapOf<String, Any>(
                    "timeMs" to (presentationUs / 1000L).toInt(),
                    "x"      to cx.toInt(),
                    "y"      to cy.toInt()
                ))
            }

            // Record presentation time for newly confirmed falls
            for (track in tracker.tracks) {
                if (track.confirmedFallen && track.fallFrame == frameIdx && track.fallPresentationUs == null) {
                    track.fallPresentationUs = presentationUs
                }
            }
        }

        return drawAnnotations(bitmap, tracker, ballDets, carDets, carPathOut, presentationUs)
    }

    // -------------------------------------------------------------------------
    // Annotation drawing
    // -------------------------------------------------------------------------

    private fun drawAnnotations(
        src:           Bitmap,
        tracker:       PinTracker,
        ballDets:      List<TFLiteDetector.Detection>,
        carDets:       List<TFLiteDetector.Detection>,
        carPathPoints: List<Map<String, Any>>,
        presentationUs: Long
    ): Bitmap {
        val out    = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)

        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; textSize = 16f }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        // Ball – yellow
        boxPaint.color = Color.YELLOW
        for (det in ballDets) {
            canvas.drawRect(det.box[0], det.box[1], det.box[2], det.box[3], boxPaint)
            drawOutlinedLabel(canvas, "ball %.2f".format(det.conf), det.box[0], det.box[1] - 8f, Color.YELLOW, textPaint)
        }

        // Car – green (notebook BGR (0,255,0))
        boxPaint.color = Color.GREEN
        for (det in carDets) {
            canvas.drawRect(det.box[0], det.box[1], det.box[2], det.box[3], boxPaint)
            drawOutlinedLabel(canvas, "car %.2f".format(det.conf), det.box[0], det.box[1] - 8f, Color.GREEN, textPaint)
        }

        // Car path – red (notebook PATH_COLOR BGR (0,0,255))
        if (carPathPoints.size > 1) {
            val pathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED; style = Paint.Style.STROKE; strokeWidth = 2f }
            for (i in 1 until carPathPoints.size) {
                canvas.drawLine(
                    (carPathPoints[i-1]["x"] as Int).toFloat(), (carPathPoints[i-1]["y"] as Int).toFloat(),
                    (carPathPoints[i]["x"] as Int).toFloat(),   (carPathPoints[i]["y"] as Int).toFloat(),
                    pathPaint)
            }
        }

        // Pins
        for (track in tracker.tracks) {
            val x1 = track.box[0]; val y1 = track.box[1]; val x2 = track.box[2]; val y2 = track.box[3]
            if (track.classId == TFLiteDetector.FALLEN_ID) {
                // Yellow glow + red box (notebook style)
                boxPaint.strokeWidth = 4f; boxPaint.color = Color.YELLOW
                canvas.drawRect(x1, y1, x2, y2, boxPaint)
                boxPaint.strokeWidth = 2f; boxPaint.color = Color.RED
                canvas.drawRect(x1, y1, x2, y2, boxPaint)
                var label = "fallen-pins PIN:${track.trackId}"
                if (track.confirmedFallen && track.fallTime != null)
                    label += " %.2fs #${track.fallOrder}".format(track.fallTime)
                drawOutlinedLabel(canvas, label, x1, y1 - 8f, Color.RED, textPaint)
            } else {
                boxPaint.strokeWidth = 2f; boxPaint.color = Color.MAGENTA
                canvas.drawRect(x1, y1, x2, y2, boxPaint)
                drawOutlinedLabel(canvas, "standing-pins PIN:${track.trackId}", x1, y1 - 8f, Color.MAGENTA, textPaint)
            }
        }

        // Count panel (top-left)
        val standingCount = tracker.tracks.count { it.classId == TFLiteDetector.STANDING_ID }
        val fallenCount   = tracker.tracks.count { it.classId == TFLiteDetector.FALLEN_ID }
        fillPaint.color = Color.BLACK
        canvas.drawRect(20f, 20f, 360f, 115f, fillPaint)
        boxPaint.color = Color.WHITE; boxPaint.strokeWidth = 1f
        canvas.drawRect(20f, 20f, 360f, 115f, boxPaint)
        textPaint.textSize = 20f; textPaint.color = Color.MAGENTA
        canvas.drawText("Standing pins: $standingCount", 35f, 55f, textPaint)
        textPaint.color = Color.RED
        canvas.drawText("Fallen pins: $fallenCount", 35f, 95f, textPaint)

        // Fall timeline panel (top-right)
        val fallLog = tracker.tracks.filter { it.confirmedFallen && it.fallOrder != null }.sortedBy { it.fallOrder }
        if (fallLog.isNotEmpty()) {
            val fw = out.width.toFloat()
            val px1 = maxOf(fw - 390f, 20f); val py1 = 20f
            val px2 = fw - 20f; val py2 = minOf(80f + 25f * fallLog.size, 500f)
            fillPaint.color = Color.BLACK
            canvas.drawRect(px1, py1, px2, py2, fillPaint)
            boxPaint.color = Color.YELLOW
            canvas.drawRect(px1, py1, px2, py2, boxPaint)
            textPaint.textSize = 18f; textPaint.color = Color.YELLOW
            canvas.drawText("Fall History", px1 + 15f, py1 + 30f, textPaint)
            textPaint.textSize = 14f; textPaint.color = Color.WHITE
            var ty = py1 + 60f
            for (e in fallLog) {
                canvas.drawText("#${e.fallOrder} Pin ${e.trackId} %.2fs".format(e.fallTime ?: 0f), px1 + 15f, ty, textPaint)
                ty += 24f
            }
        }

        return out
    }

    /** Black outline + colored fill label (matches notebook's draw_label). */
    private fun drawOutlinedLabel(canvas: Canvas, text: String, x: Float, y: Float, color: Int, paint: Paint) {
        val safeY = maxOf(20f, y)
        val savedColor = paint.color
        val savedSize  = paint.textSize
        paint.textSize = 16f
        // Black outline
        paint.color = Color.BLACK
        paint.style = Paint.Style.FILL_AND_STROKE
        paint.strokeWidth = 3f
        canvas.drawText(text, x, safeY, paint)
        // Colored fill
        paint.color = color
        paint.style = Paint.Style.FILL
        paint.strokeWidth = 0f
        canvas.drawText(text, x, safeY, paint)
        paint.color = savedColor
        paint.textSize = savedSize
    }

    // =========================================================================
    // EGL helper: renders Bitmaps onto the encoder’s input Surface.
    // This completely bypasses ByteBuffer stride issues — the GPU handles
    // all pixel format conversion internally.
    // =========================================================================

    private class EglSurfaceHelper(surface: Surface) {
        private val display: EGLDisplay
        private val context: EGLContext
        private val eglSurf: EGLSurface
        private val program: Int
        private val aPos: Int
        private val aTex: Int
        private val texId: Int
        private val vtxBuf: FloatBuffer
        private val texBuf: FloatBuffer

        init {
            // ---- EGL init ----
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val ver = IntArray(2)
            EGL14.eglInitialize(display, ver, 0, ver, 1)

            val cfgAttr = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_NONE
            )
            val cfgs = arrayOfNulls<EGLConfig>(1)
            val nCfg = IntArray(1)
            EGL14.eglChooseConfig(display, cfgAttr, 0, cfgs, 0, 1, nCfg, 0)

            val ctxAttr = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            context = EGL14.eglCreateContext(display, cfgs[0]!!, EGL14.EGL_NO_CONTEXT, ctxAttr, 0)

            val sAttr = intArrayOf(EGL14.EGL_NONE)
            eglSurf = EGL14.eglCreateWindowSurface(display, cfgs[0]!!, surface, sAttr, 0)
            EGL14.eglMakeCurrent(display, eglSurf, eglSurf, context)

            // ---- GL program ----
            val vs = compileShader(GLES20.GL_VERTEX_SHADER,
                "attribute vec4 aPos; attribute vec2 aTex;" +
                "varying vec2 vTex;" +
                "void main(){ gl_Position=aPos; vTex=aTex; }")
            val fs = compileShader(GLES20.GL_FRAGMENT_SHADER,
                "precision mediump float; varying vec2 vTex; uniform sampler2D uTex;" +
                "void main(){ gl_FragColor=texture2D(uTex,vTex); }")
            program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vs)
            GLES20.glAttachShader(program, fs)
            GLES20.glLinkProgram(program)
            aPos = GLES20.glGetAttribLocation(program, "aPos")
            aTex = GLES20.glGetAttribLocation(program, "aTex")

            // ---- texture ----
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            texId = ids[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

            // ---- geometry (fullscreen quad, two triangles) ----
            vtxBuf = floatBuf(floatArrayOf(-1f,-1f, 1f,-1f, -1f,1f, 1f,1f))
            texBuf = floatBuf(floatArrayOf( 0f,1f,  1f,1f,   0f,0f, 1f,0f))
        }

        /** Draw [bmp] to the surface with the given presentation time in nanoseconds. */
        fun drawFrame(bmp: Bitmap, presentationNs: Long) {
            EGL14.eglMakeCurrent(display, eglSurf, eglSurf, context)
            GLES20.glViewport(0, 0, bmp.width, bmp.height)
            GLES20.glUseProgram(program)

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)

            vtxBuf.position(0)
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, vtxBuf)

            texBuf.position(0)
            GLES20.glEnableVertexAttribArray(aTex)
            GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, texBuf)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            EGLExt.eglPresentationTimeANDROID(display, eglSurf, presentationNs)
            EGL14.eglSwapBuffers(display, eglSurf)
        }

        fun release() {
            GLES20.glDeleteTextures(1, intArrayOf(texId), 0)
            GLES20.glDeleteProgram(program)
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, eglSurf)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }

        private fun compileShader(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            return s
        }

        private fun floatBuf(a: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder())
                .asFloatBuffer().apply { put(a); position(0) }
    }

    // -------------------------------------------------------------------------
    // YUV_420_888 Image → ARGB Bitmap
    // -------------------------------------------------------------------------

    private fun yuv420ToBitmap(image: Image): Bitmap {
        val w = image.width; val h = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val yRS  = yPlane.rowStride
        val uvRS = uPlane.rowStride
        val uvPS = uPlane.pixelStride   // 1 = planar I420, 2 = semi-planar NV12/NV21

        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val yVal  = (yBuf[y * yRS + x].toInt() and 0xFF)
                val uvOff = (y / 2) * uvRS + (x / 2) * uvPS
                val uVal  = (uBuf[uvOff].toInt() and 0xFF) - 128
                val vVal  = (vBuf[uvOff].toInt() and 0xFF) - 128

                val r = (yVal + 1.402f   * vVal).toInt().coerceIn(0, 255)
                val g = (yVal - 0.344136f * uVal - 0.714136f * vVal).toInt().coerceIn(0, 255)
                val b = (yVal + 1.772f   * uVal).toInt().coerceIn(0, 255)

                pixels[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, w, 0, 0, w, h)
        }
    }

    // -------------------------------------------------------------------------
    // ByteBuffer YUV → ARGB Bitmap  (fallback for hardware decoders where
    // getOutputImage() returns null despite ByteBuffer output mode)
    // -------------------------------------------------------------------------

    private fun yuvBufferToBitmap(buf: ByteBuffer, fmt: MediaFormat): Bitmap {
        val w       = fmt.getInteger(MediaFormat.KEY_WIDTH)
        val h       = fmt.getInteger(MediaFormat.KEY_HEIGHT)
        val stride  = if (fmt.containsKey(MediaFormat.KEY_STRIDE))       fmt.getInteger(MediaFormat.KEY_STRIDE)       else w
        val sliceH  = if (fmt.containsKey(MediaFormat.KEY_SLICE_HEIGHT)) fmt.getInteger(MediaFormat.KEY_SLICE_HEIGHT) else h
        val cfmt    = if (fmt.containsKey(MediaFormat.KEY_COLOR_FORMAT)) fmt.getInteger(MediaFormat.KEY_COLOR_FORMAT) else -1
        // I420 / YV12 are fully planar; everything else is assumed semi-planar (NV12/NV21)
        val isPlanar = cfmt == CodecCapabilities.COLOR_FormatYUV420Planar ||
                       cfmt == CodecCapabilities.COLOR_FormatYUV420PackedPlanar

        val pixels = IntArray(w * h)
        buf.rewind()
        for (j in 0 until h) {
            for (i in 0 until w) {
                val yOff = j * stride + i
                if (yOff >= buf.limit()) break
                val yVal = (buf[yOff].toInt() and 0xFF)

                val uvJ = j / 2
                val uvI = i / 2
                val uVal: Int
                val vVal: Int

                if (isPlanar) {
                    // I420: Y plane (stride×sliceH), then U plane (stride/2 × sliceH/2), then V
                    val uBase = stride * sliceH
                    val vBase = uBase + (stride / 2) * (sliceH / 2)
                    val uOff  = uBase + uvJ * (stride / 2) + uvI
                    val vOff  = vBase + uvJ * (stride / 2) + uvI
                    uVal = if (uOff < buf.limit()) (buf[uOff].toInt() and 0xFF) - 128 else 0
                    vVal = if (vOff < buf.limit()) (buf[vOff].toInt() and 0xFF) - 128 else 0
                } else {
                    // NV12: Y plane (stride×sliceH), then interleaved UV
                    val uvBase = stride * sliceH
                    val uvOff  = uvBase + uvJ * stride + uvI * 2
                    uVal = if (uvOff     < buf.limit()) (buf[uvOff    ].toInt() and 0xFF) - 128 else 0
                    vVal = if (uvOff + 1 < buf.limit()) (buf[uvOff + 1].toInt() and 0xFF) - 128 else 0
                }

                val r = (yVal + 1.402f   * vVal).toInt().coerceIn(0, 255)
                val g = (yVal - 0.344136f * uVal - 0.714136f * vVal).toInt().coerceIn(0, 255)
                val b = (yVal + 1.772f   * uVal).toInt().coerceIn(0, 255)
                pixels[j * w + i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, w, 0, 0, w, h)
        }
    }

    // -------------------------------------------------------------------------
    // I/O helpers
    // -------------------------------------------------------------------------

    private fun copyToTemp(context: Context, uriString: String): File {
        val uri  = Uri.parse(uriString)
        val src  = if (uri.scheme == "file") {
            FileInputStream(uri.path ?: throw IllegalArgumentException("Null path: $uriString"))
        } else {
            context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Cannot open: $uriString")
        }
        val dest = File(context.cacheDir, "rc_bowl_in_${System.currentTimeMillis()}.mp4")
        src.use { it.copyTo(dest.outputStream(), bufferSize = 65_536) }
        return dest
    }

    private fun saveToGallery(context: Context, file: File): String {
        val fileName = "rc_bowling_${System.currentTimeMillis()}.mp4"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            saveGalleryQ(context, file, fileName)
        else
            saveGalleryLegacy(context, file, fileName)
    }

    private fun saveGalleryQ(context: Context, file: File, fileName: String): String {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE,    "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/RCBowling")
            put(MediaStore.Video.Media.IS_PENDING,   1)
        }
        val resolver = context.contentResolver
        val colUri   = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val itemUri  = resolver.insert(colUri, values)
            ?: throw IllegalStateException("MediaStore insert returned null")

        resolver.openOutputStream(itemUri)?.use { out ->
            file.inputStream().use { it.copyTo(out, bufferSize = 65_536) }
        } ?: throw IllegalStateException("Cannot open MediaStore output stream")

        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(itemUri, values, null, null)
        return itemUri.toString()
    }

    @Suppress("DEPRECATION")
    private fun saveGalleryLegacy(context: Context, file: File, fileName: String): String {
        val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        val rcDir     = File(moviesDir, "RCBowling").also { it.mkdirs() }
        val destFile  = File(rcDir, fileName)
        file.inputStream().use { it.copyTo(destFile.outputStream(), bufferSize = 65_536) }
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DATA,      destFile.absolutePath)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.TITLE,     fileName)
        }
        val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: Uri.fromFile(destFile)
        return uri.toString()
    }
}
