plugins {
    java
    id("com.gradleup.shadow") version "8.3.5"
}

group = "com.kaerna"
version = "1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")

    implementation("com.google.apis:google-api-services-drive:v3-rev20260428-2.0.0")
    implementation("com.google.auth:google-auth-library-oauth2-http:1.23.0")
    implementation("com.google.http-client:google-http-client-gson:1.44.2")

    testImplementation("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testImplementation("org.mockito:mockito-core:5.12.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.12.0")
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    archiveFileName.set("PaperBackup-GoogleDrive-${project.version}.jar")

    relocate("com.google", "com.kaerna.paperbackup.libs.com.google")
    relocate("com.fasterxml", "com.kaerna.paperbackup.libs.com.fasterxml")
    relocate("org.apache.http", "com.kaerna.paperbackup.libs.org.apache.http")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
