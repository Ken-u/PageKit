import org.jetbrains.kotlin.gradle.dsl.JvmTarget

fun semVerToVersionCode(versionName: String): Int {
    val match = Regex(
        "^(\\d+)\\.(\\d+)\\.(\\d+)(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?$",
    ).matchEntire(versionName) ?: error("PAGEKIT_VERSION must be SemVer, got: $versionName")
    val (major, minor, patch) = match.destructured
    require(minor.toInt() < 1_000 && patch.toInt() < 1_000) {
        "PAGEKIT_VERSION minor and patch must be below 1000"
    }
    return (major.toLong() * 1_000_000L + minor.toLong() * 1_000L + patch.toLong()).also {
        require(it in 1..2_100_000_000L) { "PAGEKIT_VERSION produces an invalid Android versionCode: $it" }
    }.toInt()
}

val pageKitVersion = providers.gradleProperty("PAGEKIT_VERSION").get()

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.kenjc.pagekit"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kenjc.pagekit"
        minSdk = 28
        targetSdk = 35
        versionCode = semVerToVersionCode(pageKitVersion)
        versionName = pageKitVersion
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.flexmark.html2md)
    implementation(libs.ktor.server.cio)
    implementation(libs.mcp.kotlin.server)
    implementation(libs.androidx.work.runtime)
    testImplementation(libs.junit)
    testImplementation(libs.mcp.kotlin.client)
    testImplementation(libs.mcp.kotlin.testing)
}
