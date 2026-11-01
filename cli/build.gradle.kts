plugins {
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-serial"))
}

application {
    applicationName = "itemsync-cli"
    mainClass.set("github.gtouming.itemsync.cli.ItemSyncCli")
}

dependencies {
    implementation(project(":core-sync"))
    implementation(project(":core-store"))
    implementation(project(":core-transport"))
    runtimeOnly(libs.sqlite.jdbc)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
