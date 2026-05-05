package com.aliashraf69420.rcbowlingtracker.processing

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

class TFLiteDetector(context: Context) {

    companion object {
        private const val TAG = "TFLiteDetector"
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

        private const val NMS_IOU_THRESH = 0.5f
    }

    data class Detection(
        val box: FloatArray,   // [x1, y1, x2, y2] in original-frame pixel coords
        val classId: Int,
        val conf: Float
    )

    private val interpreter: Interpreter
    private val inputBuffer: ByteBuffer

    // Output handling — determined at init from the model's actual output tensors.
    // Two possible formats:
    //   A) NMS'd: single output [1, 300, 6] = [x1, y1, x2, y2, conf, classId]
    //   B) Raw:   single output [1, 8, 8400] = [cx, cy, w, h, cls0, cls1, cls2, cls3] per anchor
    //             (transposed YOLO format, needs NMS)
    private val useNmsOutput: Boolean
    private val nmsOutputIndex: Int

    // For NMS'd output [1, 300, 6]
    private var nmsBuffer: Array<Array<FloatArray>>? = null

    // For raw output [1, 8, 8400] — we allocate based on actual shape
    private var rawBuffer: Array<Array<FloatArray>>? = null
    private var rawAnchors = 0
    private var rawOutputIndex = 0
    private var rawIsTransposed = false

    init {
        val opts = Interpreter.Options().apply { numThreads = 4 }
        interpreter = Interpreter(loadModel(context), opts)
        inputBuffer = ByteBuffer
            .allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4)
            .order(ByteOrder.nativeOrder())

        // Inspect output tensors to determine format
        val numOutputs = interpreter.outputTensorCount
        Log.i(TAG, "Model has $numOutputs output tensor(s)")

        var foundNms = false
        var nmsIdx = 0

        for (i in 0 until numOutputs) {
            val tensor = interpreter.getOutputTensor(i)
            val shape = tensor.shape()
            Log.i(TAG, "  Output[$i]: shape=${shape.contentToString()} dtype=${tensor.dataType()}")

            // Look for NMS'd output [1, N, 6] = [x1, y1, x2, y2, conf, cls_id]
            if (shape.size == 3 && shape[2] == 6 && shape[1] >= 1) {
                foundNms = true
                nmsIdx = i
            } else if (shape.size == 3 && shape[1] == 6 && shape[2] >= 1) {
                // Transposed NMS: [1, 6, N]
                foundNms = true
                nmsIdx = i
            }
        }

        if (foundNms) {
            useNmsOutput = true
            nmsOutputIndex = nmsIdx
            val shape = interpreter.getOutputTensor(nmsIdx).shape()
            nmsBuffer = Array(shape[0]) { Array(shape[1]) { FloatArray(shape[2]) } }
            Log.i(TAG, "Using NMS output at index $nmsIdx, shape=${shape.contentToString()}")
        } else {
            // Use raw YOLO output — find the one with shape [1, 4+NUM_CLASSES, N] or [1, N, 4+NUM_CLASSES]
            useNmsOutput = false
            nmsOutputIndex = 0
            var rawIdx = 0
            for (i in 0 until numOutputs) {
                val shape = interpreter.getOutputTensor(i).shape()
                if (shape.size == 3) {
                    // [1, 8, 8400] (transposed) or [1, 8400, 8]
                    if (shape[1] == 4 + NUM_CLASSES) {
                        rawAnchors = shape[2]
                        rawIdx = i
                        rawIsTransposed = true
                        rawBuffer = Array(shape[0]) { Array(shape[1]) { FloatArray(shape[2]) } }
                        break
                    } else if (shape[2] == 4 + NUM_CLASSES) {
                        rawAnchors = shape[1]
                        rawIdx = i
                        rawIsTransposed = false
                        rawBuffer = Array(shape[0]) { Array(shape[1]) { FloatArray(shape[2]) } }
                        break
                    }
                }
            }
            rawOutputIndex = rawIdx
            Log.i(TAG, "Using RAW output at index $rawIdx (transposed=$rawIsTransposed) with $rawAnchors anchors")
        }
    }

    fun detect(frame: Bitmap): List<Detection> {
        val scaled = Bitmap.createScaledBitmap(frame, INPUT_SIZE, INPUT_SIZE, true)
        fillInput(scaled)
        if (scaled !== frame) scaled.recycle()

        val scaleX = frame.width.toFloat() / INPUT_SIZE
        val scaleY = frame.height.toFloat() / INPUT_SIZE

        return if (useNmsOutput) {
            detectNms(scaleX, scaleY)
        } else {
            detectRaw(scaleX, scaleY)
        }
    }

    /** Parse NMS'd output [1, maxDet, 6]: [x1, y1, x2, y2, conf, classId] */
    private fun detectNms(scaleX: Float, scaleY: Float): List<Detection> {
        val buf = nmsBuffer!!
        val outputs = HashMap<Int, Any>()
        outputs[nmsOutputIndex] = buf
        interpreter.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputs)

        val dets = mutableListOf<Detection>()
        for (i in buf[0].indices) {
            val row     = buf[0][i]
            val conf    = row[4]
            val classId = row[5].toInt()

            if (conf < 0.01f) continue
            if (classId < 0 || classId >= NUM_CLASSES) continue
            val thresh = CONF_THRESH[classId] ?: 0.15f
            if (conf < thresh) continue

            // NMS output coordinates are normalized [0,1]; multiply by frame dimensions
            dets.add(Detection(
                box = floatArrayOf(
                    row[0] * scaleX * INPUT_SIZE, row[1] * scaleY * INPUT_SIZE,
                    row[2] * scaleX * INPUT_SIZE, row[3] * scaleY * INPUT_SIZE
                ),
                classId = classId,
                conf    = conf
            ))
        }
        return dets
    }

    /**
     * Parse raw YOLO output [1, 4+NUM_CLASSES, numAnchors] (transposed format).
     * Each anchor: [cx, cy, w, h, cls0_score, cls1_score, cls2_score, cls3_score]
     * Coordinates are in pixel space (0..INPUT_SIZE).
     * Requires manual NMS.
     */
    private fun detectRaw(scaleX: Float, scaleY: Float): List<Detection> {
        val buf = rawBuffer!!

        val outputs = HashMap<Int, Any>()
        outputs[rawOutputIndex] = buf
        interpreter.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputs)

        val isTransposed = rawIsTransposed
        val numAnchors = rawAnchors

        val candidates = mutableListOf<Detection>()

        for (a in 0 until numAnchors) {
            // Get values — handle both [1, 8, N] and [1, N, 8]
            val cx: Float
            val cy: Float
            val w: Float
            val h: Float
            val classScores = FloatArray(NUM_CLASSES)

            if (isTransposed) {
                cx = buf[0][0][a]
                cy = buf[0][1][a]
                w  = buf[0][2][a]
                h  = buf[0][3][a]
                for (c in 0 until NUM_CLASSES) classScores[c] = buf[0][4 + c][a]
            } else {
                cx = buf[0][a][0]
                cy = buf[0][a][1]
                w  = buf[0][a][2]
                h  = buf[0][a][3]
                for (c in 0 until NUM_CLASSES) classScores[c] = buf[0][a][4 + c]
            }

            // Find best class
            var bestClass = 0
            var bestScore = classScores[0]
            for (c in 1 until NUM_CLASSES) {
                if (classScores[c] > bestScore) {
                    bestScore = classScores[c]
                    bestClass = c
                }
            }

            val thresh = CONF_THRESH[bestClass] ?: 0.15f
            if (bestScore < thresh) continue

            // Convert cx,cy,w,h → x1,y1,x2,y2 in original frame coords
            val x1 = (cx - w / 2f) * scaleX
            val y1 = (cy - h / 2f) * scaleY
            val x2 = (cx + w / 2f) * scaleX
            val y2 = (cy + h / 2f) * scaleY

            candidates.add(Detection(
                box = floatArrayOf(x1, y1, x2, y2),
                classId = bestClass,
                conf = bestScore
            ))
        }

        // Apply NMS per class
        return nms(candidates, NMS_IOU_THRESH)
    }

    /** Simple greedy NMS per class. */
    private fun nms(dets: List<Detection>, iouThresh: Float): List<Detection> {
        val result = mutableListOf<Detection>()
        val sorted = dets.sortedByDescending { it.conf }.toMutableList()

        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            result.add(best)
            sorted.removeAll { other ->
                other.classId == best.classId && iou(best.box, other.box) > iouThresh
            }
        }
        return result
    }

    private fun iou(a: FloatArray, b: FloatArray): Float {
        val ix1 = max(a[0], b[0]); val iy1 = max(a[1], b[1])
        val ix2 = min(a[2], b[2]); val iy2 = min(a[3], b[3])
        val inter = max(0f, ix2 - ix1) * max(0f, iy2 - iy1)
        val areaA = max(0f, a[2]-a[0]) * max(0f, a[3]-a[1])
        val areaB = max(0f, b[2]-b[0]) * max(0f, b[3]-b[1])
        val union = areaA + areaB - inter
        return if (union <= 0f) 0f else inter / union
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
