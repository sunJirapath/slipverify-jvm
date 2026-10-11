plugins {
    java
    application
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(17) }
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(project(":spring-boot-starter"))
    implementation(project(":provider-easyslip"))
    implementation(libs.spring.boot.starter.web)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.promptparse)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application { mainClass = "io.github.sunjirapath.slipverify.examples.spring.DemoApplication" }

tasks.test { useJUnitPlatform() }
