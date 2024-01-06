/*
 * Copyright (c) 2021, 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.machfour.round2precision.internal

import java.io.IOException

/*
 * For full details about this code see the following references:
 *
 * [1] Giulietti, "The Schubfach way to render doubles",
 *     https://drive.google.com/file/d/1gp5xv4CAa78SVgCeWfGqqI4FfYYYuNFb
 *
 * [2] IEEE Computer Society, "IEEE Standard for Floating-Point Arithmetic"
 *
 * [3] Bouvier & Zimmermann, "Division-Free Binary-to-Decimal Conversion"
 *
 * Divisions are avoided altogether for the benefit of those architectures
 * that do not provide specific machine instructions or where they are slow.
 * This is discussed in section 10 of [1].
 */

/**
 * This class exposes a method to render a `float` as a string.
 */
class FloatToDecimal private constructor() {
    private val bytes = ByteArray(MAX_CHARS)

    /* Index into bytes of rightmost valid character */
    private var index = 0

    private fun toDecimalString(v: Float): String {
        return when (toDecimal(v)) {
            NON_SPECIAL -> charsToString()
            PLUS_ZERO -> "0.0"
            MINUS_ZERO -> "-0.0"
            PLUS_INF -> "Infinity"
            MINUS_INF -> "-Infinity"
            else -> "NaN"
        }
    }

    /*
     * Returns
     *     PLUS_ZERO       iff v is 0.0
     *     MINUS_ZERO      iff v is -0.0
     *     PLUS_INF        iff v is POSITIVE_INFINITY
     *     MINUS_INF       iff v is NEGATIVE_INFINITY
     *     NAN             iff v is NaN
     */
    private fun toDecimal(v: Float): Int {
        /*
         * For full details see references [2] and [1].
         *
         * For finite v != 0, determine integers c and q such that
         *     |v| = c 2^q    and
         *     Q_MIN <= q <= Q_MAX    and
         *         either    2^(P-1) <= c < 2^P                 (normal)
         *         or        0 < c < 2^(P-1)  and  q = Q_MIN    (subnormal)
         */
        val bits = v.toRawBits()
        val t = bits and T_MASK
        val bq = (bits ushr P - 1) and BQ_MASK
        if (bq < BQ_MASK) {
            index = -1
            if (bits < 0) {
                append('-'.code)
            }
            if (bq != 0) {
                /* normal value. Here mq = -q */
                val mq = -Q_MIN + 1 - bq
                val c = C_MIN or t
                /* The fast path discussed in section 8.3 of [1] */
                if ((0 < mq) and (mq < P)) {
                    val f = c shr mq
                    if (f shl mq == c) {
                        return toChars(f, 0)
                    }
                }
                return toDecimal(-mq, c, 0)
            }
            if (t != 0) {
                /* subnormal value */
                return if (t < C_TINY
                ) toDecimal(Q_MIN, 10 * t, -1)
                else toDecimal(Q_MIN, t, 0)
            }
            return if (bits == 0) PLUS_ZERO else MINUS_ZERO
        }
        if (t != 0) {
            return NAN
        }
        return if (bits > 0) PLUS_INF else MINUS_INF
    }

    private fun toDecimal(q: Int, c: Int, dk: Int): Int {
        /*
         * The skeleton corresponds to figure 7 of [1].
         * The efficient computations are those summarized in figure 9.
         * Also check the appendix.
         *
         * Here's a correspondence between Java names and names in [1],
         * expressed as approximate LaTeX source code and informally.
         * Other names are identical.
         * cb:     \bar{c}     "c-bar"
         * cbr:    \bar{c}_r   "c-bar-r"
         * cbl:    \bar{c}_l   "c-bar-l"
         *
         * vb:     \bar{v}     "v-bar"
         * vbr:    \bar{v}_r   "v-bar-r"
         * vbl:    \bar{v}_l   "v-bar-l"
         *
         * rop:    r_o'        "r-o-prime"
         */
        val out = c and 0x1
        val cb = (c shl 2).toLong()
        val cbr = cb + 2
        val cbl: Long
        val k: Int
        /*
         * flog10pow2(e) = floor(log_10(2^e))
         * flog10threeQuartersPow2(e) = floor(log_10(3/4 2^e))
         * flog2pow10(e) = floor(log_2(10^e))
         */
        if ((c != C_MIN) or (q == Q_MIN)) {
            /* regular spacing */
            cbl = cb - 2
            k = flog10pow2(q)
        } else {
            /* irregular spacing */
            cbl = cb - 1
            k = flog10threeQuartersPow2(q)
        }
        val h = q + flog2pow10(-k) + 33

        /* g is as in the appendix */
        val g = g1(k) + 1

        val vb = rop(g, cb shl h)
        val vbl = rop(g, cbl shl h)
        val vbr = rop(g, cbr shl h)

        val s = vb shr 2
        if (s >= 100) {
            /*
             * For n = 9, m = 1 the table in section 10 of [1] shows
             *     s' = floor(s / 10) = floor(s 1_717_986_919 / 2^34)
             *
             * sp10 = 10 s'
             * tp10 = 10 t'
             * upin    iff    u' = sp10 10^k in Rv
             * wpin    iff    w' = tp10 10^k in Rv
             * See section 9.3 of [1].
             */
            val sp10 = 10 * (s * 1717986919L ushr 34).toInt()
            val tp10 = sp10 + 10
            val upin = vbl + out <= sp10 shl 2
            val wpin = (tp10 shl 2) + out <= vbr
            if (upin != wpin) {
                return toChars(if (upin) sp10 else tp10, k)
            }
        }

        /*
         * 10 <= s < 100    or    s >= 100  and  u', w' not in Rv
         * uin    iff    u = s 10^k in Rv
         * win    iff    w = t 10^k in Rv
         * See section 9.3 of [1].
         */
        val t = s + 1
        val uin = vbl + out <= s shl 2
        val win = (t shl 2) + out <= vbr
        if (uin != win) {
            /* Exactly one of u or w lies in Rv */
            return toChars(if (uin) s else t, k + dk)
        }
        /*
         * Both u and w lie in Rv: determine the one closest to v.
         * See section 9.3 of [1].
         */
        val cmp = vb - (s + t shl 1)
        return toChars(if (cmp < 0 || cmp == 0 && (s and 0x1) == 0) s else t, k + dk)
    }

    /*
     * Formats the decimal f 10^e.
     */
    private fun toChars(f: Int, e: Int): Int {
        /*
         * For details not discussed here see section 10 of [1].
         *
         * Determine len such that
         *     10^(len-1) <= f < 10^len
         */
        var f = f
        var e = e
        var len = flog10pow2(Integer.SIZE - f.countLeadingZeroBits())
        if (f >= pow10(len)) {
            len += 1
        }

        /*
         * Let fp and ep be the original f and e, respectively.
         * Transform f and e to ensure
         *     10^(H-1) <= f < 10^H
         *     fp 10^ep = f 10^(e-H) = 0.f 10^e
         */
        f *= pow10(H - len).toInt()
        e += len

        /*
         * The toChars?() methods perform left-to-right digits extraction
         * using ints, provided that the arguments are limited to 8 digits.
         * Therefore, split the H = 9 digits of f into:
         *     h = the most significant digit of f
         *     l = the last 8, least significant digits of f
         *
         * For n = 9, m = 8 the table in section 10 of [1] shows
         *     floor(f / 10^8) = floor(1_441_151_881 f / 2^57)
         */
        val h = (f * 1441151881L ushr 57).toInt()
        val l = f - 100000000 * h

        if (e in 1..7) {
            return toChars1(h, l, e)
        }
        if (e in -2..0) {
            return toChars2(h, l, e)
        }
        return toChars3(h, l, e)
    }

    private fun toChars1(h: Int, l: Int, e: Int): Int {
        /*
         * 0 < e <= 7: plain format without leading zeroes.
         * Left-to-right digits extraction:
         * algorithm 1 in [3], with b = 10, k = 8, n = 28.
         */
        appendDigit(h)
        var y = y(l)
        var t: Int
        var i = 1
        while (i < e) {
            t = 10 * y
            appendDigit(t ushr 28)
            y = t and MASK_28
            ++i
        }
        append('.'.code)
        while (i <= 8) {
            t = 10 * y
            appendDigit(t ushr 28)
            y = t and MASK_28
            ++i
        }
        removeTrailingZeroes()
        return NON_SPECIAL
    }

    private fun toChars2(h: Int, l: Int, e: Int): Int {
        /* -3 < e <= 0: plain format with leading zeroes */
        var e = e
        appendDigit(0)
        append('.'.code)
        while (e < 0) {
            appendDigit(0)
            ++e
        }
        appendDigit(h)
        append8Digits(l)
        removeTrailingZeroes()
        return NON_SPECIAL
    }

    private fun toChars3(h: Int, l: Int, e: Int): Int {
        /* -3 >= e | e > 7: computerized scientific notation */
        appendDigit(h)
        append('.'.code)
        append8Digits(l)
        removeTrailingZeroes()
        exponent(e - 1)
        return NON_SPECIAL
    }

    private fun append8Digits(m: Int) {
        /*
         * Left-to-right digits extraction:
         * algorithm 1 in [3], with b = 10, k = 8, n = 28.
         */
        var y = y(m)
        for (i in 0..7) {
            val t = 10 * y
            appendDigit(t ushr 28)
            y = t and MASK_28
        }
    }

    private fun removeTrailingZeroes() {
        while (bytes[index] == '0'.code.toByte()) {
            --index
        }
        /* ... but do not remove the one directly to the right of '.' */
        if (bytes[index] == '.'.code.toByte()) {
            ++index
        }
    }

    private fun y(a: Int): Int {
        /*
         * Algorithm 1 in [3] needs computation of
         *     floor((a + 1) 2^n / b^k) - 1
         * with a < 10^8, b = 10, k = 8, n = 28.
         * Noting that
         *     (a + 1) 2^n <= 10^8 2^28 < 10^17
         * For n = 17, m = 8 the table in section 10 of [1] leads to:
         */
        return (Math.multiplyHigh(
            (a + 1).toLong() shl 28,
            193428131138340668L
        ) ushr 20).toInt() - 1
    }

    private fun exponent(e: Int) {
        var e = e
        append('E'.code)
        if (e < 0) {
            append('-'.code)
            e = -e
        }
        if (e < 10) {
            appendDigit(e)
            return
        }
        /*
         * For n = 2, m = 1 the table in section 10 of [1] shows
         *     floor(e / 10) = floor(103 e / 2^10)
         */
        val d = e * 103 ushr 10
        appendDigit(d)
        appendDigit(e - 10 * d)
    }

    private fun append(c: Int) {
        bytes[++index] = c.toByte()
    }

    private fun appendDigit(d: Int) {
        bytes[++index] = ('0'.code + d).toByte()
    }

    private fun charsToString(): String {
        return String(bytes, 0, index + 1)
    }

    companion object {
        /* Number of bits in float mantissa */
        private const val PRECISION = 24 // 3f.toRawBits().countTrailingZeroBits() + 2

        /* The precision in bits */
        const val P: Int = PRECISION

        /* Exponent width in bits */
        private const val W = (Float.SIZE_BITS - 1) - (P - 1)

        /* Minimum value of the exponent: -(2^(W-1)) - P + 3 */
        const val Q_MIN: Int = (-1 shl (W - 1)) - P + 3

        /* Maximum value of the exponent: 2^(W-1) - P */
        const val Q_MAX: Int = (1 shl (W - 1)) - P

        /* 10^(E_MIN - 1) <= MIN_VALUE < 10^E_MIN */
        const val E_MIN: Int = -44

        /* 10^(E_MAX - 1) <= MAX_VALUE < 10^E_MAX */
        const val E_MAX: Int = 39

        /* Threshold to detect tiny values, as in section 8.2.1 of [1] */
        const val C_TINY: Int = 8

        /* The minimum and maximum k, as in section 8 of [1] */
        const val K_MIN: Int = -45
        const val K_MAX: Int = 31

        /* H is as in section 8.1 of [1] */
        const val H: Int = 9

        /* Minimum value of the significand of a normal value: 2^(P-1) */
        private const val C_MIN = 1 shl (P - 1)

        /* Mask to extract the biased exponent */
        private const val BQ_MASK = (1 shl W) - 1

        /* Mask to extract the fraction bits */
        private const val T_MASK = (1 shl (P - 1)) - 1

        /* Used in rop() */
        private const val MASK_32 = (1L shl 32) - 1

        /* Used for left-to-tight digit extraction */
        private const val MASK_28 = (1 shl 28) - 1

        private const val NON_SPECIAL = 0
        private const val PLUS_ZERO = 1
        private const val MINUS_ZERO = 2
        private const val PLUS_INF = 3
        private const val MINUS_INF = 4
        private const val NAN = 5

        /*
         * Room for the longer of the forms
         *     -ddddd.dddd         H + 2 characters
         *     -0.00ddddddddd      H + 5 characters
         *     -d.ddddddddE-ee     H + 6 characters
         * where there are H digits d
         */
        const val MAX_CHARS: Int = H + 6

        /**
         * Returns a string representation of the `float`
         * argument. All characters mentioned below are ASCII characters.
         *
         * @param   v   the `float` to be converted.
         * @return a string representation of the argument.
         * @see Float.toString
         */
        fun toString(v: Float): String {
            return FloatToDecimal().toDecimalString(v)
        }

        /*
         * Computes rop(cp g 2^(-95))
         * See appendix and figure 11 of [1].
         */
        private fun rop(g: Long, cp: Long): Int {
            val x1 = Math.multiplyHigh(g, cp)
            val vbp = x1 ushr 31
            return (vbp or ((x1 and MASK_32) + MASK_32 ushr 32)).toInt()
        }
    }
}
