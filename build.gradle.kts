buildscript {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:9.5.1")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
        classpath("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.4.21")
    }
}
tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}

allprojects {
    configurations.all {
        resolutionStrategy {
            force("androidx.compose.material:material-ripple:1.12.1")
            force("androidx.core:core:1.19.1")
            force("androidx.core:core-ktx:1.19.1")
            force("androidx.lifecycle:lifecycle-common-jvm:2.11.0")
            force("androidx.lifecycle:lifecycle-runtime-android:2.11.0")
        }
    }
}
