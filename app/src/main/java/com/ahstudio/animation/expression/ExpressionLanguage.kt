package com.ahstudio.animation.expression

import com.ahstudio.animation.math.Vec2
import com.ahstudio.animation.procedural.Procedural
import kotlin.math.*

/** Thrown for syntax errors, type errors and resource-limit violations. Never thrown out of the engine's evaluate(). */
class ExpressionException(message: String, val position: Int = -1) : RuntimeException(message)

/** Expression value: scalar, vector (2-4 components) or string (only used for option names such as "pingpong"). */
sealed class XVal {
    data class Num(val v: Double) : XVal()
    class Vec(val v: DoubleArray) : XVal() {
        override fun equals(other: Any?) = other is Vec && v.contentEquals(other.v)
        override fun hashCode() = v.contentHashCode()
        override fun toString() = v.joinToString(prefix = "[", postfix = "]")
    }
    data class Str(val s: String) : XVal()

    fun asDouble(): Double = when (this) {
        is Num -> v
        is Vec -> v.firstOrNull() ?: 0.0
        is Str -> throw ExpressionException("expected a number but got string \"$s\"")
    }
    fun asVec2(): Vec2 = when (this) {
        is Num -> Vec2(v, v)
        is Vec -> Vec2(v.getOrElse(0) { 0.0 }, v.getOrElse(1) { 0.0 })
        is Str -> throw ExpressionException("expected a vector but got string")
    }
    companion object {
        fun of(x: Double) = Num(x)
        fun of(v: Vec2) = Vec(doubleArrayOf(v.x, v.y))
    }
}

/**
 * What an expression can see. Implemented by the engine (see [EngineExpressionHost]); tests use fakes.
 * Times are milliseconds on the host side; the language itself works in seconds like After Effects.
 */
interface ExpressionHost {
    val timeMs: Long
    /** Keyframed (or static) value of THIS property at [timeMs], i.e. AE's `value`. */
    val value: XVal
    val seed: Long get() = 0L
    val fps: Double get() = 30.0
    val durationMs: Long get() = 0L
    fun valueAt(timeMs: Long): XVal
    fun keyTimes(): List<Long> = emptyList()
    fun keyValue(index: Int): XVal? = null
    /** Property of another target ([targetId] null = same target) -- the "pick whip". */
    fun propertyAt(targetId: String?, property: String, timeMs: Long): XVal? = null
    fun markerTime(name: String): Long? = null
}

// ---------------------------------------------------------------------------------------------
// AST
// ---------------------------------------------------------------------------------------------
internal sealed class Node(val pos: Int) {
    class Lit(val v: XVal, pos: Int) : Node(pos)
    class VecLit(val items: List<Node>, pos: Int) : Node(pos)
    class Ident(val name: String, pos: Int) : Node(pos)
    class Call(val name: String, val args: List<Node>, pos: Int) : Node(pos)
    class Unary(val op: String, val e: Node, pos: Int) : Node(pos)
    class Binary(val op: String, val l: Node, val r: Node, pos: Int) : Node(pos)
    class Ternary(val c: Node, val a: Node, val b: Node, pos: Int) : Node(pos)
    class Index(val e: Node, val i: Node, pos: Int) : Node(pos)
    class Member(val e: Node, val name: String, pos: Int) : Node(pos)
    class Assign(val name: String, val e: Node, pos: Int) : Node(pos)
    class Block(val stmts: List<Node>, pos: Int) : Node(pos)
}

// ---------------------------------------------------------------------------------------------
// Lexer + parser (precedence climbing)
// ---------------------------------------------------------------------------------------------
internal class Parser(private val src: String) {
    private enum class T { NUM, ID, STR, OP, LP, RP, LB, RB, COMMA, SEMI, Q, COLON, ASSIGN, EOF }
    private class Tok(val t: T, val s: String, val pos: Int, val num: Double = 0.0)

    private val toks = ArrayList<Tok>()
    private var p = 0
    private var depth = 0

    init { lex() }

    private fun lex() {
        var i = 0
        fun add(t: T, s: String, pos: Int, n: Double = 0.0) { toks.add(Tok(t, s, pos, n)) }
        fun lastContinues(): Boolean {
            val l = toks.lastOrNull() ?: return true
            return l.t == T.OP || l.t == T.COMMA || l.t == T.LP || l.t == T.LB || l.t == T.Q || l.t == T.COLON || l.t == T.ASSIGN || l.t == T.SEMI
        }
        while (i < src.length) {
            val c = src[i]
            when {
                c == '\n' -> { if (!lastContinues()) add(T.SEMI, "\n", i); i++ }
                c.isWhitespace() -> i++
                c == '/' && i + 1 < src.length && src[i + 1] == '/' -> { while (i < src.length && src[i] != '\n') i++ }
                c.isDigit() || (c == '.' && i + 1 < src.length && src[i + 1].isDigit()) -> {
                    val st = i
                    while (i < src.length && (src[i].isDigit() || src[i] == '.')) i++
                    if (i < src.length && (src[i] == 'e' || src[i] == 'E')) {
                        var j = i + 1
                        if (j < src.length && (src[j] == '+' || src[j] == '-')) j++
                        if (j < src.length && src[j].isDigit()) { i = j; while (i < src.length && src[i].isDigit()) i++ }
                    }
                    val txt = src.substring(st, i)
                    add(T.NUM, txt, st, txt.toDoubleOrNull() ?: throw ExpressionException("bad number '$txt'", st))
                }
                c.isLetter() || c == '_' || c == '$' -> {
                    val st = i
                    while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '_' || src[i] == '$')) i++
                    add(T.ID, src.substring(st, i), st)
                }
                c == '"' || c == '\'' -> {
                    val st = i; i++
                    val sb = StringBuilder()
                    while (i < src.length && src[i] != c) { if (src[i] == '\\' && i + 1 < src.length) i++; sb.append(src[i]); i++ }
                    if (i >= src.length) throw ExpressionException("unterminated string", st)
                    i++; add(T.STR, sb.toString(), st)
                }
                c == '(' -> { add(T.LP, "(", i); i++ }
                c == ')' -> { add(T.RP, ")", i); i++ }
                c == '[' -> { add(T.LB, "[", i); i++ }
                c == ']' -> { add(T.RB, "]", i); i++ }
                c == ',' -> { add(T.COMMA, ",", i); i++ }
                c == ';' -> { add(T.SEMI, ";", i); i++ }
                c == '?' -> { add(T.Q, "?", i); i++ }
                c == ':' -> { add(T.COLON, ":", i); i++ }
                else -> {
                    val two = if (i + 1 < src.length) src.substring(i, i + 2) else ""
                    val three = if (i + 2 < src.length) src.substring(i, i + 3) else ""
                    when {
                        three == "===" -> { add(T.OP, "==", i); i += 3 }
                        three == "!==" -> { add(T.OP, "!=", i); i += 3 }
                        two == "**" -> { add(T.OP, "^", i); i += 2 }
                        two in setOf("==", "!=", "<=", ">=", "&&", "||") -> { add(T.OP, two, i); i += 2 }
                        c == '=' -> { add(T.ASSIGN, "=", i); i++ }
                        c in "+-*/%^<>!." -> { add(T.OP, c.toString(), i); i++ }
                        else -> throw ExpressionException("unexpected character '$c'", i)
                    }
                }
            }
        }
        toks.add(Tok(T.EOF, "", src.length))
    }

    private fun peek() = toks[p]
    private fun next() = toks[p++]
    private fun isOp(s: String) = peek().t == T.OP && peek().s == s
    private fun expect(t: T, what: String): Tok {
        if (peek().t != t) throw ExpressionException("expected $what", peek().pos)
        return next()
    }

    fun parseProgram(): Node {
        val stmts = ArrayList<Node>()
        while (peek().t == T.SEMI) next()
        while (peek().t != T.EOF) {
            stmts.add(statement())
            if (peek().t == T.SEMI) { while (peek().t == T.SEMI) next() }
            else if (peek().t != T.EOF) throw ExpressionException("unexpected '${peek().s}'", peek().pos)
        }
        if (stmts.isEmpty()) throw ExpressionException("empty expression", 0)
        return if (stmts.size == 1) stmts[0] else Node.Block(stmts, 0)
    }

    private fun statement(): Node {
        val t = peek()
        if (t.t == T.ID && (t.s == "var" || t.s == "let" || t.s == "const")) {
            next(); val name = expect(T.ID, "variable name")
            expect(T.ASSIGN, "'='")
            return Node.Assign(name.s, expr(), name.pos)
        }
        if (t.t == T.ID && toks[p + 1].t == T.ASSIGN) {
            next(); next(); return Node.Assign(t.s, expr(), t.pos)
        }
        return expr()
    }

    private fun expr(): Node {
        if (++depth > 64) throw ExpressionException("expression nested too deeply", peek().pos)
        try { return ternary() } finally { depth-- }
    }

    private fun ternary(): Node {
        val c = or()
        if (peek().t == T.Q) {
            val q = next(); val a = expr(); expect(T.COLON, "':'"); val b = expr()
            return Node.Ternary(c, a, b, q.pos)
        }
        return c
    }
    private fun or(): Node { var l = and(); while (isOp("||")) { val o = next(); l = Node.Binary("||", l, and(), o.pos) }; return l }
    private fun and(): Node { var l = eq(); while (isOp("&&")) { val o = next(); l = Node.Binary("&&", l, eq(), o.pos) }; return l }
    private fun eq(): Node { var l = rel(); while (isOp("==") || isOp("!=")) { val o = next(); l = Node.Binary(o.s, l, rel(), o.pos) }; return l }
    private fun rel(): Node {
        var l = add()
        while (isOp("<") || isOp("<=") || isOp(">") || isOp(">=")) { val o = next(); l = Node.Binary(o.s, l, add(), o.pos) }
        return l
    }
    private fun add(): Node { var l = mul(); while (isOp("+") || isOp("-")) { val o = next(); l = Node.Binary(o.s, l, mul(), o.pos) }; return l }
    private fun mul(): Node {
        var l = unary()
        while (isOp("*") || isOp("/") || isOp("%")) { val o = next(); l = Node.Binary(o.s, l, unary(), o.pos) }
        return l
    }
    private fun unary(): Node {
        if (isOp("-") || isOp("+") || isOp("!")) { val o = next(); return Node.Unary(o.s, unary(), o.pos) }
        return pow()
    }
    private fun pow(): Node {
        val base = postfix()
        if (isOp("^")) { val o = next(); return Node.Binary("^", base, unary(), o.pos) }
        return base
    }
    private fun postfix(): Node {
        var e = primary()
        while (true) {
            if (peek().t == T.LB) { val b = next(); val i = expr(); expect(T.RB, "']'"); e = Node.Index(e, i, b.pos) }
            else if (isOp(".")) { val d = next(); val n = expect(T.ID, "member name"); e = Node.Member(e, n.s, d.pos) }
            else break
        }
        return e
    }
    private fun primary(): Node {
        val t = next()
        return when (t.t) {
            T.NUM -> Node.Lit(XVal.Num(t.num), t.pos)
            T.STR -> Node.Lit(XVal.Str(t.s), t.pos)
            T.ID -> if (peek().t == T.LP) {
                next()
                val args = ArrayList<Node>()
                if (peek().t != T.RP) { args.add(expr()); while (peek().t == T.COMMA) { next(); args.add(expr()) } }
                expect(T.RP, "')'")
                Node.Call(t.s, args, t.pos)
            } else Node.Ident(t.s, t.pos)
            T.LP -> { val e = expr(); expect(T.RP, "')'"); e }
            T.LB -> {
                val items = ArrayList<Node>()
                if (peek().t != T.RB) { items.add(expr()); while (peek().t == T.COMMA) { next(); items.add(expr()) } }
                expect(T.RB, "']'")
                Node.VecLit(items, t.pos)
            }
            else -> throw ExpressionException("unexpected '${t.s.ifEmpty { "end of input" }}'", t.pos)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Compiled expression + interpreter
// ---------------------------------------------------------------------------------------------
class Expression private constructor(val source: String, private val root: Node) {
    companion object {
        const val MAX_SOURCE = 4000
        const val MAX_STEPS = 20_000

        /** Parses [source]. Throws [ExpressionException] with a character position on error. */
        fun compile(source: String): Expression {
            if (source.length > MAX_SOURCE) throw ExpressionException("expression too long (max $MAX_SOURCE chars)")
            return Expression(source, Parser(source).parseProgram())
        }
        fun tryCompile(source: String): Result<Expression> = runCatching { compile(source) }

        private val PROP_SHORTCUTS = mapOf(
            "position" to "Transform.Position", "scale" to "Transform.Scale", "rotation" to "Transform.Rotation",
            "opacity" to "Transform.Opacity", "anchor" to "Transform.Anchor", "skew" to "Transform.Skew")
    }

    fun evaluate(host: ExpressionHost): XVal = Interp(host).run(root)

    private class Interp(val host: ExpressionHost) {
        private var steps = 0
        private var randCalls = 0
        private var seed = host.seed
        private val vars = HashMap<String, XVal>()
        private val tSec get() = host.timeMs / 1000.0

        fun run(n: Node): XVal {
            if (++steps > MAX_STEPS) throw ExpressionException("expression too complex (step limit)", n.pos)
            return when (n) {
                is Node.Lit -> n.v
                is Node.VecLit -> {
                    val parts = n.items.map { run(it) }
                    val out = ArrayList<Double>()
                    for (v in parts) when (v) { is XVal.Num -> out.add(v.v); is XVal.Vec -> v.v.forEach { out.add(it) }; is XVal.Str -> throw ExpressionException("strings cannot be vector components", n.pos) }
                    if (out.size > 4) throw ExpressionException("vectors support at most 4 components", n.pos)
                    if (out.size == 1) XVal.Num(out[0]) else XVal.Vec(out.toDoubleArray())
                }
                is Node.Ident -> ident(n)
                is Node.Call -> call(n)
                is Node.Unary -> {
                    val v = run(n.e)
                    when (n.op) {
                        "-" -> map(v) { -it }
                        "+" -> v
                        else -> XVal.Num(if (truthy(v)) 0.0 else 1.0)
                    }
                }
                is Node.Binary -> binary(n)
                is Node.Ternary -> if (truthy(run(n.c))) run(n.a) else run(n.b)
                is Node.Index -> {
                    val v = run(n.e); val i = run(n.i).asDouble().toInt()
                    when (v) {
                        is XVal.Vec -> XVal.Num(v.v.getOrElse(i) { throw ExpressionException("index $i out of range", n.pos) })
                        is XVal.Num -> if (i == 0) v else throw ExpressionException("index $i out of range", n.pos)
                        is XVal.Str -> throw ExpressionException("cannot index a string", n.pos)
                    }
                }
                is Node.Member -> {
                    val v = run(n.e)
                    when (n.name) {
                        "x" -> comp(v, 0, n); "y" -> comp(v, 1, n); "z" -> comp(v, 2, n); "w" -> comp(v, 3, n)
                        "length" -> XVal.Num(if (v is XVal.Vec) v.v.size.toDouble() else 1.0)
                        else -> throw ExpressionException("unknown member '${n.name}'", n.pos)
                    }
                }
                is Node.Assign -> { val v = run(n.e); vars[n.name] = v; v }
                is Node.Block -> { var last: XVal = XVal.Num(0.0); for (s in n.stmts) last = run(s); last }
            }
        }

        private fun comp(v: XVal, i: Int, n: Node): XVal = when (v) {
            is XVal.Vec -> XVal.Num(v.v.getOrElse(i) { throw ExpressionException("no component $i", n.pos) })
            is XVal.Num -> if (i == 0) v else throw ExpressionException("no component $i", n.pos)
            is XVal.Str -> throw ExpressionException("not a vector", n.pos)
        }

        private fun ident(n: Node.Ident): XVal {
            vars[n.name]?.let { return it }
            return when (n.name) {
                "time" -> XVal.Num(tSec)
                "value" -> host.value
                "pi", "PI" -> XVal.Num(PI)
                "fps" -> XVal.Num(host.fps)
                "duration" -> XVal.Num(host.durationMs / 1000.0)
                "true" -> XVal.Num(1.0); "false" -> XVal.Num(0.0)
                in PROP_SHORTCUTS -> host.propertyAt(null, PROP_SHORTCUTS.getValue(n.name), host.timeMs)
                    ?: throw ExpressionException("property '${n.name}' is not available", n.pos)
                else -> throw ExpressionException("unknown name '${n.name}'", n.pos)
            }
        }

        private fun truthy(v: XVal) = when (v) { is XVal.Num -> v.v != 0.0 && !v.v.isNaN(); is XVal.Vec -> v.v.any { it != 0.0 }; is XVal.Str -> v.s.isNotEmpty() }

        private fun map(v: XVal, f: (Double) -> Double): XVal = when (v) {
            is XVal.Num -> XVal.Num(f(v.v))
            is XVal.Vec -> XVal.Vec(DoubleArray(v.v.size) { f(v.v[it]) })
            is XVal.Str -> throw ExpressionException("cannot apply math to a string")
        }
        private fun zip(a: XVal, b: XVal, pos: Int, f: (Double, Double) -> Double): XVal = when {
            a is XVal.Str || b is XVal.Str -> throw ExpressionException("cannot apply math to a string", pos)
            a is XVal.Num && b is XVal.Num -> XVal.Num(f(a.v, b.v))
            a is XVal.Vec && b is XVal.Num -> XVal.Vec(DoubleArray(a.v.size) { f(a.v[it], b.v) })
            a is XVal.Num && b is XVal.Vec -> XVal.Vec(DoubleArray(b.v.size) { f(a.v, b.v[it]) })
            a is XVal.Vec && b is XVal.Vec -> {
                if (a.v.size != b.v.size) throw ExpressionException("vector size mismatch (${a.v.size} vs ${b.v.size})", pos)
                XVal.Vec(DoubleArray(a.v.size) { f(a.v[it], b.v[it]) })
            }
            else -> throw ExpressionException("bad operands", pos)
        }

        private fun binary(n: Node.Binary): XVal {
            if (n.op == "&&") { val l = run(n.l); return if (!truthy(l)) XVal.Num(0.0) else XVal.Num(if (truthy(run(n.r))) 1.0 else 0.0) }
            if (n.op == "||") { val l = run(n.l); return if (truthy(l)) XVal.Num(1.0) else XVal.Num(if (truthy(run(n.r))) 1.0 else 0.0) }
            val l = run(n.l); val r = run(n.r)
            return when (n.op) {
                "+" -> if (l is XVal.Str || r is XVal.Str) XVal.Str(str(l) + str(r)) else zip(l, r, n.pos) { a, b -> a + b }
                "-" -> zip(l, r, n.pos) { a, b -> a - b }
                "*" -> zip(l, r, n.pos) { a, b -> a * b }
                "/" -> zip(l, r, n.pos) { a, b -> if (b == 0.0) 0.0 else a / b }       // AE yields 0 on division by zero
                "%" -> zip(l, r, n.pos) { a, b -> if (b == 0.0) 0.0 else a - b * floor(a / b) }
                "^" -> zip(l, r, n.pos) { a, b -> a.pow(b) }
                "==" -> XVal.Num(if (l == r) 1.0 else 0.0)
                "!=" -> XVal.Num(if (l != r) 1.0 else 0.0)
                "<" -> XVal.Num(if (l.asDouble() < r.asDouble()) 1.0 else 0.0)
                "<=" -> XVal.Num(if (l.asDouble() <= r.asDouble()) 1.0 else 0.0)
                ">" -> XVal.Num(if (l.asDouble() > r.asDouble()) 1.0 else 0.0)
                ">=" -> XVal.Num(if (l.asDouble() >= r.asDouble()) 1.0 else 0.0)
                else -> throw ExpressionException("unknown operator ${n.op}", n.pos)
            }
        }
        private fun str(v: XVal) = when (v) { is XVal.Str -> v.s; is XVal.Num -> if (v.v == floor(v.v) && abs(v.v) < 1e12) v.v.toLong().toString() else v.v.toString(); is XVal.Vec -> v.toString() }

        // ---------------- functions ----------------
        private fun arity(n: Node.Call, min: Int, max: Int = min) {
            if (n.args.size < min || n.args.size > max)
                throw ExpressionException("${n.name}() takes ${if (min == max) "$min" else "$min-$max"} argument(s), got ${n.args.size}", n.pos)
        }
        private fun arg(n: Node.Call, i: Int): XVal = run(n.args[i])

        private fun call(n: Node.Call): XVal {
            val f1: ((Double) -> Double)? = when (n.name) {
                "sin" -> ::sin; "cos" -> ::cos; "tan" -> ::tan; "asin" -> { x -> asin(x.coerceIn(-1.0, 1.0)) }
                "acos" -> { x -> acos(x.coerceIn(-1.0, 1.0)) }; "atan" -> ::atan
                "sqrt" -> { x -> if (x < 0) 0.0 else sqrt(x) }; "abs" -> ::abs; "exp" -> ::exp
                "log", "ln" -> { x -> if (x <= 0) 0.0 else ln(x) }; "log10" -> { x -> if (x <= 0) 0.0 else log10(x) }
                "floor" -> ::floor; "ceil" -> ::ceil; "round" -> { x -> floor(x + 0.5) }
                "sign" -> { x -> sign(x) }; "fract" -> { x -> x - floor(x) }
                "degreesToRadians", "radians" -> { x -> Math.toRadians(x) }
                "radiansToDegrees", "degrees" -> { x -> Math.toDegrees(x) }
                else -> null
            }
            if (f1 != null) { arity(n, 1); return map(arg(n, 0), f1) }
            return when (n.name) {
                "atan2" -> { arity(n, 2); XVal.Num(atan2(arg(n, 0).asDouble(), arg(n, 1).asDouble())) }
                "pow" -> { arity(n, 2); zip(arg(n, 0), arg(n, 1), n.pos) { a, b -> a.pow(b) } }
                "mod" -> { arity(n, 2); zip(arg(n, 0), arg(n, 1), n.pos) { a, b -> if (b == 0.0) 0.0 else a - b * floor(a / b) } }
                "min" -> { arity(n, 2, 8); n.args.indices.map { arg(n, it) }.reduce { a, b -> zip(a, b, n.pos) { x, y -> min(x, y) } } }
                "max" -> { arity(n, 2, 8); n.args.indices.map { arg(n, it) }.reduce { a, b -> zip(a, b, n.pos) { x, y -> max(x, y) } } }
                "clamp" -> { arity(n, 3); val lo = arg(n, 1); val hi = arg(n, 2); zip(zip(arg(n, 0), lo, n.pos) { x, l -> max(x, l) }, hi, n.pos) { x, h -> min(x, h) } }
                "lerp" -> { arity(n, 3); val a = arg(n, 0); val b = arg(n, 1); val t = arg(n, 2).asDouble(); zip(a, b, n.pos) { x, y -> x + (y - x) * t } }
                "linear" -> interp(n, 0)
                "ease" -> interp(n, 1)
                "easeIn" -> interp(n, 2)
                "easeOut" -> interp(n, 3)
                "smoothstep" -> { arity(n, 3); val e0 = arg(n, 0).asDouble(); val e1 = arg(n, 1).asDouble(); val x = arg(n, 2).asDouble()
                    val t = if (e1 == e0) (if (x < e0) 0.0 else 1.0) else ((x - e0) / (e1 - e0)).coerceIn(0.0, 1.0); XVal.Num(t * t * (3 - 2 * t)) }
                "step" -> { arity(n, 2); XVal.Num(if (arg(n, 1).asDouble() >= arg(n, 0).asDouble()) 1.0 else 0.0) }
                "length" -> { arity(n, 1, 2); if (n.args.size == 1) XVal.Num(vlen(arg(n, 0))) else XVal.Num(vlen(zip(arg(n, 0), arg(n, 1), n.pos) { a, b -> a - b })) }
                "distance" -> { arity(n, 2); XVal.Num(vlen(zip(arg(n, 0), arg(n, 1), n.pos) { a, b -> a - b })) }
                "normalize" -> { arity(n, 1); val v = arg(n, 0); val l = vlen(v); if (l < 1e-12) v else map(v) { it / l } }
                "dot" -> { arity(n, 2); val p = zip(arg(n, 0), arg(n, 1), n.pos) { a, b -> a * b }; XVal.Num(if (p is XVal.Vec) p.v.sum() else p.asDouble()) }
                "add" -> { arity(n, 2); zip(arg(n, 0), arg(n, 1), n.pos) { a, b -> a + b } }
                "sub" -> { arity(n, 2); zip(arg(n, 0), arg(n, 1), n.pos) { a, b -> a - b } }
                "mul" -> { arity(n, 2); zip(arg(n, 0), arg(n, 1), n.pos) { a, b -> a * b } }
                "div" -> { arity(n, 2); zip(arg(n, 0), arg(n, 1), n.pos) { a, b -> if (b == 0.0) 0.0 else a / b } }
                "seedRandom" -> { arity(n, 1, 2); seed = arg(n, 0).asDouble().toLong(); randCalls = 0; XVal.Num(0.0) }
                "random" -> { arity(n, 0, 2); random(n) }
                "noise" -> { arity(n, 1); XVal.Num(Procedural.valueNoise(seed, arg(n, 0).asDouble())) }
                "wiggle" -> { arity(n, 2, 5); wiggle(n) }
                "loopOut", "loopIn" -> { arity(n, 0, 2); loop(n, n.name == "loopOut") }
                "valueAtTime" -> { arity(n, 1); host.valueAt((arg(n, 0).asDouble() * 1000.0).roundToLong()) }
                "velocityAtTime" -> { arity(n, 1); val ms = (arg(n, 0).asDouble() * 1000.0).roundToLong()
                    zip(host.valueAt(ms + 2), host.valueAt(ms - 2), n.pos) { a, b -> (a - b) / 0.004 } }
                "numKeys" -> { arity(n, 0); XVal.Num(host.keyTimes().size.toDouble()) }
                "keyTime" -> { arity(n, 1); val k = host.keyTimes(); val i = arg(n, 0).asDouble().toInt() - 1
                    XVal.Num(k.getOrNull(i)?.div(1000.0) ?: throw ExpressionException("key ${i + 1} does not exist", n.pos)) }
                "keyValue" -> { arity(n, 1); val i = arg(n, 0).asDouble().toInt() - 1
                    host.keyValue(i) ?: throw ExpressionException("key ${i + 1} does not exist", n.pos) }
                "markerTime" -> { arity(n, 1); val s = arg(n, 0); XVal.Num((host.markerTime((s as? XVal.Str)?.s ?: str(s)) ?: throw ExpressionException("no marker", n.pos)) / 1000.0) }
                "prop" -> { arity(n, 2, 3)
                    val target = (arg(n, 0) as? XVal.Str)?.s ?: throw ExpressionException("prop() target must be a string", n.pos)
                    val property = (arg(n, 1) as? XVal.Str)?.s ?: throw ExpressionException("prop() property must be a string", n.pos)
                    val t = if (n.args.size == 3) (arg(n, 2).asDouble() * 1000.0).roundToLong() else host.timeMs
                    host.propertyAt(target.ifEmpty { null }, property, t) ?: throw ExpressionException("unknown property $target/$property", n.pos) }
                "thisProp" -> { arity(n, 1, 2)
                    val property = (arg(n, 0) as? XVal.Str)?.s ?: throw ExpressionException("thisProp() name must be a string", n.pos)
                    val t = if (n.args.size == 2) (arg(n, 1).asDouble() * 1000.0).roundToLong() else host.timeMs
                    host.propertyAt(null, property, t) ?: throw ExpressionException("unknown property $property", n.pos) }
                else -> throw ExpressionException("unknown function '${n.name}'", n.pos)
            }
        }

        private fun vlen(v: XVal) = when (v) { is XVal.Num -> abs(v.v); is XVal.Vec -> sqrt(v.v.sumOf { it * it }); is XVal.Str -> throw ExpressionException("not a number") }

        /** linear/ease/easeIn/easeOut(t, tMin, tMax, value1, value2): 4-5 args like AE (2-arg form maps 0..1 to value1..value2). */
        private fun interp(n: Node.Call, kind: Int): XVal {
            arity(n, 3, 5)
            val t: Double; val t1: Double; val t2: Double; val v1: XVal; val v2: XVal
            if (n.args.size == 3) { t = arg(n, 0).asDouble(); t1 = 0.0; t2 = 1.0; v1 = arg(n, 1); v2 = arg(n, 2) }
            else if (n.args.size == 5) { t = arg(n, 0).asDouble(); t1 = arg(n, 1).asDouble(); t2 = arg(n, 2).asDouble(); v1 = arg(n, 3); v2 = arg(n, 4) }
            else throw ExpressionException("${n.name}() takes 3 or 5 arguments", n.pos)
            val raw = if (t2 == t1) (if (t < t1) 0.0 else 1.0) else ((t - t1) / (t2 - t1)).coerceIn(0.0, 1.0)
            val u = when (kind) {
                0 -> raw
                1 -> raw * raw * (3 - 2 * raw)
                2 -> raw * raw * raw
                else -> 1 - (1 - raw).pow(3)
            }
            return zip(v1, v2, n.pos) { a, b -> a + (b - a) * u }
        }

        private fun random(n: Node.Call): XVal {
            val frame = floor(tSec * host.fps).toLong()
            val r = Procedural.rand01(seed + 31L * (randCalls++), frame)
            return when (n.args.size) {
                0 -> XVal.Num(r)
                1 -> XVal.Num(r * arg(n, 0).asDouble())
                else -> XVal.Num(arg(n, 0).asDouble() + r * (arg(n, 1).asDouble() - arg(n, 0).asDouble()))
            }
        }

        private fun wiggle(n: Node.Call): XVal {
            val freq = arg(n, 0).asDouble(); val amp = arg(n, 1)
            val octaves = if (n.args.size > 2) arg(n, 2).asDouble().toInt().coerceIn(1, 8) else 1
            val mult = if (n.args.size > 3) arg(n, 3).asDouble() else 0.5
            val t = if (n.args.size > 4) arg(n, 4).asDouble() else tSec
            val base = host.value
            fun noiseFor(channel: Int): Double {
                var s = 0.0; var a = 1.0; var f = max(freq, 1e-6)
                for (o in 0 until octaves) { s += a * Procedural.valueNoise(seed + o * 7919L + channel * 104729L, t * f); a *= mult; f *= 2.0 }
                return s
            }
            return when (base) {
                is XVal.Num -> XVal.Num(base.v + noiseFor(0) * amp.asDouble())
                is XVal.Vec -> XVal.Vec(DoubleArray(base.v.size) { i ->
                    val a = if (amp is XVal.Vec) amp.v.getOrElse(i) { amp.v.last() } else amp.asDouble()
                    base.v[i] + noiseFor(i) * a
                })
                is XVal.Str -> base
            }
        }

        private fun loop(n: Node.Call, out: Boolean): XVal {
            val type = if (n.args.isNotEmpty()) ((arg(n, 0) as? XVal.Str)?.s ?: "cycle") else "cycle"
            val numKeys = if (n.args.size > 1) arg(n, 1).asDouble().toInt() else 0
            val keys = host.keyTimes()
            if (keys.size < 2) return host.value
            val startMs: Long; val endMs: Long
            if (out) { endMs = keys.last(); startMs = if (numKeys > 0) keys[(keys.size - 1 - numKeys).coerceAtLeast(0)] else keys.first() }
            else { startMs = keys.first(); endMs = if (numKeys > 0) keys[numKeys.coerceAtMost(keys.size - 1)] else keys.last() }
            val span = (endMs - startMs).coerceAtLeast(1L)
            val t = host.timeMs
            if (out && t <= endMs) return host.valueAt(t)
            if (!out && t >= startMs) return host.valueAt(t)
            val dt = if (out) t - endMs else startMs - t
            val cycles = dt / span; val rem = dt % span
            val vStart = host.valueAt(startMs); val vEnd = host.valueAt(endMs)
            return when (type.lowercase()) {
                "pingpong" -> {
                    val forward = cycles % 2L == 0L
                    val tm = if (out) (if (forward) endMs - rem else startMs + rem) else (if (forward) startMs + rem else endMs - rem)
                    host.valueAt(tm)
                }
                "offset" -> {
                    val tm = if (out) startMs + rem else endMs - rem
                    val delta = zip(vEnd, vStart, n.pos) { a, b -> a - b }
                    val k = (cycles + 1).toDouble() * (if (out) 1.0 else -1.0)
                    zip(host.valueAt(tm), map(delta) { it * k }, n.pos) { a, b -> a + b }
                }
                "continue" -> {
                    val anchorT = if (out) endMs else startMs
                    val v0 = host.valueAt(anchorT)
                    // one-sided difference taken INSIDE the keyframe range (outside it the value is held, i.e. flat)
                    val vel = if (out) zip(v0, host.valueAt(anchorT - 10), n.pos) { a, b -> (a - b) / 0.010 }
                              else zip(host.valueAt(anchorT + 10), v0, n.pos) { a, b -> (a - b) / 0.010 }
                    val secs = (t - anchorT) / 1000.0
                    zip(v0, map(vel) { it * secs }, n.pos) { a, b -> a + b }
                }
                else -> host.valueAt(if (out) startMs + rem else endMs - rem)            // "cycle"
            }
        }

    }
}
