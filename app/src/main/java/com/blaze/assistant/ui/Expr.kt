package com.blaze.assistant.ui

/**
 * v0.26.1 mini tools: a tiny, safe arithmetic evaluator for the formulas in a ```tool block (ExprTest).
 * Pure Kotlin, no reflection, no scripting engine, no WebView: it only knows numbers, the tool's own input names,
 * + - * / % ^ ( ), comparisons (< <= > >= == !=, giving 1 or 0) and a fixed list of functions:
 * min, max, round(x[, digits]), ceil, floor, abs, sqrt, pow, if(cond, a, b). Anything else is an error.
 * A result that isn't a finite number (divide by zero, sqrt of a negative) comes back as null, shown blank.
 */
object Expr {
    class Bad(msg: String) : Exception(msg)

    const val MAX_LEN = 400
    private const val MAX_DEPTH = 40

    val FUNCTIONS = setOf("min", "max", "round", "ceil", "floor", "abs", "sqrt", "pow", "if")

    /** The value of [src] with [vars], or null when it can't be worked out (bad formula, unknown name, not finite). */
    fun eval(src: String, vars: Map<String, Double> = emptyMap()): Double? = try {
        val v = Parser(src, vars).run()
        if (v.isFinite()) v else null
    } catch (_: Bad) { null } catch (_: ArithmeticException) { null } catch (_: StackOverflowError) { null }

    /** Like [eval] but says why it failed (tests and the tool card's tiny error hint). */
    fun check(src: String, names: Set<String>): String? = try {
        Parser(src, names.associateWith { 1.0 }).run(); null
    } catch (e: Bad) { e.message ?: "bad formula" }

    private class Parser(val s: String, val vars: Map<String, Double>) {
        var i = 0
        var depth = 0

        fun run(): Double {
            if (s.length > MAX_LEN) throw Bad("formula too long")
            if (s.isBlank()) throw Bad("empty formula")
            val v = comparison()
            ws()
            if (i < s.length) throw Bad("unexpected '${s[i]}'")
            return v
        }

        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun peek(t: String): Boolean { ws(); return s.startsWith(t, i) }
        fun eat(t: String): Boolean { if (peek(t)) { i += t.length; return true }; return false }

        fun enter() { if (++depth > MAX_DEPTH) throw Bad("formula too deep") }
        fun leave() { depth-- }

        fun comparison(): Double {
            enter()
            var a = additive()
            while (true) {
                a = when {
                    eat("<=") -> if (a <= additive()) 1.0 else 0.0
                    eat(">=") -> if (a >= additive()) 1.0 else 0.0
                    eat("==") -> if (a == additive()) 1.0 else 0.0
                    eat("!=") -> if (a != additive()) 1.0 else 0.0
                    eat("<") -> if (a < additive()) 1.0 else 0.0
                    eat(">") -> if (a > additive()) 1.0 else 0.0
                    else -> { leave(); return a }
                }
            }
        }

        fun additive(): Double {
            var a = term()
            while (true) {
                a = when {
                    eat("+") -> a + term()
                    eat("-") -> a - term()
                    else -> return a
                }
            }
        }

        fun term(): Double {
            var a = unary()
            while (true) {
                a = when {
                    eat("*") -> a * unary()
                    eat("/") -> { val b = unary(); if (b == 0.0) Double.NaN else a / b }
                    eat("%") -> { val b = unary(); if (b == 0.0) Double.NaN else a % b }
                    else -> return a
                }
            }
        }

        // Unary minus binds looser than ^, so -2^2 = -4 (as in maths and spreadsheets' POWER).
        fun unary(): Double {
            enter()
            val v = when {
                eat("-") -> -unary()
                eat("+") -> unary()
                else -> power()
            }
            leave(); return v
        }

        fun power(): Double {
            val base = atom()
            if (eat("^")) { enter(); val e = unary(); leave(); return Math.pow(base, e) }   // right-associative
            return base
        }

        fun atom(): Double {
            ws()
            if (i >= s.length) throw Bad("formula ends early")
            val ch = s[i]
            if (eat("(")) { val v = comparison(); if (!eat(")")) throw Bad("missing )"); return v }
            if (ch.isDigit() || ch == '.') return number()
            if (ch.isLetter() || ch == '_') {
                val st = i
                while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
                val name = s.substring(st, i)
                if (peek("(")) return call(name.lowercase())
                return vars[name] ?: vars.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value ?: when (name.lowercase()) {
                    "pi" -> Math.PI
                    else -> throw Bad("unknown name '$name'")
                }
            }
            throw Bad("unexpected '$ch'")
        }

        fun number(): Double {
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == '_')) i++
            if (i < s.length && (s[i] == 'e' || s[i] == 'E') && i + 1 < s.length && (s[i + 1].isDigit() || ((s[i + 1] == '-' || s[i + 1] == '+') && i + 2 < s.length && s[i + 2].isDigit()))) {
                i += 2; while (i < s.length && s[i].isDigit()) i++
            }
            return s.substring(st, i).replace("_", "").toDoubleOrNull() ?: throw Bad("bad number")
        }

        fun call(name: String): Double {
            if (name !in FUNCTIONS) throw Bad("unknown function '$name'")
            eat("(")
            enter()
            val args = ArrayList<Double>()
            if (!peek(")")) {
                do { args += comparison() } while (eat(","))
            }
            if (!eat(")")) throw Bad("missing )")
            leave()
            fun need(n: IntRange) { if (args.size !in n) throw Bad("$name takes ${if (n.first == n.last) "${n.first}" else "${n.first}-${n.last}"} values") }
            return when (name) {
                "min" -> { if (args.isEmpty()) throw Bad("min needs values"); args.min() }
                "max" -> { if (args.isEmpty()) throw Bad("max needs values"); args.max() }
                "round" -> { need(1..2); val d = (args.getOrNull(1) ?: 0.0).toInt().coerceIn(0, 10); val f = Math.pow(10.0, d.toDouble()); Math.round(args[0] * f) / f }
                "ceil" -> { need(1..1); Math.ceil(args[0]) }
                "floor" -> { need(1..1); Math.floor(args[0]) }
                "abs" -> { need(1..1); Math.abs(args[0]) }
                "sqrt" -> { need(1..1); Math.sqrt(args[0]) }
                "pow" -> { need(2..2); Math.pow(args[0], args[1]) }
                "if" -> { need(3..3); if (args[0] != 0.0 && !args[0].isNaN()) args[1] else args[2] }
                else -> throw Bad("unknown function '$name'")
            }
        }
    }

    /** A number for a tool's output: [decimals] places, thousands separators, trailing zeros kept for money (2 places). */
    fun format(v: Double?, decimals: Int = 2, prefix: String = "", suffix: String = ""): String {
        if (v == null || !v.isFinite()) return ""
        val d = decimals.coerceIn(0, 6)
        val s = java.math.BigDecimal(v).setScale(d, java.math.RoundingMode.HALF_UP)
        val neg = s.signum() < 0
        val plain = s.abs().toPlainString()
        val whole = plain.substringBefore('.')
        val frac = plain.substringAfter('.', "")
        val grouped = whole.reversed().chunked(3).joinToString(",").reversed()
        val num = if (frac.isEmpty()) grouped else "$grouped.$frac"
        return (if (neg) "-" else "") + prefix + num + suffix
    }
}
