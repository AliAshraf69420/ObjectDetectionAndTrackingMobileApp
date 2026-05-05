package com.aliashraf69420.rcbowlingtracker.processing

import kotlin.math.sqrt

/**
 * Pin tracker matching the notebook's logic:
 *   - center_distance < 70 OR iou > 0.20 → candidate match
 *   - match score = iou - (dist / 1000)
 *   - Fall detected on standing→fallen class transition (no multi-frame confirmation)
 *   - Prune tracks missing > 20 frames
 */
class PinTracker {

    companion object {
        // Notebook values: match_distance=70, match_iou=0.20, max_missing_frames=20
        private const val MATCH_DISTANCE   = 70f
        private const val MATCH_IOU        = 0.20f
        private const val MAX_MISSING_FRAMES = 20
    }

    class PinTrack(
        val trackId: Int,
        var box: FloatArray,
        var lastSeenFrame: Int,
        var classId: Int,
        var conf: Float
    ) {
        var missingFrames    = 0
        var confirmedFallen  = false
        var fallOrder: Int?  = null
        var fallFrame: Int?  = null
        var fallTime: Float? = null           // seconds, set at transition
        var fallPresentationUs: Long? = null  // set by VideoProcessor
    }

    val tracks = mutableListOf<PinTrack>()
    private var nextTrackId  = 1
    var nextFallOrder = 1
        private set

    /**
     * Update tracker with this frame's pin detections.
     * Mirrors the notebook's per-frame matching loop exactly.
     *
     * @param fps used to compute fall_time = frameIdx / fps
     */
    fun update(
        frameIdx: Int,
        pinDetections: List<TFLiteDetector.Detection>,
        fps: Float = 30f
    ) {
        val usedTrackIds = mutableSetOf<Int>()

        for (det in pinDetections) {
            val currentBox = det.box

            // ---- find best matching existing track ----
            var bestMatchId: Int? = null
            var bestMatchScore = -999f

            for (track in tracks) {
                if (track.trackId in usedTrackIds) continue

                val dist    = centerDist(currentBox, track.box)
                val overlap = iou(currentBox, track.box)

                if (dist < MATCH_DISTANCE || overlap > MATCH_IOU) {
                    val score = overlap - (dist / 1000f)
                    if (score > bestMatchScore) {
                        bestMatchScore = score
                        bestMatchId    = track.trackId
                    }
                }
            }

            // ---- create new track if no match ----
            val matchedTrack: PinTrack
            if (bestMatchId == null) {
                val newTrack = PinTrack(
                    trackId = nextTrackId++,
                    box = currentBox.clone(),
                    lastSeenFrame = frameIdx,
                    classId = det.classId,
                    conf = det.conf
                )
                tracks.add(newTrack)
                matchedTrack = newTrack
            } else {
                matchedTrack = tracks.first { it.trackId == bestMatchId }
            }

            usedTrackIds.add(matchedTrack.trackId)

            val previousClass = matchedTrack.classId

            // ---- detect standing → fallen transition ----
            if (previousClass == TFLiteDetector.STANDING_ID &&
                det.classId == TFLiteDetector.FALLEN_ID
            ) {
                if (!matchedTrack.confirmedFallen) {
                    matchedTrack.confirmedFallen = true
                    matchedTrack.fallOrder = nextFallOrder++
                    matchedTrack.fallFrame = frameIdx
                    matchedTrack.fallTime  = frameIdx / fps
                }
            }

            // ---- update track data ----
            matchedTrack.box           = currentBox.clone()
            matchedTrack.classId       = det.classId
            matchedTrack.lastSeenFrame = frameIdx
            matchedTrack.conf          = det.conf
            matchedTrack.missingFrames = 0
        }

        // ---- increment missing counter for unmatched tracks ----
        for (track in tracks) {
            if (track.trackId !in usedTrackIds) {
                track.missingFrames++
            }
        }

        // ---- prune dead tracks (but keep fall data via fall_log in VideoProcessor) ----
        tracks.removeAll { it.missingFrames > MAX_MISSING_FRAMES }
    }

    private fun iou(a: FloatArray, b: FloatArray): Float {
        val ix1 = maxOf(a[0], b[0]); val iy1 = maxOf(a[1], b[1])
        val ix2 = minOf(a[2], b[2]); val iy2 = minOf(a[3], b[3])
        val inter = maxOf(0f, ix2 - ix1) * maxOf(0f, iy2 - iy1)
        val ua = maxOf(0f, a[2]-a[0]) * maxOf(0f, a[3]-a[1])
        val ub = maxOf(0f, b[2]-b[0]) * maxOf(0f, b[3]-b[1])
        val union = ua + ub - inter
        return if (union <= 0f) 0f else inter / union
    }

    private fun centerDist(a: FloatArray, b: FloatArray): Float {
        val dx = (a[0]+a[2])/2f - (b[0]+b[2])/2f
        val dy = (a[1]+a[3])/2f - (b[1]+b[3])/2f
        return sqrt(dx*dx + dy*dy)
    }
}
