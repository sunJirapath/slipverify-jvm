import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kover) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.dokka) apply false
    alias(libs.plugins.binary.compatibility.validator)
    alias(libs.plugins.spotless)
}

spotless {
    val ktlintVersion = libs.versions.ktlint.get()
    kotlin {
        target("*/src/**/*.kt")
        ktlint(ktlintVersion)
    }
    kotlinGradle {
        target("*.gradle.kts", "*/*.gradle.kts", "examples/*/*.gradle.kts")
        ktlint(ktlintVersion)
    }
    java {
        target("examples/**/*.java")
        googleJavaFormat(libs.versions.googleJavaFormat.get())
    }
}

apiValidation {
    // Examples consume the library; they are not part of its public API.
    ignoredProjects.addAll(listOf("java", "spring-boot"))
}

// Library modules share one configuration (the equivalent of a convention plugin).
val libraryModules =
    listOf(
        "core",
        "image-zxing",
        "provider-common",
        "provider-easyslip",
        "provider-slipok",
        "store-redis",
        "spring-boot-starter",
    )
val testJdk = providers.gradleProperty("testJdk").orNull?.toInt()

configure(subprojects.filter { it.name in libraryModules }) {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlinx.kover")
    apply(plugin = "com.vanniktech.maven.publish")
    apply(plugin = "io.gitlab.arturbosch.detekt")
    apply(plugin = "org.jetbrains.dokka")

    group = providers.gradleProperty("GROUP").get()
    version = providers.gradleProperty("VERSION_NAME").get()

    extensions.configure<KotlinJvmProjectExtension> {
        jvmToolchain(17)
        explicitApi()
        compilerOptions {
            jvmTarget = if (project.name == "spring-boot-starter") JvmTarget.JVM_17 else JvmTarget.JVM_11
            allWarningsAsErrors = true
            // Fail at compile time on APIs newer than JDK 11, even though the build runs on a newer JDK.
            freeCompilerArgs.add(if (project.name == "spring-boot-starter") "-Xjdk-release=17" else "-Xjdk-release=11")
        }
    }
    tasks.withType<JavaCompile>().configureEach {
        options.release =
            if (project.name == "spring-boot-starter") 17 else 11
    }

    dependencies {
        "testImplementation"(platform(rootProject.libs.junit.bom))
        "testImplementation"(rootProject.libs.junit.jupiter)
        "testImplementation"(rootProject.libs.kotlin.test.junit5)
        "testImplementation"(rootProject.libs.jqwik)
        "testRuntimeOnly"(rootProject.libs.junit.platform.launcher)
    }

    // `-PtestJdk=11|17|21|25` runs the tests on that JDK while the build keeps using JDK 17 bytecode.
    tasks.withType<Test>().configureEach {
        useJUnitPlatform {
            // Live provider tests need real keys; run them with -PincludeLive.
            if (!providers.gradleProperty("includeLive").isPresent) excludeTags("live")
        }
        if (testJdk != null) {
            javaLauncher =
                project.extensions
                    .getByType<JavaToolchainService>()
                    .launcherFor { languageVersion = JavaLanguageVersion.of(testJdk) }
        }
    }

    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        source.setFrom("src/main/kotlin", "src/test/kotlin")
    }

    extensions.configure<kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension> {
        // store-redis.connect() needs a real Redis and is exercised by the live test (CI service container).
        reports { verify { rule { minBound(if (project.name == "store-redis") 60 else 90) } } }
    }
    tasks.named("check") { dependsOn("koverVerify") }

    // Release configuration. Nothing is published from a normal build; see .github/workflows/release.yml.
    extensions.configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
        configure(KotlinJvm(javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationHtml"), sourcesJar = true))
        publishToMavenCentral()
        signAllPublications()
        coordinates(artifactId = "slipverify-${project.name}")
        pom {
            name = "slipverify-${project.name}"
            description = "Verify Thai transfer slips from a base64 image or a slip QR payload."
            inceptionYear = "2026"
            url = "https://github.com/sunJirapath/slipverify-jvm"
            licenses {
                license {
                    name = "The Apache License, Version 2.0"
                    url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                    distribution = "repo"
                }
            }
            developers {
                developer {
                    id = "sunJirapath"
                    name = "Sun"
                    url = "https://github.com/sunJirapath"
                }
            }
            scm {
                url = "https://github.com/sunJirapath/slipverify-jvm"
                connection = "scm:git:git://github.com/sunJirapath/slipverify-jvm.git"
                developerConnection = "scm:git:ssh://git@github.com/sunJirapath/slipverify-jvm.git"
            }
        }
    }
}
