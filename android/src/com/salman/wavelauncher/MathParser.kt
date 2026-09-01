package com.salman.wavelauncher

/**
 * Recursive-descent evaluator for the search calculator.
 * Accepts + - * / ( ) and decimal numbers. Returns null if the query
 * is not a pure arithmetic expression (caller falls through to other results).
 */
class MathParser(private val s: String) {
    private var i = 0

    private fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
    private fun eat(c: Char): Boolean { ws(); if (i < s.length && s[i] == c) { i++; return true }; return false }

    fun parse(): Double? {
        val v = expr() ?: return null
        ws()
        return if (i == s.length) v else null
    }

    private fun expr(): Double? {
        var left = term() ?: return null
        while (true) {
            ws()
            when {
                eat('+') -> left += term() ?: return null
                eat('-') -> left -= term() ?: return null
                else -> return left
            }
        }
    }

    private fun term(): Double? {
        var left = unary() ?: return null
        while (true) {
            ws()
            when {
                eat('*') -> left *= unary() ?: return null
                eat('/') -> {
                    val d = unary() ?: return null
                    if (d == 0.0) return null
                    left /= d
                }
                else -> return left
            }
        }
    }

    private fun unary(): Double? {
        ws()
        if (eat('-')) return -(unary() ?: return null)
        if (eat('+')) return unary()
        return atom()
    }

    private fun atom(): Double? {
        ws()
        if (eat('(')) {
            val v = expr() ?: return null
            return if (eat(')')) v else null
        }
        val start = i
        while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
        if (start == i) return null
        return s.substring(start, i).toDoubleOrNull()
    }

    companion object {
        fun evaluate(q: String): Double? = try {
            MathParser(q.replace("×", "*").replace("÷", "/")).parse()
        } catch (_: Exception) { null }

        fun format(v: Double): String {
            return if (v == Math.floor(v) && !v.isInfinite() && Math.abs(v) < 1e15) {
                v.toLong().toString()
            } else {
                String.format(java.util.Locale.US, "%.6f", v).trimEnd('0').trimEnd('.')
            }
        }
    }
}
