import org.jetbrains.kotlin.kapt3.base.Kapt.kapt

plugins {
	kotlin("jvm") version "1.8.20"
	kotlin("plugin.serialization") version "1.8.20"
}

group = "com.github.mnemotechnician"

repositories {
	mavenCentral()
	maven("https://oss.sonatype.org/content/repositories/snapshots")
	maven("https://s01.oss.sonatype.org/content/repositories/snapshots")
}

dependencies {
	implementation("com.kotlindiscord.kord.extensions", "kord-extensions", "1.5.6-SNAPSHOT")

	implementation("dev.kord", "kord-core", "0.8.0")

	implementation("org.jetbrains.kotlinx", "kotlinx-coroutines-core", "1.6.4")
	implementation("org.jetbrains.kotlinx", "kotlinx-serialization-json", "1.3.2")

	implementation("org.slf4j", "slf4j-simple", "2.0.7") // FUCK SLF4J!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
}

tasks.test {
	useJUnitPlatform()
}

tasks.jar {
	duplicatesStrategy = DuplicatesStrategy.EXCLUDE

	manifest {
		attributes(
			"Main-Class" to "com.github.mnemotechnician.kindergarten.AppKt"
		)
	}

	from(configurations.runtimeClasspath.get().files.map { if (it.isDirectory) it else zipTree(it) })
}
