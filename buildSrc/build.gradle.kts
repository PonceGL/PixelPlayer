// Build logic shared by app and wear, kept here so it can have unit tests (the module build scripts cannot).
plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:6.1.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.google.truth:truth:1.4.5")
}

tasks.test {
    useJUnitPlatform()
}
