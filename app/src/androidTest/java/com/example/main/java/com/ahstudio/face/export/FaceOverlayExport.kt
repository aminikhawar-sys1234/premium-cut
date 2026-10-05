package com.ahstudio.face.export

import com.ahstudio.face.FaceEngineHost
import com.ahstudio.face.timeline.FaceTimelineBridge

object TrackingPreWarmer {
    fun warm(
        host: FaceEngineHost,
        bridge: FaceTimelineBridge,
        ranges: List<Triple<String, Long, Long>>,
        stepUs: Long = 66_666,
    ) {
        for ((clipId, from, to) in ranges) {
            host.tracking.prewarm(clipId, from, to, stepUs, bridge.transformHash(clipId), bridge.isMirroredSource(clipId))
        }
    }
}
