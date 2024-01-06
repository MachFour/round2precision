/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
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
 * This class exposes a method to render a `double` as a string.
 */
class DoubleToDecimal private constructor(withChars: Boolean) {
    private val bytes: ByteArray = if (withChars) ByteArray(MAX_CHARS) else byteArrayOf()

    /* Index into bytes of rightmost valid character */
    private var index = 0

    private fun toDecimalString(v: Double): String {
        return when (toDecimal(v, null)) {
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
    private fun toDecimal(v: Double, fd: FormattedFPDecimal?): Int {
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
        val bq = (bits ushr P - 1).toInt() and BQ_MASK
        if (bq < BQ_MASK) {
            index = -1
            if (bits < 0) {
                /*
                 * fd != null implies bytes == null and bits >= 0
                 * Thus, when fd != null, control never reaches here.
                 */
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
                        return toChars(f, 0, fd)
                    }
                }
                return toDecimal(-mq, c, 0, fd)
            }
            if (t != 0L) {
                /* subnormal value */
                return if (t < C_TINY) {
                    toDecimal(Q_MIN, 10 * t, -1, fd)
                } else {
                    toDecimal(Q_MIN, t, 0, fd)
                }
            }
            return if (bits == 0L) PLUS_ZERO else MINUS_ZERO
        }
        if (t != 0L) {
            return NAN
        }
        return if (bits > 0) PLUS_INF else MINUS_INF
    }

    private fun toDecimal(q: Int, c: Long, dk: Int, fd: FormattedFPDecimal?): Int {
        /*
         * The skeleton corresponds to figure 7 of [1].
         * The efficient computations are those summarized in figure 9.
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
        val out = c.toInt() and 0x1
        val cb = c shl 2
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
        val h = q + flog2pow10(-k) + 2

        /* g1 and g0 are as in section 9.8.3 of [1], so g = g1 2^63 + g0 */
        val g1 = g1(k)
        val g0 = g0(k)

        val vb = rop(g1, g0, cb shl h)
        val vbl = rop(g1, g0, cbl shl h)
        val vbr = rop(g1, g0, cbr shl h)

        val s = vb shr 2
        if (s >= 100) {
            /*
             * For n = 17, m = 1 the table in section 10 of [1] shows
             *     s' = floor(s / 10) = floor(s 115_292_150_460_684_698 / 2^60)
             *        = floor(s 115_292_150_460_684_698 2^4 / 2^64)
             *
             * sp10 = 10 s'
             * tp10 = 10 t'
             * upin    iff    u' = sp10 10^k in Rv
             * wpin    iff    w' = tp10 10^k in Rv
             * See section 9.3 of [1].
             */
            val sp10 = 10 * Math.multiplyHigh(s, 115292150460684698L shl 4)
            val tp10 = sp10 + 10
            val upin = vbl + out <= sp10 shl 2
            val wpin = (tp10 shl 2) + out <= vbr
            if (upin != wpin) {
                return toChars(if (upin) sp10 else tp10, k, fd)
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
            return toChars(if (uin) s else t, k + dk, fd)
        }
        /*
         * Both u and w lie in Rv: determine the one closest to v.
         * See section 9.3 of [1].
         */
        val cmp = vb - (s + t shl 1)
        return toChars(if (cmp < 0 || cmp == 0L && (s and 0x1L) == 0L) s else t, k + dk, fd)
    }

    /*
     * Formats the decimal f 10^e.
     */
    private fun toChars(f: Long, e: Int, fd: FormattedFPDecimal?): Int {
        /*
         * For details not discussed here see section 10 of [1].
         *
         * Determine len such that
         *     10^(len-1) <= f < 10^len
         */
        var f = f
        var e = e
        var len = flog10pow2(Long.SIZE_BITS - f.countLeadingZeroBits())
        if (f >= pow10(len)) {
            len += 1
        }
        if (fd != null) {
            fd.set(f, e, len)
            return NON_SPECIAL
        }

        /*
         * Let fp and ep be the original f and e, respectively.
         * Transform f and e to ensure
         *     10^(H-1) <= f < 10^H
         *     fp 10^ep = f 10^(e-H) = 0.f 10^e
         */
        f *= pow10(H - len)
        e += len

        /*
         * The toChars?() methods perform left-to-right digits extraction
         * using ints, provided that the arguments are limited to 8 digits.
         * Therefore, split the H = 17 digits of f into:
         *     h = the most significant digit of f
         *     m = the next 8 most significant digits of f
         *     l = the last 8, least significant digits of f
         *
         * For n = 17, m = 8 the table in section 10 of [1] shows
         *     floor(f / 10^8) = floor(193_428_131_138_340_668 f / 2^84) =
         *     floor(floor(193_428_131_138_340_668 f / 2^64) / 2^20)
         * and for n = 9, m = 8
         *     floor(hm / 10^8) = floor(1_441_151_881 hm / 2^57)
         */
        val hm = Math.multiplyHigh(f, 193428131138340668L) ushr 20
        val l = (f - 100000000L * hm).toInt()
        val h = (hm * 1441151881L ushr 57).toInt()
        val m = (hm - 100000000 * h).toInt()

        if (e in 1..7) {
            return toChars1(h, m, l, e)
        }
        if (e in -2 .. 0) {
            return toChars2(h, m, l, e)
        }
        return toChars3(h, m, l, e)
    }

    private fun toChars1(h: Int, m: Int, l: Int, e: Int): Int {
        /*
         * 0 < e <= 7: plain format without leading zeroes.
         * Left-to-right digits extraction:
         * algorithm 1 in [3], with b = 10, k = 8, n = 28.
         */
        appendDigit(h)
        var y = y(m)
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
        lowDigits(l)
        return NON_SPECIAL
    }

    private fun toChars2(h: Int, m: Int, l: Int, e: Int): Int {
        /* -3 < e <= 0: plain format with leading zeroes */
        var e = e
        appendDigit(0)
        append('.'.code)
        while (e < 0) {
            appendDigit(0)
            ++e
        }
        appendDigit(h)
        append8Digits(m)
        lowDigits(l)
        return NON_SPECIAL
    }

    private fun toChars3(h: Int, m: Int, l: Int, e: Int): Int {
        /* -3 >= e | e > 7: computerized scientific notation */
        appendDigit(h)
        append('.'.code)
        append8Digits(m)
        lowDigits(l)
        exponent(e - 1)
        return NON_SPECIAL
    }

    private fun lowDigits(l: Int) {
        if (l != 0) {
            append8Digits(l)
        }
        removeTrailingZeroes()
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
        var d: Int
        if (e >= 100) {
            /*
             * For n = 3, m = 2 the table in section 10 of [1] shows
             *     floor(e / 100) = floor(1_311 e / 2^17)
             */
            d = e * 1311 ushr 17
            appendDigit(d)
            e -= 100 * d
        }
        /*
         * For n = 2, m = 1 the table in section 10 of [1] shows
         *     floor(e / 10) = floor(103 e / 2^10)
         */
        d = e * 103 ushr 10
        appendDigit(d)
        appendDigit(e - 10 * d)
    }

    private fun append(c: Int) {
        bytes[++index] = c.toByte()
    }

    private fun appendDigit(d: Int) {
        bytes[++index] = ('0'.code + d).toByte()
    }

    /* Using the deprecated constructor enhances performance */
    private fun charsToString(): String {
        return String(bytes, 0, index + 1)
    }

    companion object {
        /* Number of bits in double mantissa */
        private const val PRECISION = 53

        /* The precision in bits */
        const val P: Int = PRECISION

        /* Exponent width in bits */
        private const val W = (Double.SIZE_BITS - 1) - (P - 1)

        /* Minimum value of the exponent: -(2^(W-1)) - P + 3 */
        const val Q_MIN: Int = (-1 shl (W - 1)) - P + 3

        /* Maximum value of the exponent: 2^(W-1) - P */
        const val Q_MAX: Int = (1 shl (W - 1)) - P

        /* 10^(E_MIN - 1) <= MIN_VALUE < 10^E_MIN */
        const val E_MIN: Int = -323

        /* 10^(E_MAX - 1) <= MAX_VALUE < 10^E_MAX */
        const val E_MAX: Int = 309

        /* Threshold to detect tiny values, as in section 8.2.1 of [1] */
        const val C_TINY: Long = 3

        /* The minimum and maximum k, as in section 8 of [1] */
        const val K_MIN: Int = -324
        const val K_MAX: Int = 292

        /* H is as in section 8.1 of [1] */
        const val H: Int = 17

        /* Minimum value of the significand of a normal value: 2^(P-1) */
        private const val C_MIN = 1L shl (P - 1)

        /* Mask to extract the biased exponent */
        private const val BQ_MASK = (1 shl W) - 1

        /* Mask to extract the fraction bits */
        private const val T_MASK = (1L shl (P - 1)) - 1

        /* Used in rop() */
        private const val MASK_63 = (1L shl 63) - 1

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
         *     -ddddd.dddddddddddd         H + 2 characters
         *     -0.00ddddddddddddddddd      H + 5 characters
         *     -d.ddddddddddddddddE-eee    H + 7 characters
         * where there are H digits d
         */
        const val MAX_CHARS: Int = H + 7

        /**
         * Returns a string representation of the `double`
         * argument. All characters mentioned below are ASCII characters.
         *
         * @param   v   the `double` to be converted.
         * @return a string representation of the argument.
         * @see Double.toString
         */
        fun toString(v: Double): String {
            return DoubleToDecimal(true).toDecimalString(v)
        }

        /**
         * Splits the decimal *d* described in
         * [Double.toString] in integers *f* and *e*
         * such that *d* = *f* 10<sup>*e*</sup>.
         *
         *
         * Further, determines integer *n* such that *n* = 0 when
         * *f* = 0, and
         * 10<sup>*n*-1</sup>  *f* &lt; 10<sup>*n*</sup>
         * otherwise.
         *
         *
         * The argument `v` is assumed to be a positive finite value or
         * positive zero.
         * Further, `fd` must not be `null`.
         *
         * @param v     the finite `double` to be split.
         * @param fd    the object that will carry *f*, *e*, and *n*.
         */
        fun split(v: Double, fd: FormattedFPDecimal?) {
            DoubleToDecimal(false).toDecimal(v, fd)
        }


        /*
         * Computes rop(cp g 2^(-127)), where g = g1 2^63 + g0
         * See section 9.9 and figure 8 of [1].
         */
        private fun rop(g1: Long, g0: Long, cp: Long): Long {
            val x1 = Math.multiplyHigh(g0, cp)
            val y0 = g1 * cp
            val y1 = Math.multiplyHigh(g1, cp)
            val z = (y0 ushr 1) + x1
            val vbp = y1 + (z ushr 63)
            return vbp or ((z and MASK_63) + MASK_63 ushr 63)
        }
    }
}

