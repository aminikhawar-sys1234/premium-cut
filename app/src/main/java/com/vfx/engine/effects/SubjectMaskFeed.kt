package com.vfx.engine.effects

/**
 * GL-thread holder for the CURRENT frame's subject segmentation mask.
 *
 * The compositor publishes the active clip's mask texture right before its VFX stack runs;
 * [SubjectMaskRuntime] picks it up while building the effect's pass. Publishing and consuming
 * happen inside the same call stack, so a stale handle can never be sampled: every frame that
 * renders a subject effect publishes a fresh handle (or none, which gates the effect off).
 */
object SubjectMaskFeed {

    class Frame(val maskTex: Int, val maskWidth: Int, val maskHeight: Int)

    @Volatile private var frame: Frame? = null

    /**
     * Thumbnail-only switch: while rasterizing panel icons there is no real clip frame, so the
     * subject runtimes may style the synthetic base scene full-frame to communicate the LOOK.
     * The live compositor never enables this — a missing mask passes the video through.
     */
    @Volatile var allowFullFrameFallback: Boolean = false

    fun publish(tex: Int, width: Int, height: Int) {
        frame = Frame(tex, width.coerceAtLeast(1), height.coerceAtLeast(1))
    }

    fun clear() { frame = null }

    fun current(): Frame? = frame
}

/** Cheap helpers around the `subject.*` engine definitions. */
object SubjectVfx {
    const val ID_PREFIX = "subject."

    /** True when the serialized stack contains at least one subject effect id. */
    fun referenced(stackJson: String?): Boolean =
        stackJson != null && stackJson.contains("\"$ID_PREFIX")
}
