plugins {
    alias(libs.plugins.kotlinMultiplatform)
}
dependencies {
}

kotlin {

    jvm {
        withJava()
    }

    sourceSets {
        commonMain.dependencies {
            // put your Multiplatform dependencies here
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}