dependencies {
    "api"(project(":core"))
    // Tree API only (JsonParser). Smallest maintained zero-dependency JSON library with a stable Java-friendly API.
    "api"(libs.gson)
}
