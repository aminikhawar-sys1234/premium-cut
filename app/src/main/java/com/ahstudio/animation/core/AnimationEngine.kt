package com.ahstudio.animation.core

import com.ahstudio.animation.diagnostics.AnimationProfiler
import com.ahstudio.animation.expression.EngineExpressionHost
import com.ahstudio.animation.expression.Expression
import com.ahstudio.animation.expression.XVal
import com.ahstudio.animation.expression.toEvaluated
import com.ahstudio.animation.expression.toX
import com.ahstudio.animation.easing.EasingType
import com.ahstudio.animation.keyframes.*
import com.ahstudio.animation.math.Vec2
import com.ahstudio.animation.math.isFinite
import com.ahstudio.animation.properties.AnimatableProperty
import com.ahstudio.animation.properties.BindingKey
import com.ahstudio.animation.properties.PropertyType
import com.ahstudio.animation.procedural.Spring
import com.ahstudio.animation.procedural.Wiggle
import com.ahstudio.animation.undo.KeyframeEditSession
import com.ahstudio.animation.undo.UndoSink
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * THE animation engine. Owns NO clock: host passes authoritative timeline time into every
 * evaluate() call.
 */
class AnimationEngine(
    val timeSource: () -> Long = { 0L }
) {
    fun interface ValueSource { fun value(timeMs: Long): EvaluatedValue? }

    sealed class SourceDescriptor {
        data class WiggleDesc(val seed: Long, val freqHz: Double, val amplitude: Double,
                              val octaves: Int, val vec2: Boolean) : SourceDescriptor()
        data class SpringDesc(val from: Double, val to: Double, val startMs: Long, val durationMs: Long,
                              val stiffness: Double, val damping: Double, val mass: Double) : SourceDescriptor()
    }

    private val tracks = ConcurrentHashMap<BindingKey, AnimationTrack>()
    private val properties = ConcurrentHashMap<BindingKey, AnimatableProperty>()
    private val sources = ConcurrentHashMap<BindingKey, ValueSource>()
    val sourceDescriptors = ConcurrentHashMap<BindingKey, SourceDescriptor>()
    private val caches = ConcurrentHashMap<BindingKey, TrackEvalCache>()
    private val clips = ConcurrentHashMap<String, AnimationClip>()
    private val markers = CopyOnWriteArrayList<AnimationMarker>()
    private val idGen = AtomicLong(1L)
    private val animatableMarked = ConcurrentHashMap.newKeySet<BindingKey>()
    private val expressions = ConcurrentHashMap<BindingKey, Expression>()
    private val expressionErrors = ConcurrentHashMap<BindingKey, String>()
    private val baseValues = ConcurrentHashMap<BindingKey, EvaluatedValue>()

    /** Frame rate / duration exposed to expressions (`fps`, `duration`, `random()` frame stepping). */
    @Volatile var fps: Double = 30.0
    @Volatile var durationMs: Long = 0L

    @Volatile var autoKeyframeEnabled = false
    @Volatile var undoSink: UndoSink? = null
    @Volatile var version: Long = 0L; private set
    val profiler = AnimationProfiler()

    fun nextId(): KeyframeId = KeyframeId(idGen.getAndIncrement())
    fun currentTimeMs(): Long = timeSource()
    fun bumpVersion() { version++ }
    internal fun applyStates(target: Map<BindingKey, AnimationTrack.State?>) {
        for ((k, s) in target) {
            if (s == null) tracks.remove(k)
            else {
                val existing = tracks[k]
                if (existing == null) tracks[k] = AnimationTrack(k, s)
                else existing.forceState(s)
            }
            caches.remove(k)
        }
        bumpVersion()
    }

    // ---------------- property registration ----------------
    fun registerProperty(p: AnimatableProperty) { properties[p.key] = p }
    fun unregisterProperty(key: BindingKey) { properties.remove(key) }
    fun registeredProperties(): Map<BindingKey, AnimatableProperty> = properties.toMap()
    fun markAnimatable(key: BindingKey) { animatableMarked.add(key) }

    // ---------------- track management ----------------
    fun trackFor(key: BindingKey): AnimationTrack? = tracks[key]
    fun allTracks(): Map<BindingKey, AnimationTrack> = tracks.toMap()
    fun tracksForTarget(targetId: String): List<AnimationTrack> =
        tracks.entries.filter { it.key.targetId == targetId }.map { it.value }
    fun hasTrack(key: BindingKey) = tracks.containsKey(key)

    fun ensureTrack(key: BindingKey, type: PropertyType, spatial: Boolean = type == PropertyType.VEC2): AnimationTrack =
        tracks.computeIfAbsent(key) {
            AnimationTrack(key, AnimationTrack.State(type = type, spatial = spatial)).also { bumpVersion() }
        }

    fun removeTrack(key: BindingKey) { if (tracks.remove(key) != null) { caches.remove(key); bumpVersion() } }
    fun clear() { tracks.clear(); caches.clear(); sources.clear(); sourceDescriptors.clear(); markers.clear(); expressions.clear(); expressionErrors.clear(); baseValues.clear(); bumpVersion() }

    // ---------------- evaluation ----------------
    fun evaluate(timeMs: Long): AnimationSnapshot = profiler.measure {
        val keys = HashSet<BindingKey>(tracks.size + sources.size + expressions.size)
        keys.addAll(tracks.keys); keys.addAll(sources.keys); keys.addAll(expressions.keys)
        val map = HashMap<BindingKey, EvaluatedValue>(keys.size)
        for (k in keys) evaluateKeyGuarded(k, timeMs, emptySet())?.let { map[k] = it }
        profiler.propertiesEvaluated.addAndGet(map.size.toLong())
        AnimationSnapshot(timeMs, map)
    }

    fun evaluateKey(key: BindingKey, timeMs: Long): EvaluatedValue? = evaluateKeyGuarded(key, timeMs, emptySet())

    /** Value before any expression runs: procedural/spring source, else keyframe track. */
    private fun baseEval(key: BindingKey, timeMs: Long): EvaluatedValue? {
        sources[key]?.value(timeMs)?.let { return it }
        tracks[key]?.let { tr -> tr.evaluate(timeMs, caches.computeIfAbsent(key) { TrackEvalCache() })?.let { return it } }
        return null
    }

    /** Base value, falling back to the static value an expression-only property is anchored to. */
    private fun baseOrStatic(key: BindingKey, timeMs: Long): EvaluatedValue? =
        baseEval(key, timeMs) ?: baseValues[key] ?: properties[key]?.let { p ->
            if (p.type == PropertyType.VEC2) EvaluatedValue.Vec2V(p.defaultVec, Vec2.ZERO) else EvaluatedValue.FloatV(p.defaultValue, 0.0)
        }

    internal fun evaluateKeyGuarded(key: BindingKey, timeMs: Long, visiting: Set<BindingKey>): EvaluatedValue? {
        val ex = expressions[key] ?: return baseEval(key, timeMs)
        if (key in visiting) return baseOrStatic(key, timeMs)            // cyclic pick-whip -> use the un-expressed value
        val base = baseOrStatic(key, timeMs)
        return try {
            val main = runExpression(key, ex, timeMs, base, visiting)
            val vel = if (main is EvaluatedValue.FloatV) {
                val a = runExpression(key, ex, timeMs + 2, baseOrStatic(key, timeMs + 2), visiting) as? EvaluatedValue.FloatV
                val b = runExpression(key, ex, timeMs - 2, baseOrStatic(key, timeMs - 2), visiting) as? EvaluatedValue.FloatV
                if (a != null && b != null) (a.value - b.value) / 0.004 else 0.0
            } else 0.0
            val velV = if (main is EvaluatedValue.Vec2V) {
                val a = runExpression(key, ex, timeMs + 2, baseOrStatic(key, timeMs + 2), visiting) as? EvaluatedValue.Vec2V
                val b = runExpression(key, ex, timeMs - 2, baseOrStatic(key, timeMs - 2), visiting) as? EvaluatedValue.Vec2V
                if (a != null && b != null) (a.value - b.value) / 0.004 else Vec2.ZERO
            } else Vec2.ZERO
            expressionErrors.remove(key)
            when (main) {
                is EvaluatedValue.FloatV -> EvaluatedValue.FloatV(main.value, vel)
                is EvaluatedValue.Vec2V -> EvaluatedValue.Vec2V(main.value, velV)
            }
        } catch (e: RuntimeException) {
            expressionErrors[key] = e.message ?: e.javaClass.simpleName
            base
        }
    }

    private fun runExpression(key: BindingKey, ex: Expression, timeMs: Long, base: EvaluatedValue?, visiting: Set<BindingKey>): EvaluatedValue {
        val host = EngineExpressionHost(this, key, timeMs, { t -> baseOrStatic(key, t) },
            base?.toX() ?: XVal.Num(0.0), visiting, fps, durationMs)
        val r = ex.evaluate(host)
        val asVec = base is EvaluatedValue.Vec2V || (base == null && properties[key]?.type == PropertyType.VEC2)
        val out = r.toEvaluated(base, asVec)
        // refuse to propagate NaN/Inf into the render pipeline
        return when (out) {
            is EvaluatedValue.FloatV -> if (isFinite(out.value)) out else throw IllegalStateException("expression produced a non-finite value")
            is EvaluatedValue.Vec2V -> if (isFinite(out.value)) out else throw IllegalStateException("expression produced a non-finite vector")
        }
    }

    fun evaluateTarget(targetId: String, timeMs: Long): Map<BindingKey, EvaluatedValue> {
        val out = HashMap<BindingKey, EvaluatedValue>()
        val keys = HashSet<BindingKey>()
        for (k in tracks.keys) if (k.targetId == targetId) keys.add(k)
        for (k in sources.keys) if (k.targetId == targetId) keys.add(k)
        for (k in expressions.keys) if (k.targetId == targetId) keys.add(k)
        for (k in keys) evaluateKey(k, timeMs)?.let { out[k] = it }
        return out
    }

    // ---------------- expressions ----------------
    /**
     * Attaches a sandboxed expression to a property (see [Expression] for the language). Returns the compile error
     * (state unchanged) or success. Expressions are pure functions of time => random-access safe for scrubbing/export.
     */
    fun setExpression(key: BindingKey, source: String): Result<Unit> {
        val compiled = Expression.tryCompile(source).getOrElse { return Result.failure(it) }
        expressions[key] = compiled
        expressionErrors.remove(key)
        bumpVersion()
        return Result.success(Unit)
    }
    fun clearExpression(key: BindingKey) { if (expressions.remove(key) != null) { expressionErrors.remove(key); bumpVersion() } }
    fun expressionOf(key: BindingKey): String? = expressions[key]?.source
    /** Last runtime error of the property's expression (null = healthy). Runtime errors fall back to the un-expressed value. */
    fun expressionError(key: BindingKey): String? = expressionErrors[key]
    fun expressionKeys(): Set<BindingKey> = expressions.keys.toSet()
    /** Static value an expression-only property starts from (AE `value` when there are no keyframes). */
    fun setBaseValue(key: BindingKey, v: EvaluatedValue) { baseValues[key] = v; bumpVersion() }
    fun setBaseValue(key: BindingKey, v: Double) = setBaseValue(key, EvaluatedValue.FloatV(v, 0.0))
    fun setBaseValue(key: BindingKey, v: Vec2) = setBaseValue(key, EvaluatedValue.Vec2V(v, Vec2.ZERO))
    fun baseValueOf(key: BindingKey): EvaluatedValue? = baseValues[key]

    fun applyToRegisteredProperties(timeMs: Long): Int {
        val snap = evaluate(timeMs)
        var n = 0
        for ((k, v) in snap.values) properties[k]?.let { if (it.apply(v)) n++ }
        return n
    }

    // ---------------- auto-keyframe / user value change ----------------
    fun notifyUserValueChange(key: BindingKey, value: Double, timeMs: Long): Boolean {
        if (!isFinite(value)) return false
        val tr = tracks[key]
        if (tr != null) {
            if (!tr.hasKeyframes && !autoKeyframeEnabled) return false
            editSession(key).upsertKeyframe(key, Keyframe(nextId(), timeMs, value = value))
                .commit("Auto-key ${key.property}")
            return true
        }
        if (autoKeyframeEnabled && key in animatableMarked) {
            val t = ensureTrack(key, properties[key]?.type ?: PropertyType.FLOAT)
            t.forceState(t.get().copy(data = KeyframeOps.upsert(t.get().data, Keyframe(nextId(), timeMs, value = value))))
            bumpVersion(); return true
        }
        return false
    }

    fun notifyUserValueChange(key: BindingKey, value: Vec2, timeMs: Long): Boolean {
        if (!isFinite(value)) return false
        val tr = tracks[key]
        if (tr != null) {
            if (!tr.hasKeyframes && !autoKeyframeEnabled) return false
            editSession(key).upsertKeyframe(key, Keyframe(nextId(), timeMs, vecValue = value))
                .commit("Auto-key ${key.property}")
            return true
        }
        if (autoKeyframeEnabled && key in animatableMarked) {
            val t = ensureTrack(key, PropertyType.VEC2, spatial = true)
            t.forceState(t.get().copy(data = KeyframeOps.upsert(t.get().data, Keyframe(nextId(), timeMs, vecValue = value))))
            bumpVersion(); return true
        }
        return false
    }

    // ---------------- editing ----------------
    fun editSession(vararg keys: BindingKey) = KeyframeEditSession(this, keys.toList())
    fun editSession(keys: List<BindingKey>) = KeyframeEditSession(this, keys)

    fun addKeyframe(key: BindingKey, kf: Keyframe): Boolean {
        ensureTrack(key, if (kf.vecValue != null) PropertyType.VEC2 else PropertyType.FLOAT, kf.vecValue != null)
        return editSession(key).upsertKeyframe(key, kf).commit("Add keyframe") != null
    }
    fun deleteKeyframes(key: BindingKey, ids: Set<KeyframeId>): Boolean =
        editSession(key).deleteKeyframes(key, ids).commit("Delete keyframes") != null
    fun moveKeyframes(key: BindingKey, ids: Set<KeyframeId>, deltaMs: Long): Boolean =
        editSession(key).moveKeyframes(key, ids, deltaMs).commit("Move keyframes") != null
    fun setKeyframeEasing(key: BindingKey, ids: Set<KeyframeId>, easing: EasingType): Boolean =
        editSession(key).setEasing(key, ids, easing).commit("Change easing") != null

    // ---------------- procedural / spring / sources ----------------
    /** Wiggle rides ON TOP of the keyframed value (After Effects semantics); alone it oscillates around 0. */
    fun setWiggle(key: BindingKey, wiggle: Wiggle) {
        sources[key] = ValueSource { t ->
            val base = trackValue(key, t) as? EvaluatedValue.FloatV
            EvaluatedValue.FloatV((base?.value ?: 0.0) + wiggle.value(t), base?.velocityPerSec ?: 0.0)
        }
        sourceDescriptors[key] = SourceDescriptor.WiggleDesc(wiggle.seed, wiggle.freqHz, wiggle.amplitude, wiggle.octaves, false)
        bumpVersion()
    }
    fun setWiggleVec2(key: BindingKey, wiggle: Wiggle) {
        sources[key] = ValueSource { t ->
            val base = trackValue(key, t) as? EvaluatedValue.Vec2V
            EvaluatedValue.Vec2V((base?.value ?: Vec2.ZERO) + wiggle.vec2(t), base?.velocityPerSec ?: Vec2.ZERO)
        }
        sourceDescriptors[key] = SourceDescriptor.WiggleDesc(wiggle.seed, wiggle.freqHz, wiggle.amplitude, wiggle.octaves, true)
        bumpVersion()
    }
    /** Keyframe-track value only (ignores sources). */
    fun trackValue(key: BindingKey, timeMs: Long): EvaluatedValue? =
        tracks[key]?.evaluate(timeMs, caches.computeIfAbsent(key) { TrackEvalCache() })
    fun setSpring(key: BindingKey, from: Double, to: Double, startMs: Long, durationMs: Long, spring: Spring) {
        sources[key] = ValueSource { t ->
            if (t <= startMs) EvaluatedValue.FloatV(from, 0.0)
            else {
                val tc = (t - startMs).coerceAtMost(durationMs)
                EvaluatedValue.FloatV(spring.value(from, to, tc / 1000.0), spring.velocity(from, to, tc))
            }
        }
        sourceDescriptors[key] = SourceDescriptor.SpringDesc(from, to, startMs, durationMs,
            spring.stiffness, spring.damping, spring.mass)
        bumpVersion()
    }
    fun setSource(key: BindingKey, src: ValueSource) { sources[key] = src; sourceDescriptors.remove(key); bumpVersion() }
    fun clearSource(key: BindingKey) { if (sources.remove(key) != null) { sourceDescriptors.remove(key); bumpVersion() } }
    fun activeSourceKeys(): Set<BindingKey> = sources.keys.toSet()

    // ---------------- clips / presets ----------------
    fun addClip(clip: AnimationClip) { clips[clip.id] = clip; bumpVersion() }
    fun removeClip(id: String) { if (clips.remove(id) != null) bumpVersion() }
    fun clips(): List<AnimationClip> = clips.values.toList()
    fun installBuiltinPresets() { BuiltinPresets.all().forEach { addClip(it) } }

    fun applyClip(clipId: String, targetId: String, startMs: Long, replaceExisting: Boolean = false): List<BindingKey> {
        val clip = clips[clipId] ?: return emptyList()
        val applied = ArrayList<BindingKey>()
        for (ct in clip.tracks) {
            val key = BindingKey(targetId, ct.property)
            if (replaceExisting) removeTrack(key)
            val tr = ensureTrack(key, ct.type, spatial = ct.type == PropertyType.VEC2)
            val st = tr.get()
            var data = st.data
            for (kf in ct.keyframes) {
                val fixed = if (kf.id == KeyframeId(0L)) kf.copy(id = nextId(), timeMs = kf.timeMs + startMs)
                            else kf.copy(timeMs = kf.timeMs + startMs)
                data = KeyframeOps.upsert(data, fixed)
            }
            tr.forceState(st.copy(data = data, loop = ct.loop))
            applied.add(key)
        }
        bumpVersion()
        return applied
    }

    // ---------------- markers ----------------
    fun addMarker(timeMs: Long, name: String, category: String = "", color: Int = 0): AnimationMarker {
        val m = AnimationMarker(idGen.getAndIncrement(), timeMs, name, category, color)
        markers.add(m); bumpVersion(); return m
    }
    fun removeMarker(id: Long) { if (markers.removeIf { it.id == id }) bumpVersion() }
    fun markers(): List<AnimationMarker> = markers.toList()
    fun markersAt(timeMs: Long, toleranceMs: Long = 0): List<AnimationMarker> =
        markers.filter { abs(it.timeMs - timeMs) <= toleranceMs }
    fun nearestMarker(timeMs: Long): AnimationMarker? = markers.minByOrNull { abs(it.timeMs - timeMs) }

    private fun abs(v: Long) = kotlin.math.abs(v)
}

/** Immutable per-frame result -- consumed by Composition. */
class AnimationSnapshot(
    val timeMs: Long,
    val values: Map<BindingKey, EvaluatedValue>
) {
    fun floatValue(key: BindingKey): Double? =
        (values[key] as? EvaluatedValue.FloatV)?.value
    fun vec2Value(key: BindingKey): Vec2? =
        (values[key] as? EvaluatedValue.Vec2V)?.value
    fun velocityOf(key: BindingKey): Vec2? = when (val v = values[key]) {
        is EvaluatedValue.FloatV -> Vec2(v.velocityPerSec, 0.0)
        is EvaluatedValue.Vec2V -> v.velocityPerSec
        null -> null
    }
}
