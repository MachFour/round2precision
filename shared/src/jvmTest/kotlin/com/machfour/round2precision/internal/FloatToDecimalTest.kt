package com.machfour.round2precision.internal

import com.machfour.round2precision.internal.checkers.FloatToDecimalChecker
import kotlin.test.Test

private const val RANDOM_COUNT = 100000

class FloatToDecimalTest {

    @Test
    fun test() {
        //val testType = "all"
        //val testType = "positive"
        val testType = null

        val count = RANDOM_COUNT // can replace with any desired value

        when (testType) {
            "all" -> FloatToDecimalChecker.testAll()
            "positive" -> FloatToDecimalChecker.testPositive()
            else -> FloatToDecimalChecker.test(count, RandomFactory.random)
        }
    }
}