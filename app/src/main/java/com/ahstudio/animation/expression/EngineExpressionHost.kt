package com.ahstudio.animation.expression

import com.ahstudio.animation.core.AnimationEngine
import com.ahstudio.animation.keyframes.EvaluatedValue
import com.ahstudio.animation.math.Vec2
import com.ahstudio.animation.properties.BindingKey

/** Bridges [Expression] to a live [AnimationEngine] property. Cross-property reads are cycle-guarded by [visiting]. */
internal class EngineExpressionHost(
    private val engine: AnimationEngine,
    private val key: BindingKey,
    override val timeMs: Long,
    private val baseAtTime: (Long) -> EvaluatedValue?,
    override val value: XVal,
    private val visiting: Set<BindingKey>,
    override val fps: Double,
    override val durationMs: Long
) : ExpressionHost {
    override val seed: Long = key.unique.hashCode().toLong()

    override fun valueAt(timeMs: Long): XVal = baseAtTime(timeMs)?.toX() ?: value

    override fun keyTimes(): List<Long> = engine.trackFor(key)?.get()?.data?.keyframes?.map { it.timeMs } ?: emptyList()

    override fun keyValue(index: Int): XVal? {
        val kf = engine.trackFor(key)?.get()?.data?.keyframes?.getOrNull(index) ?: return null
        return kf.vecValue?.let { XVal.of(it) } ?: XVal.Num(kf.value)
    }

    override fun propertyAt(targetId: String?, property: String, timeMs: Long): XVal? {
        val k = BindingKey(targetId ?: key.targetId, property)
        if (k == key) return baseAtTime(timeMs)?.toX()
        return engine.evaluateKeyGuarded(k, timeMs, visiting + key)?.toX()
    }

    override fun markerTime(name: String): Long? = engine.markers().firstOrNull { it.name == name }?.timeMs
}

internal fun EvaluatedValue.toX(): XVal = when (this) {
    is EvaluatedValue.FloatV -> XVal.Num(value)
    is EvaluatedValue.Vec2V -> XVal.of(value)
}

internal fun XVal.toEvaluated(velocity: EvaluatedValue? , asVec: Boolean): EvaluatedValue {
    val vel = velocity
    return if (asVec || this is XVal.Vec) {
        val v = asVec2()
        EvaluatedValue.Vec2V(v, (vel as? EvaluatedValue.Vec2V)?.velocityPerSec ?: Vec2.ZERO)
    } else EvaluatedValue.FloatV(asDouble(), (vel as? EvaluatedValue.FloatV)?.velocityPerSec ?: 0.0)
}
