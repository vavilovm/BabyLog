plugins {
    id("com.diffplug.spotless") version "7.2.1"
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.25" apply false
    id("app.cash.paparazzi") version "1.3.5" apply false
    id("com.google.gms.google-services") version "4.5.0" apply false
}

spotless {
    kotlin {
        target("app/src/**/*.kt")
        ktfmt().kotlinlangStyle()
    }
    kotlinGradle {
        target("*.gradle.kts", "app/*.gradle.kts")
        ktfmt().kotlinlangStyle()
    }
}
