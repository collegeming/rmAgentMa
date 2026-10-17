import groovy.json.JsonSlurper
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.nio.file.Files
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "org.rmagentma.hostprobe"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        applicationId = "org.rmagentma.hostprobe"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0-probe"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents {
    beforeVariants(selector().withBuildType("release")) { it.enable = false }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

abstract class PrepareRendererAssets : DefaultTask() {
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty
}

val rendererAssetsDir = providers.gradleProperty("RendererAssetsDir")
val generatedAssets = layout.buildDirectory.dir("generated/rendererAssets")
val prepareRendererAssets by tasks.registering(PrepareRendererAssets::class) {
    notCompatibleWithConfigurationCache("Renderer verification copies a fully validated file inventory")
    rendererAssetsDir.orNull?.let { inputs.dir(it) }
    outputDirectory.set(generatedAssets)
    outputs.upToDateWhen { false }
    doLast {
        val source = rendererAssetsDir.orNull?.let(::file)
        require(source != null && source.isDirectory) { "RendererAssetsDir must name the freshly built renderer directory" }
        val manifestFile = source.resolve("asset-manifest.json")
        val metadataFile = source.resolve("renderer-build.json")
        require(manifestFile.isFile && metadataFile.isFile) { "asset-manifest.json and renderer-build.json are required; no empty or stale renderer fallback" }
        fun digest(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        val manifest = JsonSlurper().parse(manifestFile) as Map<*, *>
        val metadata = JsonSlurper().parse(metadataFile) as Map<*, *>
        require(metadata["schemaVersion"] == 1) { "Unknown renderer build schema" }
        require(metadata["nodeEngineCompatible"] == true) { "Renderer Node engine is not approved" }
        val tools = metadata["tools"] as? Map<*, *> ?: error("Missing renderer tool identity")
        val nodeMajor = Regex("v?(\\d+)\\.\\d+\\.\\d+").matchEntire(tools["node"].toString())?.groupValues?.get(1)?.toInt()
        require(nodeMajor != null && nodeMajor in 22..24 && tools["bun"].toString().matches(Regex("\\d+\\.\\d+\\.\\d+"))) { "Unsupported renderer tool identity" }
        val upstream = metadata["upstream"] as? Map<*, *> ?: error("Missing upstream identity")
        require(upstream["commit"] == "6744099b279b991c17e31c243f0920477bd31cb6" && upstream["repository"] == "https://github.com/iOfficeAI/AionUi") { "Unexpected renderer identity" }
        require(upstream["packageSha256"] == "f437f7350dcf27a375c96658dc10a4ef405cc7750f509d7490cbe5f34d290039" && upstream["lockSha256"] == "e3f79c54c0d5b786e1a2689557aabac67696e602cd17e8d35cf236f999e98039") { "Unexpected renderer lock identity" }
        require(manifest["schemaVersion"] == 1 && manifest["algorithm"] == "SHA-256" && manifest["entry"] == "index.html" && metadata["entry"] == "index.html") { "Unexpected renderer manifest" }
        require(metadata["assetManifestSha256"] == digest(manifestFile)) { "Manifest hash mismatch" }
        require(metadata["testOnly"] != true) { "Test fixtures cannot be packaged as the renderer" }
        val checks = metadata["checks"] as? List<*> ?: error("Missing standard renderer check results")
        val commands = mapOf(
            "lint" to listOf("bun", "run", "lint"),
            "format" to listOf("bun", "run", "format:check"),
            "type" to listOf("node_modules/typescript/bin/tsc", "--noEmit"),
            "i18n-types" to listOf("bun", "run", "i18n:types"),
            "i18n" to listOf("scripts/check-i18n.js"),
            "unit" to listOf("bun", "run", "test", "--project", "node", "--project", "dom"),
            "package" to listOf("bun", "run", "package"),
        )
        val labels = checks.map { check ->
            val result = check as Map<*, *>
            val label = result["label"] as? String ?: error("Missing check label")
            require(result["exitCode"] == 0 && result["signal"] == null) { "Renderer standard check failed" }
            val command = result["command"] as? List<*> ?: error("Missing check command")
            require((if (label in setOf("type", "i18n")) command.drop(1) else command) == commands[label]) { "Unexpected renderer check command: $label" }
            label
        }.toSet()
        require(checks.size == commands.size && labels == commands.keys) { "Incomplete standard renderer checks" }
        val rendererRoot = rootProject.projectDir.parentFile.resolve("renderer")
        for (kind in listOf("patches", "overlay", "scripts")) {
            val key = if (kind == "scripts") "buildScripts" else kind
            val records = metadata[key] as? List<*> ?: error("Missing renderer $key identity")
            val actualInputs = rendererRoot.resolve(kind).walkTopDown().filter { it.isFile }.associate { it.relativeTo(rendererRoot.resolve(kind)).invariantSeparatorsPath to digest(it) }
            val expectedInputs = records.associate { record ->
                val item = record as Map<*, *>
                item["path"] to item["sha256"]
            }
            require(actualInputs.isNotEmpty() && records.size == expectedInputs.size && actualInputs == expectedInputs) { "Renderer $key changed since build" }
        }
        require(metadata["assetPolicySha256"] == digest(rendererRoot.resolve("assets/policy.json"))) { "Renderer asset policy changed since build" }
        val pin = JsonSlurper().parse(rendererRoot.resolve("upstream.json")) as Map<*, *>
        require(pin["nodeEngineCompatible"] == true && tools == pin["tools"]) { "Renderer tools differ from approved pin" }
        val records = manifest["files"] as? List<*> ?: error("Manifest files must be asset records")
        val files = records.associate { record ->
            val item = record as? Map<*, *> ?: error("Invalid asset record")
            require(item["sha256"] is String && item["sha256"].toString().matches(Regex("[a-f0-9]{64}"))) { "Invalid asset hash" }
            require(item["size"] is Int || item["size"] is Long) { "Invalid asset size type" }
            require((item["size"] as Number).toLong() >= 0) { "Invalid asset size" }
            item["path"] to item
        }
        require(metadata["files"] == records.size) { "Renderer file count mismatch" }
        require(files.size == records.size && files.keys.containsAll(setOf("index.html", "licenses/index.html", "THIRD_PARTY_NOTICES.txt"))) { "Renderer entry or licenses are not inventoried" }
        val root = source.canonicalFile
        for ((name, expected) in files) {
            val path = name as? String ?: error("Invalid asset path")
            require(path.isNotBlank() && path.none { it.code < 32 || it == '\\' || it == '%' || it == '?' || it == '#' || it == ':' } && path.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "Unsafe asset path" }
            val asset = source.resolve(path).canonicalFile
            require(asset.toPath().startsWith(root.toPath()) && asset.isFile) { "Missing asset: $path" }
            require(asset.length() == (expected["size"] as Number).toLong()) { "Asset size mismatch: $path" }
            var current: File = source
            for (part in path.split('/')) {
                current = current.resolve(part)
                require(!Files.isSymbolicLink(current.toPath())) { "Symlink asset: $path" }
            }
            val digest = MessageDigest.getInstance("SHA-256")
            asset.inputStream().use { stream ->
                val buffer = ByteArray(65536)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            require(digest.digest().joinToString("") { "%02x".format(it) } == expected["sha256"]) { "Asset hash mismatch: $path" }
        }
        val actual = source.walkTopDown().filter { it.isFile }.map { it.relativeTo(source).invariantSeparatorsPath }.toSet()
        require(actual == files.keys + setOf("asset-manifest.json", "renderer-build.json")) { "Uninventoried renderer asset" }
        val destination = outputDirectory.get().asFile
        delete(destination)
        copy {
            from(source)
            into(destination.resolve("renderer"))
        }
    }
}

androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(prepareRendererAssets, PrepareRendererAssets::outputDirectory)
}

dependencies {
    implementation(libs.androidx.webkit)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
}
