plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
}

dependencies {
    implementation(project(":modules:player"))
    implementation(project(":modules:sport"))
    implementation("org.jetbrains.kotlin:kotlin-reflect")
}
