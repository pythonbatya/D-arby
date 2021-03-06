plugins {
    id("com.google.cloud.tools.jib") version "3.1.4"
    id("java")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

group = "com.example"
version = "0.0.1-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webflux:2.5.6")
    implementation("org.springframework.boot:spring-boot-starter-data-r2dbc:2.5.6")
    implementation("io.r2dbc:r2dbc-h2:0.8.4.RELEASE")

    // slack on bolt dependencies
    implementation("com.slack.api:bolt:1.14.0")
    implementation("com.slack.api:bolt-socket-mode:1.14.0")
    implementation("javax.websocket:javax.websocket-api:1.1")
    implementation("org.glassfish.tyrus.bundles:tyrus-standalone-client:1.17")

    // test
    testImplementation("org.springframework.boot:spring-boot-starter-test:2.5.6")
    testImplementation("io.projectreactor:reactor-test:3.4.12")
}

tasks.getByName<Test>("test") {
    useJUnitPlatform()
}

jib {
    from.image = "eclipse-temurin:17" // -alpine
    to.image = "registry.pyn.ru/darby"
    to.tags = setOf(project.version.toString(), "latest")
    container {
        ports = listOf("8082")
        jvmFlags = listOf("-Dspring.config.additional-location=/etc/myapp/application-prod.yml")
    }
}
