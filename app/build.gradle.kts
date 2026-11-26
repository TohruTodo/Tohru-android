import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Git tags are the single source of truth for the app version. CI can still
// override both values through the environment.
fun gitOutput(vararg args: String): String? = try {
    providers.exec {
        commandLine("git", *args)
    }.standardOutput.asText.get().trim().takeIf(String::isNotEmpty)
} catch (_: Exception) {
    null
}

val gitDescribe = gitOutput("describe", "--tags", "--always", "--dirty")
val gitTag = gitOutput("describe", "--tags", "--abbrev=0")
val appVersionName = System.getenv("VERSION_NAME")?.takeIf(String::isNotBlank)
    ?: gitDescribe?.removePrefix("v")?.takeIf { gitTag != null }
    ?: buildString {
        append("0.0.0-dev+")
        append(gitOutput("rev-parse", "--short", "HEAD") ?: "unknown")
        if (gitDescribe?.endsWith("-dirty") == true) append("-dirty")
    }
val appVersionCode = System.getenv("VERSION_CODE")?.toIntOrNull()
    ?: gitOutput("rev-list", "--count", "HEAD")?.toIntOrNull()?.coerceAtLeast(1)
    ?: 1

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val envKeystorePath = System.getenv("KEYSTORE_PATH")
val hasReleaseKeystore = keystorePropertiesFile.exists() || envKeystorePath != null

apply(plugin = "com.android.application")
apply(plugin = "org.jetbrains.kotlin.plugin.compose")

configure<com.android.build.api.dsl.ApplicationExtension> {
    namespace = "io.github.tohrutodo.tohru"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.tohrutodo.tohru"
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                if (keystorePropertiesFile.exists()) {
                    storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                    storePassword = keystoreProperties.getProperty("storePassword")
                    keyAlias = keystoreProperties.getProperty("keyAlias")
                    keyPassword = keystoreProperties.getProperty("keyPassword")
                } else {
                    storeFile = file(envKeystorePath!!)
                    storePassword = System.getenv("KEYSTORE_PASSWORD")
                    keyAlias = System.getenv("KEY_ALIAS")
                    keyPassword = System.getenv("KEY_PASSWORD")
                }
            }
        }
    }

    signingConfigs {
        getByName("debug") {
            (project.findProperty("debugKeystore") as String?)?.let {
                storeFile = file(it)
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    buildFeatures {
        compose = true
    }
    packaging {
        // JGit core and its SSH adapter ship the same Eclipse metadata.
        resources.excludes.add("plugin.properties")
        resources.excludes.add("about.html")
        resources.excludes.add("META-INF/eclipse.inf")
    }
    lint {
        lintConfig = file("lint.xml")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

tasks.register("printVersionName") {
    doLast { println(appVersionName) }
}

dependencies {
    val composeUi = "1.12.1"
    val deps = listOf(
        "androidx.core:core-ktx:1.19.1",
        "androidx.activity:activity-compose:1.13.0",
        "androidx.compose.runtime:runtime:$composeUi",
        "androidx.compose.ui:ui:$composeUi",
        "androidx.compose.foundation:foundation:$composeUi",
        "androidx.compose.material3:material3:1.4.0",
        "androidx.lifecycle:lifecycle-viewmodel:2.11.0",
        "androidx.lifecycle:lifecycle-runtime-compose:2.11.0",
        "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0",
    )
    deps.forEach { project.dependencies.add("implementation", it) }
    project.dependencies.add("testImplementation", "junit:junit:4.13.2")

    val jgit = "5.13.5.202508271544-r"
    project.dependencies.add("implementation", "org.eclipse.jgit:org.eclipse.jgit:$jgit")
    project.dependencies.add("implementation", "org.eclipse.jgit:org.eclipse.jgit.ssh.jsch:$jgit") {
        exclude(group = "com.jcraft", module = "jsch")
    }
    // Maintained JSch fork: ed25519 keys and modern algorithms on Android.
    project.dependencies.add("implementation", "com.github.mwiede:jsch:2.28.8")
    project.dependencies.add("implementation", "org.slf4j:slf4j-nop:1.7.36")
    // intellij-markdown supplies the AST used for task link extraction and
    // exact source-range rewrites.
    project.dependencies.add("implementation", "org.jetbrains:markdown:0.7.18")
    // Compose renderer over the same parser AST. The m3 module supplies the
    // Material 3 color and typography defaults.
    project.dependencies.add("implementation", "com.mikepenz:multiplatform-markdown-renderer:0.46.0")
    project.dependencies.add("implementation", "com.mikepenz:multiplatform-markdown-renderer-m3:0.46.0")
}
