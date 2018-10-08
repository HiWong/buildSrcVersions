package jmfayard.github.io

import com.squareup.kotlinpoet.*
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.util.*

open class SyncLibs : DefaultTask() {

    init {
        description = "Update buildSrc/src/main/java/Libs.kt"
        group = "build"
    }

    var jsonInputPath = "build/dependencyUpdates/report.json"


    val outputDir = project.file("buildSrc/src/main/java")


    @get:InputFile
    val jsonInput
        get() = project.file(jsonInputPath)
    @get:OutputFile
    val outputFile
        get() = outputDir.resolve("$LibsClassName.kt")


    @TaskAction
    fun taskAction() {
        val fileExisted = outputFile.canRead()

        println(helpMessageBefore())

        createBasicStructureIfNeeded()


        val json = jsonInput.readText()
        val moshiAdapter: JsonAdapter<DependencyGraph> = Moshi.Builder().build().adapter(DependencyGraph::class.java)
        val dependencyGraph: DependencyGraph = moshiAdapter.fromJson(json)!!
        val dependencies: List<Dependency> = parseGraph(dependencyGraph)

        val kotlinPoetry : KotlinPoetry = kotlinpoet(dependencies, dependencyGraph.gradle)
        kotlinPoetry.Libs.writeTo(outputDir)
        kotlinPoetry.Versions.writeTo(outputDir)

        println(helpMessageAfter(fileExisted, dependencies))
    }

    fun helpMessageBefore(): String = """
          Done running $ ./gradlew dependencyUpdates   # com.github.ben-manes:gradle-versions-plugin
          Reading info about your dependencies from ${jsonInput.absolutePath}
          """.trimIndent()

    fun helpMessageAfter(fileExisted: Boolean, dependencies: List<Dependency>): String {
        val createdOrupdated = if (fileExisted) "Updated file" else "Created file"
        val path = outputFile.absolutePath
        val someDependency = random(dependencies)?.escapedName ?: "xxx"

        return """
            $createdOrupdated $path

            It contains meta-data about all your dependencies, including available updates and links to the website

            Its content is available in all your build.gradle and build.gradle.kts

            // build.gradle or build.gradle.kts
            dependencies {
               Libs.$someDependency
            }

            Run again the task any time you add a dependency or want to check for updates
               $ ./gradlew syncLibs

            """.trimIndent()
    }

    fun parseGraph(graph: DependencyGraph): List<Dependency> {
        val versions = graph.current + graph.exceeded + graph.outdated

        val map = mutableMapOf<String, Dependency>()
        for (v in versions) {
            val key = escapeName(v.name)
            val fdqnName = escapeName("${v.group}_${v.name}")

            if (key in MEANING_LESS_NAMES) {
                v.escapedName = fdqnName
            } else if (map.containsKey(key)) {
                v.escapedName = fdqnName
                map[key]!!.escapedName = fdqnName
            } else {
                map[key] = v
                v.escapedName = key
            }
        }
        return versions
            .distinctBy { it.escapedName }
            .sortedBy { it.escapedName }

    }


    @Suppress("LocalVariableName")
    fun kotlinpoet(versions: List<Dependency>, gradleConfig: GradleConfig): KotlinPoetry {

        val versionsProperties: List<PropertySpec> = versions.map { d: Dependency ->
            constStringProperty(
                name = d.escapedName,
                initializer = CodeBlock.of("%S %L", d.version, d.versionInformation())
            )
        }
        val libsProperties: List<PropertySpec> = versions.map { d ->
            constStringProperty(
                name = d.escapedName,
                initializer = CodeBlock.of("%S + Versions.%L", "${d.group}:${d.name}:", d.escapedName),
                kdoc = d.projectUrl?.let { url ->
                    CodeBlock.of("[%L website](%L)", d.name, url)
                }
            )
        }

        val gradleProperties: List<PropertySpec> = listOf(
            constStringProperty("runningVersion", gradleConfig.running.version),
            constStringProperty("currentVersion", gradleConfig.current.version),
            constStringProperty("nightlyVersion", gradleConfig.nightly.version),
            constStringProperty("releaseCandidate", gradleConfig.releaseCandidate.version)
        )

        val Gradle: TypeSpec = TypeSpec.objectBuilder("Gradle")
            .addProperties(gradleProperties)
            .build()

        val Versions: TypeSpec = TypeSpec.objectBuilder("Versions")
            .addKdoc(GENERATED_BY_SYNCLIBS)
            .addType(Gradle).addProperties(versionsProperties)
            .build()


        val Libs = TypeSpec.objectBuilder("Libs")
            .addKdoc(GENERATED_BY_SYNCLIBS)
            .addProperties(libsProperties)
            .build()


        val LibsFile = FileSpec.builder("", LibsClassName)
            .addType(Libs)
            .build()

        val VersionsFile = FileSpec.builder("", VersionsClassName)
            .addType(Versions)
            .build()

        return KotlinPoetry(Libs = LibsFile, Versions = VersionsFile)

    }

    fun createBasicStructureIfNeeded() {
        val folder = project.file("buildSrc/main/java")
        if (folder.isDirectory.not()) {
            folder.mkdirs()
        }
        val buildSrc = project.file("buildSrc/build.gradle.kts")
        if (buildSrc.exists().not()) {
            buildSrc.writeText(INITIAL_BUILD_GRADLE_KTS)
        }
    }


    companion object {

        var LibsClassName = "Libs"
        var VersionsClassName = "Versions"

        val random = Random()

        fun random(deps: List<Dependency>) : Dependency? {
            val index = random.nextInt(deps.size)
            return if (deps.isEmpty()) null else deps[index]
        }

        /**
         * We don't want to use meaningless generic names like Libs.core
         *
         * Found many examples of bad names here https://developer.android.com/jetpack/androidx/migrate
         * **/
        val MEANING_LESS_NAMES = listOf(
            "common", "core", "core-testing", "testing", "runtime", "extensions",
            "compiler", "migration", "db", "rules", "runner", "monitor", "loader",
            "media", "print", "io", "media", "collection"
        )

        val GENERATED_BY_SYNCLIBS = """
                  Generated by [gradle-kotlin-dsl-libs](https://github.com/jmfayard/gradle-kotlin-dsl-libs)

                  Run again
                    `$ ./gradlew syncLibs`
                  to update this file
                  """.trimIndent()


        const val INITIAL_BUILD_GRADLE_KTS = """
plugins {
    `kotlin-dsl`
}
repositories {
    jcenter()
}
        """

        fun constStringProperty(name: String, initializer: CodeBlock, kdoc: CodeBlock? = null) =
            PropertySpec.builder(name, String::class)
                .addModifiers(KModifier.CONST)
                .initializer(initializer)
                .apply {
                    if (kdoc != null) addKdoc(kdoc)
                }.build()

        fun constStringProperty(name: String, initializer: String, kdoc: CodeBlock? = null) =
            constStringProperty(name, CodeBlock.of("%S", initializer))

        fun escapeName(name: String): String {
            val escapedChars = listOf('-', '.', ':')
            return buildString {
                for (c in name) {
                    append(if (c in escapedChars) '_' else c.toLowerCase())
                }
            }
        }

        fun Dependency.versionInformation(): String = when {
            latest.isNullOrBlank().not() -> "// exceed the version found: $latest"
            reason != null && reason.isNotBlank() -> {
                val shorterReason = reason.lines().take(4).joinToString(separator = "\n")
                "\n/* error: $shorterReason \n.... */"
            }
            available != null -> available.displayComment()
            else -> "// up-to-date"
        }

        fun AvailableDependency.displayComment(): String = when {
            release.isNullOrBlank().not() -> "// available: release=$release"
            milestone.isNullOrBlank().not() -> "// available: milestone=$milestone"
            integration.isNullOrBlank().not() -> "// available: integration=$integration"
            else -> "// " + this.toString()
        }

    }

}


