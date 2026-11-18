package com.nexo.nexorith.lab.maven

import java.math.BigInteger

/** Pembanding versi bergaya Maven: 1.10 > 1.9, 1.0 == 1.0.0, 1.0-rc1 < 1.0 < 1.0-sp1. */
object MavenVersion : Comparator<String> {
    private val TOKEN = Regex("\\d+|[A-Za-z]+")

    private fun tokens(v: String): List<String> = TOKEN.findAll(v).map { it.value }.toList()

    private fun rank(q: String): Int = when (q.lowercase()) {
        "alpha", "a" -> 1
        "beta", "b" -> 2
        "milestone", "m" -> 3
        "rc", "cr" -> 4
        "snapshot" -> 5
        "final", "ga", "release" -> 6
        "sp" -> 7
        else -> 8
    }

    private fun isNumber(t: String): Boolean = t[0].isDigit()

    override fun compare(a: String, b: String): Int {
        val ta = tokens(a)
        val tb = tokens(b)
        for (i in 0 until maxOf(ta.size, tb.size)) {
            val c = compareToken(ta.getOrNull(i), tb.getOrNull(i))
            if (c != 0) return c
        }
        return 0
    }

    private fun compareToken(x: String?, y: String?): Int {
        if (x == null && y == null) return 0
        if (x == null) return -compareToken(y, x)
        if (y == null) {
            // token yang hilang dianggap "0" untuk angka dan "release" untuk kualifier
            return if (isNumber(x)) {
                if (x.toBigInteger() == BigInteger.ZERO) 0 else 1
            } else {
                rank(x).compareTo(6)
            }
        }
        val nx = isNumber(x)
        val ny = isNumber(y)
        return when {
            nx && ny -> x.toBigInteger().compareTo(y.toBigInteger())
            nx -> 1
            ny -> -1
            else -> {
                val rx = rank(x)
                val ry = rank(y)
                if (rx != ry) rx.compareTo(ry) else x.lowercase().compareTo(y.lowercase())
            }
        }
    }
}
