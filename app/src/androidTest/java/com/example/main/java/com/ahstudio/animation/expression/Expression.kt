package com.ahstudio.animation.expression

import com.ahstudio.animation.core.AnimationEngine
import com.ahstudio.animation.keyframes.EvaluatedValue
import com.ahstudio.animation.properties.BindingKey

/**
 * Legacy "expression-ready" hooks kept for source compatibility. The real, sandboxed expression language lives in
 * [Expression] (see ExpressionLanguage.kt); the engine runs it via [AnimationEngine.setExpression].
 */
interface ExpressionContext {
    fun timeMs(): Long
    fun propertyValue(key: BindingKey): Double
    fun markerTime(name: String): Long?
    fun seededRandom(seed: Long, index: Long): Double
}

fun interface ExpressionEvaluator {
    fun evaluate(expressionId: String, ctx: ExpressionContext): Double
}

/** Adapter so a host-supplied [ExpressionEvaluator] can drive a property: `engine.setSource(key, ExpressionSource(...))`. */
class ExpressionSource(
    private val expressionId: String,
    private val evaluator: ExpressionEvaluator,
    private val ctxAt: (Long) -> ExpressionContext
) : AnimationEngine.ValueSource {
    override fun value(timeMs: Long): EvaluatedValue? =
        EvaluatedValue.FloatV(evaluator.evaluate(expressionId, ctxAt(timeMs)), 0.0)
}
