plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "org.agty"
version = "0.10.2"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        val localIde = providers.gradleProperty("localIdePath")
        if (localIde.isPresent) local(localIde.get()) else intellijIdea("2025.3")
        javaCompiler("253.28294.334")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Bundled)
    }
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.commonmark:commonmark:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.24.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    // IntelliJ's test bootstrap also loads JUnit 4 interfaces.
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
tasks.test {
    useJUnitPlatform()
    systemProperty("fixture.classes", sourceSets["test"].output.classesDirs.asPath)
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion { sinceBuild = "253"; untilBuild = "253.*" }
    }
}
