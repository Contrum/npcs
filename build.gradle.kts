/*
 * This file is part of npc-lib, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2022-2023 Julian M., Pasqual K. and contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

import com.diffplug.gradle.spotless.SpotlessExtension
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
  alias(libs.plugins.spotless)
  alias(libs.plugins.shadow) apply false
}

defaultTasks("build", "shadowJar")

allprojects {
  version = "3.0.0-SNAPSHOT"
  group = "io.github.juliarn"
  description = "Abstract NPC-Library for Minecraft 1.8+ Servers"

  repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://s01.oss.sonatype.org/content/repositories/snapshots/")

    // more stable replacement for jitpack
    maven("https://repository.derklaro.dev/releases/") {
      mavenContent {
        releasesOnly()
      }
    }
    maven("https://repository.derklaro.dev/snapshots/") {
      mavenContent {
        snapshotsOnly()
      }
    }

    // packetevents
    maven("https://repo.codemc.io/repository/maven-releases/") {
      mavenContent {
        includeGroup("com.github.retrooper")
      }
    }
  }
}

subprojects {
  // apply all plugins only to subprojects
  apply(plugin = "java-library")
  apply(plugin = "maven-publish")
  apply(plugin = "com.diffplug.spotless")
  apply(plugin = "io.github.goooler.shadow")

  dependencies {
    "compileOnly"(rootProject.libs.annotations)
    "compileOnly"("org.projectlombok:lombok:1.18.38")
    "annotationProcessor"("org.projectlombok:lombok:1.18.38")
  }

  configurations.all {
    // unsure why but every project loves them, and they literally have an import for every letter I type - beware
    exclude("org.checkerframework", "checker-qual")
  }

  tasks.withType<Jar> {
    from(rootProject.file("license.txt"))
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
  }

  tasks.withType<ShadowJar> {
    archiveClassifier.set(null as String?)
    dependencies {
      // excludes the META-INF directory, module infos & html files of all dependencies
      // this includes for example maven lib files & multi-release module-json files
      exclude("META-INF/**", "**/*.html", "module-info.*")
    }
  }

  tasks.withType<JavaCompile>().configureEach {
    // options
    options.release.set(21)
    options.encoding = "UTF-8"
    options.isIncremental = true
    // we are aware that those are there, but we only do that if there is no other way we can use - so please keep the terminal clean!
    options.compilerArgs = mutableListOf("-Xlint:-deprecation,-unchecked")
  }

  extensions.configure<JavaPluginExtension> {
    disableAutoTargetJvm()
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
  }

  extensions.configure<SpotlessExtension> {
    java {
      licenseHeaderFile(rootProject.file("license_header.txt"))
    }
  }

  tasks.withType<Javadoc> {
    val options = options as? StandardJavadocDocletOptions ?: return@withType

    // options
    options.encoding = "UTF-8"
    options.memberLevel = JavadocMemberLevel.PRIVATE
    options.addStringOption("-html5")
    options.addBooleanOption("Xdoclint:-missing", true)
  }

  tasks.register<org.gradle.jvm.tasks.Jar>("javadocJar") {
    archiveClassifier.set("javadoc")
    from(tasks.getByName("javadoc"))
  }

  tasks.register<org.gradle.jvm.tasks.Jar>("sourcesJar") {
    archiveClassifier.set("sources")
    from(project.the<SourceSetContainer>()["main"].allJava)
  }

  extensions.configure<PublishingExtension> {
    publications {
      create<MavenPublication>("mavenJava") {
        from(components["java"])
      }
    }
    repositories {
      // Publish to local maven repository for IntelliJ recognition
      mavenLocal()

      maven {
        name = "contrum-repo"
        url = uri("https://repo.contrum.org/private/")
        credentials {
          username = project.findProperty("contrumLibrariesUsername") as String? ?: ""
          password = project.findProperty("contrumLibrariesPassword") as String? ?: ""
        }
      }
    }
  }
}
