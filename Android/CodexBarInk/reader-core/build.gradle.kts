plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("com.google.code.gson:gson:2.13.2")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
}

sourceSets {
    test {
        resources.srcDir("../../../docs/fixtures")
    }
}

tasks.test {
    useJUnitPlatform()
}
