package com.example.engine.ai.tracking

import com.example.engine.ai.BodyTrackingFeature

/**
 * ML Kit Pose has 33 landmarks (BlazePose). Indices match
 * [com.google.mlkit.vision.pose.PoseLandmark] so this file stays JVM-testable.
 */
object BodyPoseLandmarks {
    const val COUNT = 33

    val ALL: IntArray = IntArray(COUNT) { it }

    val UPPER: IntArray = intArrayOf(
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
        11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22,
        23, 24
    )

    val ARMS: IntArray = intArrayOf(11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22)

    val LEGS: IntArray = intArrayOf(23, 24, 25, 26, 27, 28, 29, 30, 31, 32)

    fun idsFor(feature: BodyTrackingFeature): IntArray = when (feature) {
        BodyTrackingFeature.UPPER_BODY -> UPPER
        BodyTrackingFeature.ARMS -> ARMS
        BodyTrackingFeature.LEGS -> LEGS
        BodyTrackingFeature.FULL_BODY,
        BodyTrackingFeature.POSE_TRACKING,
        BodyTrackingFeature.BODY_BLUR -> ALL
    }

    /** Skeleton edges used by the preview overlay (pairs of landmark indices). */
    val BONES: List<Pair<Int, Int>> = listOf(
        11 to 12,
        11 to 13, 13 to 15,
        12 to 14, 14 to 16,
        11 to 23, 12 to 24, 23 to 24,
        23 to 25, 25 to 27,
        24 to 26, 26 to 28,
        15 to 17, 15 to 19, 15 to 21,
        16 to 18, 16 to 20, 16 to 22,
        27 to 29, 27 to 31,
        28 to 30, 28 to 32,
        0 to 1, 1 to 2, 2 to 3, 3 to 7,
        0 to 4, 4 to 5, 5 to 6, 6 to 8,
        9 to 10
    )

    fun filterLandmarks(
        all: List<Pair<Float, Float>>,
        feature: BodyTrackingFeature
    ): List<Pair<Float, Float>> {
        if (all.size < COUNT) return all
        val keep = idsFor(feature)
        val set = BooleanArray(COUNT)
        for (id in keep) if (id in 0 until COUNT) set[id] = true
        return all.mapIndexed { i, p -> if (i < COUNT && set[i]) p else (-1f to -1f) }
    }

    fun visibleCount(landmarks: List<Pair<Float, Float>>): Int =
        landmarks.count { it.first >= 0f && it.second >= 0f }
}
