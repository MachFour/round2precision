package com.machfour.round2precision.internal

import kotlin.math.max
import kotlin.math.min

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

/*
* This class provides support for the 'f' conversion on double values with
* sign bit 0.
*
* It delegates the conversion to decimal to class DoubleToDecimal to get
* the decimal d selected by Double.toString(double) as a pair of integers
* f and e meeting d = f 10^e.
* It then rounds d to the appropriate number of digits, as per specification,
* and extracts the digits of both the significand and, where required, the
* exponent of the rounded value.
*
* Further processing like padding, sign, grouping, localization, etc., is the
* responsibility of the caller.
*/
class FormattedFPDecimal internal constructor(
    // In the upstream JDK implementation, the number of digits after
    // the decimal point does not always match the requested precision.
    // This usually occurs with integers or when there are trailing zeros.
    // Some examples:
    // 1. Numbers rounded to 0 are always formatted as "0".
    // 2. Numbers rounded to other integers are formatted with trailing zeros
    //    if the input is not an integer.
    // 3. For powers of 10, an extra trailing zero is removed.
    // If consistentTrailingZeros is true, the formatting is changed so
    // that the number of decimal places always equals the precision.
    private val consistentTrailingZeros: Boolean
) {
    private var f: Long = 0
    private var e = 0 // normalized to 0 when f = 0
    private var n = 0

    fun set(f: Long, e: Int, n: Int) {
        /* Initially, n = 0 if f = 0, and 10^{n-1} <= f < 10^n if f != 0 */
        this.f = f
        this.e = e
        this.n = n
    }

    internal fun plain(prec: Int): String {
        /*
         * Rounding d = f 10^e to prec digits in plain mode means the same
         * as rounding it to the p = n + e + prec most significant digits of d,
         * with the understanding that p < 0 cuts off all its digits.
         */
        val p = n + e + prec.toLong() // n + e is well inside the int range
        round(p)

        val extra0s = if (consistentTrailingZeros) min(e, 0) + prec else 0

        val mantissa = if (e >= 0) {
            // pure integer - but add decimal point if extra0s > 0
            val size = n + e + extra0s + if (extra0s > 0) 1 else 0
            CharArray(size).apply {
                if (extra0s > 0) {
                    // add extra zeros and decimal point
                    fill('0', n + e + 1, size)
                    this[n + e] = '.'
                }
                fill('0', n, n + e)
                fillWithDigits(f, 0, n)
            }
        } else if (n + e > 0) {
            // mixed integer and fraction
            val size = n + 1 + extra0s
            CharArray(size).apply {
                fill('0', n + 1, size)
                val x = fillWithDigits(f, n + 1 + e, n + 1)
                this[n + e] = '.'
                fillWithDigits(x, 0, n + e)
            }
        } else {
            // pure fraction
            val size = 2 - e + extra0s
            CharArray(size).apply {
                fill('0', 2 - e, size)
                fillWithDigits(f, 2 - e - n, 2 - e)
                fill('0', 0, 2 - e - n)
                this[1] = '.'
            }
        }
        return String(mantissa)
    }

    private fun round(pp: Long) {
        /*
         * Let d = f 10^e, and let p shorten pp.
         * This method rounds d to the p most significant digits.
         * It does so by possibly modifying f, e and n.
         * When f becomes 0, e and n are normalized to 0 and 1, resp.
         *
         * For any real x let
         *      r(x) = floor(x + 1/2)
         * which is rounding to the closest integer, with ties rounded toward
         * positive infinity.
         *
         * When f = 0 there's not much to say, except that this holds iff n = 0.
         *
         * Otherwise, since
         *      10^{n-1} <= f < 10^n
         * it follows that
         *      10^{e+n-1} <= d < 10^{e+n}
         * To round d to the most significant p digits, first scale d to the
         * range [10^{p-1}, 10^p), cutoff the fractional digits by applying r,
         * and finally scale back.
         * To this end, first define
         *      ds = d 10^{p-e-n}
         * which ensures
         *      10^{p-1} <= ds < 10^p
         *
         * Now, if p < 0 (that is, if p <= -1) then
         *      ds < 10^p <= 10^{-1} < 1/2
         * so that
         *      r(ds) = 0
         * Thus, rounding d to p < 0 digits leads to 0.
         */
        if (n == 0 || pp < 0) {
            f = 0
            e = 0
            n = 1
            return
        }

        /*
         * Further, if p >= n then
         *      ds = f 10^e 10^{p-e-n} = f 10^{p-n}
         * which shows that ds is an integer, so r(ds) = ds. That is,
         * rounding to p >= n digits leads to a result equal to d.
         */
        if (pp >= n) {  // no rounding needed
            return
        }

        /*
         * Finally, 0 <= p < n. When p = 0 it follows that
         *      10^{-1} <= ds < 1
         *      0 <= f' = r(ds) <= 1
         * that is, f' is either 0 or 1.
         *
         * Otherwise,
         *      10^{p-1} <= ds < 10^p
         *      1 <= 10^{p-1} <= f' = r(ds) <= 10^p
         * Note that f' = 10^p is a possible outcome.
         *
         * Scale back, where e' = e + n - p
         *      d' = f' 10^{e+n-p} = f' 10^e', with 10^{e+n-1} <= d' <= 10^{e+n}
         *
         * Since n > p, f' can be computed in integer arithmetic as follows,
         * where / denotes division in the real numbers:
         *      f' = r(ds) = r(f 10^{p-n}) = r(f / 10^{n-p})
         *          = floor(f / 10^{n-p} + 1/2)
         *          = floor((f + 10^{n-p}/2) / 10^{n-p})
         */
        val p = pp.toInt() // 0 <= pp < n, safe cast
        e += n - p // new e is well inside the int range
        val pow10 = pow10(n - p)
        f = (f + (pow10 shr 1)) / pow10 // = round(f / 10^{n-p}) [e is not involved]
        if (p == 0) {
            n = 1
            if (f == 0L) {
                e = 0
            }
            return
        }

        n = p

        if (f == pow10(p)) {
            if (consistentTrailingZeros) {
                // [modified behaviour]
                // This change ensures a result of e.g. "10.0" is kept as-is,
                // rather than being changed into "10".
                n += 1
            } else {
                // [original comment + implementation]
                /*
                 * f is n + 1 digits long.
                 * Absorb one trailing zero into e and reduce f accordingly.
                 */
                f /= 10
                e += 1
            }
        }
    }
}

/*
 * Fills the digits section with indices in [from, to) with the lower
 * to - from digits of x (as chars), while stripping them away from x.
 * Returns the stripped x.
 */
private fun CharArray.fillWithDigits(x: Long, from: Int, to: Int): Long {
    var remaining = x
    var i = to
    while (i > from) {
        val q = remaining / 10
        this[--i] = (remaining - q * 10).toInt().digitToChar()
        remaining = q
    }
    return remaining
}
