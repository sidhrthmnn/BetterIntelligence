package com.example.engine

import java.math.BigDecimal
import java.math.MathContext

/** Small bounded decimal calculator. Only explicit arithmetic expressions are
 * admitted; never interpret model code, identifiers, dates or supplied prose. */
object LocalCalculation {
    fun evaluate(question: String): String? = runCatching {
        val input = question.trim().substringBefore('?').trimEnd('.', ' ')
            .replace(Regex("(?i)^(?:what is|calculate|compute|solve)\\s+"), "")
            .replace('×', '*').replace('÷', '/').replace('−', '-')
        require(input.length in 1..100)
        require(!Regex("\\d{4}-\\d{1,2}-\\d{1,2}").matches(input))
        val percent = Regex("^(-?\\d+(?:\\.\\d+)?)\\s*%\\s*(?i:of)\\s*(-?\\d+(?:\\.\\d+)?)$").matchEntire(input)
        val result = if (percent != null) percent.groupValues[1].toBigDecimal().multiply(percent.groupValues[2].toBigDecimal(), MathContext.DECIMAL128).divide(BigDecimal(100), MathContext.DECIMAL128)
            else {
                require(input.any { it in "+*/" } || Regex("\\d\\s*-\\s*\\d").containsMatchIn(input))
                require(input.all { it in "0123456789.+-*/() " })
                Parser(input).parse()
            }
        result.stripTrailingZeros().toPlainString().takeIf { it.length <= 100 }
    }.getOrNull()

    private class Parser(val text: String) {
        var position = 0; var depth = 0
        fun spaces() { while (position < text.length && text[position] == ' ') position++ }
        fun take(c: Char): Boolean { spaces(); return (position < text.length && text[position] == c).also { if (it) position++ } }
        fun parse(): BigDecimal = sum().also { spaces(); require(position == text.length) }
        fun sum(): BigDecimal {
            var value = product()
            while (true) value = when { take('+') -> value.add(product(), MathContext.DECIMAL128); take('-') -> value.subtract(product(), MathContext.DECIMAL128); else -> return value }
        }
        fun product(): BigDecimal {
            var value = atom()
            while (true) value = when { take('*') -> value.multiply(atom(), MathContext.DECIMAL128); take('/') -> value.divide(atom(), MathContext.DECIMAL128); else -> return value }
        }
        fun atom(): BigDecimal {
            require(++depth <= 12)
            try {
                if (take('-')) return atom().negate()
                if (take('+')) return atom()
                if (take('(')) return sum().also { require(take(')')) }
                spaces(); val start = position
                while (position < text.length && text[position] in "0123456789.") position++
                require(position - start in 1..32)
                return text.substring(start, position).toBigDecimal()
            } finally { depth-- }
        }
    }
}
