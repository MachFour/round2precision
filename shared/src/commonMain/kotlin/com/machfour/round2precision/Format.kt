package com.machfour.round2precision

import com.machfour.round2precision.internal.DoubleToDecimal
import com.machfour.round2precision.internal.FormattedFPDecimal
import kotlin.math.absoluteValue
import kotlin.system.exitProcess

fun Double.format(precision: Int, exactPrecision: Boolean = true): String {
    if (precision < 0 || isNaN() || isInfinite()) {
        return toString()
    }

    val fp = FormattedFPDecimal(consistentTrailingZeros = exactPrecision)
    DoubleToDecimal.split(absoluteValue, fp)
    val s = fp.plain(precision)
    return if (this < 0) "-$s" else s
}