import com.android.build.api.artifact.SingleArtifact
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Environment-only signing: no credentials or keystore files belong in this repository.
val releaseSigningEnvironment = listOf(
    "PUMPING_KEYSTORE_FILE", "PUMPING_KEY_ALIAS",
    "PUMPING_KEYSTORE_PASSWORD", "PUMPING_KEY_PASSWORD",
).associateWith { providers.environmentVariable(it).orNull }
val releaseKeystore = releaseSigningEnvironment["PUMPING_KEYSTORE_FILE"]?.takeIf { it.isNotBlank() }?.let(::File)
val repositoryDirectory = rootDir.canonicalFile.toPath()

val validateReleaseCredentials = tasks.register("validateReleaseCredentials") {
    group = "verification"
    description = "Require an external permanent release key; never fall back to debug signing."
    notCompatibleWithConfigurationCache("Release credentials must not be serialized into a project configuration cache.")
    doLast {
        val missing = releaseSigningEnvironment.filterValues { it.isNullOrBlank() }.keys
        if (missing.isNotEmpty()) throw GradleException(
            "Release signing is not configured. Set the external signing environment variables: " +
                missing.joinToString(", ") + ". See RELEASE_SIGNING.md. No release APK was produced."
        )
        val path = requireNotNull(releaseKeystore)
        if (!path.isAbsolute || !path.isFile || !path.canRead())
            throw GradleException("PUMPING_KEYSTORE_FILE must be an absolute path to a readable external keystore.")
        if (path.canonicalFile.toPath().startsWith(repositoryDirectory))
            throw GradleException("The permanent release keystore must be outside the Git repository, including ignored directories.")
        val alias = requireNotNull(releaseSigningEnvironment["PUMPING_KEY_ALIAS"])
        if (alias.equals("androiddebugkey", ignoreCase = true))
            throw GradleException("Android's development/debug key cannot sign a public release.")
        val certificate = try {
            val keys = KeyStore.getInstance(path, requireNotNull(releaseSigningEnvironment["PUMPING_KEYSTORE_PASSWORD"]).toCharArray())
            if (!keys.isKeyEntry(alias) || keys.getKey(alias, requireNotNull(releaseSigningEnvironment["PUMPING_KEY_PASSWORD"]).toCharArray()) !is PrivateKey)
                throw IllegalArgumentException()
            keys.getCertificate(alias) as X509Certificate
        } catch (_: Exception) {
            // Do not expose passwords, provider exception details or key material in logs.
            throw GradleException("Cannot unlock the release keystore/private key. Check the path, alias and both signing passwords.")
        }
        if (certificate.subjectX500Principal.name.contains("CN=Android Debug", ignoreCase = true))
            throw GradleException("Android's development/debug certificate cannot sign a public release.")
        certificate.checkValidity()
    }
}

android {
    namespace = "app.pumping"
    compileSdk = 37
    buildToolsVersion = "36.1.0"
    defaultConfig {
        applicationId = "dev.killermelon1458.pumping"
        minSdk = 26
        targetSdk = 36
        versionCode = providers.gradleProperty("pumpingVersionCode").get().toInt()
        versionName = providers.gradleProperty("pumpingVersion").get()
        testInstrumentationRunner = "app.pumping.SyntheticTestRunner"
    }
    signingConfigs {
        create("release") {
            storeFile = releaseKeystore
            keyAlias = releaseSigningEnvironment["PUMPING_KEY_ALIAS"]
            storePassword = releaseSigningEnvironment["PUMPING_KEYSTORE_PASSWORD"]
            keyPassword = releaseSigningEnvironment["PUMPING_KEY_PASSWORD"]
        }
    }
    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-dev"
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("release") {
            versionNameSuffix = "-beta"
            isDebuggable = false
            isJniDebuggable = false
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

// AGP enables host tests for debug by default; exercise the same suite against release code too.
androidComponents.beforeVariants(androidComponents.selector().withBuildType("release")) { variant ->
    variant.hostTests.values.forEach { host ->
        if (host.type == "UnitTest") host.enable = true
    }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.08.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.08.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Keep unsigned compilation/lint/unit tests available, but gate every release packaging path.
tasks.configureEach {
    if (name in setOf("validateSigningRelease", "packageRelease", "packageReleaseBundle",
            "signReleaseBundle", "assembleRelease", "bundleRelease", "installRelease")) {
        dependsOn(validateReleaseCredentials)
    }
}

// Staging is part of assemble, and consumes AGP's completed APK artifact (not a guessed path).
androidComponents.onVariants { variant ->
    val capitalized = variant.name.replaceFirstChar { it.uppercase() }
    val apkDirectory = variant.artifacts.get(SingleArtifact.APK)
    val loader = variant.artifacts.getBuiltArtifactsLoader()
    val output = variant.outputs.single()
    val versionName = output.versionName
    val versionCode = output.versionCode
    val applicationId = variant.applicationId
    val sdkDirectory = androidComponents.sdkComponents.sdkDirectory
    val stagingScript = rootProject.file("tools/stage_apk.py")
    val channel = requireNotNull(variant.buildType)
    val stagingDirectory = rootProject.file(if (channel == "debug") "dev-builds" else "test-builds")
    val stage = tasks.register("stage${capitalized}Apk") {
        group = "build"
        description = "Verify and stage ${variant.name} as Pumping-<variant versionName>.apk"
        notCompatibleWithConfigurationCache("Staging verifies shared local artifacts and version collisions on every invocation.")
        inputs.dir(apkDirectory)
        inputs.files(stagingScript, rootProject.file("tools/prepare_beta_release.py"))
        outputs.upToDateWhen { false }
        if (channel == "release") dependsOn(validateReleaseCredentials)
        doLast {
            val artifacts = loader.load(apkDirectory.get())
                ?: throw GradleException("Cannot read completed APK output metadata for $capitalized.")
            val apk = artifacts.elements.singleOrNull()
                ?: throw GradleException("Expected one universal APK; split APK staging requires explicit handling.")
            val process = ProcessBuilder(
                "python3", stagingScript.absolutePath,
                "--apk", apk.outputFile,
                "--version-name", versionName.get(),
                "--version-code", versionCode.get().toString(),
                "--application-id", applicationId.get(),
                "--channel", channel,
                "--sdk", sdkDirectory.get().asFile.absolutePath,
                "--output", stagingDirectory.absolutePath,
            ).redirectErrorStream(true).start()
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { logger.lifecycle(it) }
            }
            if (process.waitFor() != 0) throw GradleException("APK verification/staging failed; see the message above.")
        }
    }
    tasks.configureEach {
        if (name == "assemble$capitalized") dependsOn(stage)
    }
}
