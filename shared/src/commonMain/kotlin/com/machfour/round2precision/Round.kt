package com.machfour.round2precision

fun Float.round(precision: Int): Float {
    if (precision < 0 || isNaN() || isInfinite()) {
        return this
    }
    return toDouble().format(precision).toFloat()
}

fun Double.round(precision: Int): Double {
    if (precision < 0 || isNaN() || isInfinite()) {
        return this
    }
    return format(precision).toDouble()
}

