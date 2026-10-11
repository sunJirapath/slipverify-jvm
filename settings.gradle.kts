pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Build-time only: lets Gradle download the JDK 17 toolchain if it is not installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "slipverify-jvm"

include(
    ":core",
    ":image-zxing",
    ":provider-common",
    ":provider-easyslip",
    ":provider-slipok",
    ":store-redis",
    ":spring-boot-starter",
)
