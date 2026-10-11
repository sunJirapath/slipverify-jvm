dependencies {
    "api"(project(":core"))
    "implementation"(project(":provider-common"))
    "testImplementation"(libs.wiremock)
    "testImplementation"(libs.promptparse)
}
