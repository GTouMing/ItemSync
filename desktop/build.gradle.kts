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
    applicationName = "ItemSync"
    mainClass.set("github.gtouming.itemsync.desktop.DesktopLauncher")
}

val javafxVersion = "21.0.13"

dependencies {
    implementation(project(":core-sync"))
    implementation(project(":core-store"))
    implementation(project(":core-transport"))
    implementation(project(":platform-desktop"))
    runtimeOnly(libs.sqlite.jdbc)

    implementation("org.openjfx:javafx-base:$javafxVersion:win")
    implementation("org.openjfx:javafx-graphics:$javafxVersion:win")
    implementation("org.openjfx:javafx-controls:$javafxVersion:win")

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

val packageApp by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Builds a Windows app image with jpackage"
    dependsOn(tasks.installDist)

    val appVersion = project.version.toString().substringBefore('-')
    val mainJar = "desktop-${project.version}.jar"
    val inputDir = layout.buildDirectory.dir("install/ItemSync/lib")
    val destDir = layout.buildDirectory.dir("jpackage")

    doFirst {
        destDir.get().asFile.deleteRecursively()
    }
    commandLine(
        "${System.getProperty("java.home")}/bin/jpackage",
        "--type", "app-image",
        "--name", "ItemSync",
        "--app-version", appVersion,
        "--vendor", "ItemSync",
        "--input", inputDir.get().asFile.absolutePath,
        "--dest", destDir.get().asFile.absolutePath,
        "--main-jar", mainJar,
        "--main-class", "github.gtouming.itemsync.desktop.DesktopLauncher",
        "--java-options", "-Dfile.encoding=UTF-8"
    )
    doLast {
        logger.lifecycle(
            "App image: ${destDir.get().asFile.resolve("ItemSync/ItemSync.exe")}"
        )
    }
}
