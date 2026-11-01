plugins { kotlin("jvm") }

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":core"))
    implementation(project(":llm"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    workingDir = rootProject.projectDir
}
