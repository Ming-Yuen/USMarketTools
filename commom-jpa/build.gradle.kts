plugins {
	java
	id("io.spring.dependency-management") version "1.1.7"
	`maven-publish`
}

group = "com.common"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
	withSourcesJar()
	withJavadocJar()
}

repositories {
	mavenLocal()
	mavenCentral()
}

dependencyManagement {
	imports {
		mavenBom("org.springframework.boot:spring-boot-dependencies:3.4.5")
	}
}

dependencies {
	// Spring Boot核心（無Web容器）
	implementation("org.springframework.boot:spring-boot-starter") {
		exclude(group = "org.springframework.boot", module = "spring-boot-starter-logging")
	}
	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("org.springframework.boot:spring-boot-autoconfigure")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-batch")

	implementation("com.common:common-lib:0.0.1-SNAPSHOT")

	implementation("org.springframework.boot:spring-boot-starter-cache")

	// Lombok
	compileOnly("org.projectlombok:lombok")
	annotationProcessor("org.projectlombok:lombok")
	testCompileOnly("org.projectlombok:lombok")
	testAnnotationProcessor("org.projectlombok:lombok")

	// MapStruct
	implementation("org.mapstruct:mapstruct:1.6.3")
	annotationProcessor("org.mapstruct:mapstruct-processor:1.6.3")
	// Lombok + MapStruct 綁定，避免註解處理器衝突
	annotationProcessor("org.projectlombok:lombok-mapstruct-binding:0.2.0")

	implementation("org.springframework.boot:spring-boot-starter-data-jpa")

	// 測試依賴
	testImplementation("org.springframework.boot:spring-boot-starter-test") {
		exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
	}
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// MapStruct配置
tasks.withType<JavaCompile> {
	options.compilerArgs.addAll(listOf(
		"-Amapstruct.defaultComponentModel=spring",
		"-Amapstruct.unmappedTargetPolicy=IGNORE",
		"-parameters"
	))
}

// 發布配置
publishing {
	publications {
		create<MavenPublication>("maven") {
			from(components["java"])

			pom {
				name.set("Common Library")
				description.set("Spring Boot Common Library")

				developers {
					developer {
						id.set("dev")
						name.set("Developer")
					}
				}
			}
		}
	}
}

// Javadoc配置
tasks.javadoc {
	if (JavaVersion.current().isJava9Compatible) {
		(options as StandardJavadocDocletOptions).addBooleanOption("html5", true)
	}
}

tasks.test {
	useJUnitPlatform()
}
