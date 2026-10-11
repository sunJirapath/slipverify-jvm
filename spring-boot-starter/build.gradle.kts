dependencies {
    "api"(project(":core"))
    "api"(project(":image-zxing"))
    "api"(platform(libs.spring.boot.bom))
    "api"(libs.spring.boot.autoconfigure)

    // Optional integrations: present on the classpath only if the application adds them.
    "compileOnly"(project(":provider-easyslip"))
    "compileOnly"(project(":provider-slipok"))
    "compileOnly"(project(":store-redis"))
    "compileOnly"(libs.micrometer.core)
    "compileOnly"(libs.spring.boot.starter.web)

    "testImplementation"(project(":provider-easyslip"))
    "testImplementation"(project(":provider-slipok"))
    "testImplementation"(project(":store-redis"))
    "testImplementation"(libs.micrometer.core)
    "testImplementation"(libs.spring.boot.starter.web)
    "testImplementation"(libs.spring.boot.starter.test)
    "testImplementation"(libs.wiremock)
    "testImplementation"(libs.zxing.core)
    "testImplementation"(libs.zxing.javase)
    "testImplementation"(libs.promptparse)
}
