package com.machfour.round2precision.internal

import java.util.Random


/**
 * Factory class which generates and prints to STDOUT a long-valued seed
 * for use in initializing a PRNG.  An instance of `Random` or
 * `SplittableRandom` may likewise be obtained.
 */
object RandomFactory {
    private val randomSeed: Long
        /**
         * Obtain a seed from an independent PRNG.
         *
         * @return A random seed.
         */
        get() = Random().nextLong()

    val seed: Long
        /**
         * Obtain and print to STDOUT a seed appropriate for initializing a PRNG.
         * If the system property "seed" is set and has value which may be correctly
         * parsed it is used, otherwise a seed is generated using an independent
         * PRNG.
         *
         * @return The seed.
         */
        get() = randomSeed.also { println("Seed from RandomFactory = ${it}L") }

    val random: Random
        /**
         * Obtain and print to STDOUT a seed and use it to initialize a new
         * `Random` instance which is returned. If the system
         * property "seed" is set and has value which may be correctly parsed it
         * is used, otherwise a seed is generated using an independent PRNG.
         *
         * @return The `Random` instance.
         */
        get() = Random(seed)
}

