package com.machfour.round2precision

import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals

class FormatKtTest {

    @Test
    fun testFormatDirectly() {
        assertEquals("0", 0.0.format(0))
        assertEquals("0.0", 0.0.format(1))
        assertEquals("0.00", 0.0.format(2))
        assertEquals("1", 1.0.format(0))
        assertEquals("1.0", 1.0.format(1))
        assertEquals("1.00", 1.0.format(2))
        assertEquals("123123123123.00", 123123123123.0.format(2))
        assertEquals("9007199254740992.000000000", 9007199254740992.0.format(9))
        assertEquals("9007199254740998.00000000000000000000", 9007199254740998.0.format(20))
        assertEquals("2305843009213694000.000000", 2305843009213694000.0.format(6))
        assertEquals("1${"0".repeat(300)}.${"0".repeat(1000)}", 1.0e300.format(1000))

        // When the input value is not an integer but the
        // rounded value is, print 'prec' trailing zeros.
        assertEquals("0.50", 0.5.format(2))
        assertEquals("1.50", 1.5.format(2))

        assertEquals("0", 0.01.format(0))
        assertEquals("1", 1.01.format(0))
        assertEquals("0.0", 0.01.format(1))
        assertEquals("1.0", 1.01.format(1))
        assertEquals("-0.0", (-0.01).format(1))
        assertEquals("-1.0", (-1.01).format(1))

        assertEquals("0.00", 0.001.format(2))
        assertEquals("1.00", 1.001.format(2))
        assertEquals("0.00", 0.0001.format(2))
        assertEquals("1.00", 1.0001.format(2))
        assertEquals("0.00", 0.00001.format(2))
        assertEquals("1.00", 1.00001.format(2))
        assertEquals("0.00000", 0.00000001.format(5))
        assertEquals("1.00000", 1.00000001.format(5))

        assertEquals("0.100", 0.10.format(3))
        assertEquals("0.01", 0.01.format(2))
        assertEquals("0.010", 0.01.format(3))
        assertEquals("0.001", 0.001.format(3))
        assertEquals("0.0010", 0.001.format(4))
        assertEquals("0.0001", 0.0001.format(4))
        assertEquals("0.00010", 0.0001.format(5))

        assertEquals("1.111", 1.111.format(3))
        assertEquals("1.111", 1.111.format(3))
        assertEquals("1.123", 1.1233344.format(3))
        assertEquals("1.123", 1.1233344.format(3))
        assertEquals("1.124", 1.1235.format(3))
        assertEquals("1.124", 1.1235.format(3))

        assertEquals("0.75", 0.749.format(2))

        assertEquals("10", 9.9.format(0))
        assertEquals("10", 9.99.format(0))
        assertEquals("10", 10.001.format(0))
        assertEquals("9.9", 9.9.format(1))
        assertEquals("10.0", 9.99.format(1))
        assertEquals("10.0", 9.9999.format(1))
        assertEquals("10.0", 10.001.format(1))
        assertEquals("10.00", 10.001.format(2))
        assertEquals("10.001", 10.001.format(3))

        assertEquals("10000000000000.0", 9999999999999.99.format(1))
        assertEquals("9223372036854.770", 9223372036854.77.format(3))
        assertEquals("-9223372036854.770", (-9223372036854.77).format(3))

        // Difficult test case for naive implementations (e.g. my old one)
        assertEquals("295.34", 295.3350.format(2))
    }

    private fun testAgainstStringFormat(f: Int, e: Int, prec: Int) {
        val d = f.toDouble()*10.0.pow(e)
        val expected = "%.${prec}f".format(d)
        val actual = d.format(prec)
        assertEquals(
            expected = expected,
            actual = actual,
            message = "f = $f, e = $e, d = $d, prec = $prec"
        )
    }

    @Test
    fun testAgainstStringFormat() {
        val minPrec = 0
        val maxPrec = 40
        val startInt = -1
        val endInt = 1000
        for (f in startInt..endInt) {
            for (e in -40..10) {
                for (prec in minPrec..maxPrec) {
                    testAgainstStringFormat(f, e, prec)
                }
            }
        }
    }
}
