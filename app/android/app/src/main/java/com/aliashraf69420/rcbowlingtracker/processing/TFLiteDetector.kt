package com.aliashraf69420.rcbowlingtracker.processing

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

class TFLiteDetector(context: Context) {

    companion object {
        private const val MODEL_FILE = "best_unquantized.tflite"
        private const val INPUT_SIZE  = 640
        private const val NUM_CLASSES = 4

        const val BALL_ID    = 0
        const val CAR_ID     = 1
        const val FALLEN_ID  = 2
        const val STANDING_ID = 3

        val CLASS_NAMES = mapOf(
            BALL_ID     to "ball",
            CAR_ID      to "car",
            FALLEN_ID   to "fallen-pins",
            STANDING_ID to "standing-pins"
        )

        private val CONF_THRESH = mapOf(
            BALL_ID     to 0.35f,
            CAR_ID      to 0.35f,
            FALLEN_ID   to 0.35f,
            STANDING_ID to 0.35f
        )

        // Model output: [1, MAX_DETS, 6] — NMS is already applied inside the model.
        // Each detection row: [x1, y1, x2, y2, confidence, class_id]  (coords in 0..INPUT_SIZE space)
        private const val MAX_DETS = 300
        private const val DET_VALUES = 6
    }

    data class Detection(
        val box: FloatArray,   // [x1, y1, x2, y2] in original-frame pixel coords
        val classId: Int,
        val conf: Float
    )

    private val interpreter: Interpreter
    private val inputBuffer: ByteBuffer

    // Matches the actual model output shape [1, 300, 6]
    private val outputBuffer = Array(1) { Array(MAX_DETS) { FloatArray(DET_VALUES) } }

    init {
        val opts = Interpreter.Options().apply { numThreads = 4 }
        interpreter = Interpreter(loadModel(context), opts)
        inputBuffer = ByteBuffer
            .allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4)
            .order(ByteOrder.nativeOrder())
    }

    fun detect(frame: Bitmap): List<Detection> {
        val scaled = Bitmap.createScaledBitmap(frame, INPUT_SIZE, INPUT_SIZE, true)
        fillInput(scaled)
        if (scaled !== frame) scaled.recycle()

        interpreter.run(inputBuffer, outputBuffer)

        val scaleX = frame.width.toFloat() / INPUT_SIZE
        val scaleY = frame.height.toFloat() / INPUT_SIZE
        val dets = mutableListOf<Detection>()

        for (i in 0 until MAX_DETS) {
            val row     = outputBuffer[0][i]
            val conf    = row[4]
            val classId = row[5].toInt()

            if (conf < 0.01f) continue                          // zero-padded slot
            if (classId < 0 || classId >= NUM_CLASSES) continue
            val thresh = CONF_THRESH[classId] ?: 0.15f
            if (conf < thresh) continue

            dets.add(Detection(
                box = floatArrayOf(
                    row[0] * scaleX, row[1] * scaleY,
                    row[2] * scaleX, row[3] * scaleY
                ),
                classId = classId,
                conf    = conf
            ))
        }

        return dets   // NMS already done inside the model
    }

    private fun fillInput(bitmap: Bitmap) {
        inputBuffer.rewind()
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        for (px in pixels) {
            inputBuffer.putFloat(((px shr 16) and 0xFF) / 255f)
            inputBuffer.putFloat(((px shr 8)  and 0xFF) / 255f)
            inputBuffer.putFloat((px          and 0xFF) / 255f)
        }
    }

    private fun loadModel(context: Context): ByteBuffer {
        val afd = context.assets.openFd(MODEL_FILE)
        val channel = FileInputStream(afd.fileDescriptor).channel
        return channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
    }

    fun close() { interpreter.close() }
}
