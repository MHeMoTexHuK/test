plugins {
	kotlin("jvm") version "1.6.20"
	kotlin("plugin.serialization") version "1.6.10"
	application
}

repositories {
	mavenCentral()
	maven("https://jitpack.io")
}

val kotlinVersion = "1.6.20"
val kordVersion = "0.8.0-M13"
val ktorVersion = "2.0.0"

dependencies {
	// kord includes it
	// implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.0")
	implementation("org.jetbrains.kotlin:kotlin-reflect:$kotlinVersion")
	
	implementation("org.jetbrains.kotlin:kotlin-script-runtime:1.6.10")
	implementation("org.jetbrains.kotlin:kotlin-script-util:1.6.10")
	implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:1.6.10")
	implementation("org.jetbrains.kotlin:kotlin-scripting-compiler-embeddable:1.6.10")
	implementation("org.jetbrains.kotlin:kotlin-scripting-jsr223:1.6.10")

	implementation("dev.kord:kord-core:$kordVersion")
	implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.3.2")

	implementation("io.ktor:ktor-server-core:$ktorVersion")
	implementation("io.ktor:ktor-server-netty:$ktorVersion")
	implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
	implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
	
	// note for myself: DONT REMOVE THIS DEPENDENCY YOU DUMBFUCK!
	implementation("org.sejda.webp-imageio:webp-imageio-sejda:0.1.0")

	implementation("info.debatty:java-string-similarity:2.0.0")

}

tasks.compileKotlin {
	kotlinOptions.apply {
		sourceCompatibility = "11"
		jvmTarget = "11"
	}
}

tasks.jar {
	duplicatesStrategy = DuplicatesStrategy.EXCLUDE
	
	manifest {
		attributes["Main-Class"] = "flarogus.FlarogusKt"
	}
	
	from(*configurations.runtimeClasspath.files.map { if (it.isDirectory()) it else zipTree(it) }.toTypedArray())
}

// todo: useless task
tasks.register<Copy>("deploy") {
	dependsOn("jar")
	
	from("build/libs/app.jar")
	into("../build/")
	
	doLast {
		delete("build/libs/app.jar")
	}
}

// heroku requires this task.
tasks.register("stage") {
	dependsOn("jar")
}

application.apply {
	mainClass.set("flarogus.FlarogusKt")
}

//why
tasks.withType(JavaExec::class.java) {
	standardInput = System.`in`
}
