plugins {
    java
    application
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(17) }
}

tasks.withType<JavaCompile>().configureEach { options.release = 11 }

dependencies {
    implementation(project(":core"))
    implementation(project(":image-zxing"))
    implementation(project(":provider-easyslip"))
    implementation(libs.promptparse)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application { mainClass = "io.github.sunjirapath.slipverify.examples.PlainJavaExample" }

tasks.test { useJUnitPlatform() }
