import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

// 字节码目标保持 17。不把工具链钉死在 JDK 17，这样本机只有 JDK 21 时也能构建。
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = "17"
}

dependencies {
    api("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

application {
    mainClass.set("io.github.icycat.edgetts.protocol.SmokeTestKt")
}

tasks.test {
    useJUnit()
    testLogging { events("passed", "failed", "skipped"); showStandardStreams = false }
}

// 真实网络冒烟测试: ./gradlew :edge-protocol:smoke
tasks.register<JavaExec>("smoke") {
    group = "verification"
    description = "Open the real Edge Read Aloud WebSocket and synthesize a short Chinese sentence."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.github.icycat.edgetts.protocol.SmokeTestKt")
    args(project.findProperty("smokeOut")?.toString() ?: "${layout.buildDirectory.get()}/smoke.mp3")
}
