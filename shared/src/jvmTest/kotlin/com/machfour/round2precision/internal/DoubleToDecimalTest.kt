package com.machfour.round2precision.internal

import com.machfour.round2precision.internal.checkers.DoubleToDecimalChecker
import kotlin.test.Test

private const val RANDOM_COUNT = 100000

class DoubleToDecimalModTest {

    @Test
    fun test() {
        val count = RANDOM_COUNT // can replace with arbitrary value

        DoubleToDecimalChecker.test(count, RandomFactory.random)
    }
}
