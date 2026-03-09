plugins {
    id("org.springframework.boot") version "4.0.3" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    id("jacoco-report-aggregation")
}

val springBootVersion = "4.0.3"
val caffeineVersion = "3.1.8"
val grpcVersion = "1.68.1"
val protobufVersion = "4.29.3"
val jacksonVersion = "2.18.2"
val assertjVersion = "3.27.3"
val mockWebServerVersion = "4.12.0"

allprojects {
    group = property("group") as String
    version = property("version") as String

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "io.spring.dependency-management")
    apply(plugin = "jacoco")

    the<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension>().apply {
        imports {
            mavenBom("org.springframework.boot:spring-boot-dependencies:$springBootVersion")
        }
    }

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    }

    dependencies {
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testImplementation"("org.assertj:assertj-core:$assertjVersion")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        finalizedBy(tasks.named("jacocoTestReport"))
    }

    tasks.withType<JacocoReport> {
        reports {
            xml.required.set(true)
            html.required.set(true)
        }
    }

    tasks.withType<JacocoCoverageVerification> {
        violationRules {
            rule {
                limit {
                    minimum = "0.40".toBigDecimal()
                }
            }
        }
    }

    tasks.named("check") {
        dependsOn(tasks.withType<JacocoCoverageVerification>())
    }

    // Wire subproject into aggregated JaCoCo report
    rootProject.dependencies {
        "jacocoAggregation"(project(path))
    }

    // Make dependency version constants available to subproject build files
    extra["caffeineVersion"] = caffeineVersion
    extra["grpcVersion"] = grpcVersion
    extra["protobufVersion"] = protobufVersion
    extra["jacksonVersion"] = jacksonVersion
    extra["assertjVersion"] = assertjVersion
    extra["mockWebServerVersion"] = mockWebServerVersion
}
