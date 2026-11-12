plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun configString(name: String): String = (providers.gradleProperty(name).orNull
    ?: providers.environmentVariable(name).orNull ?: "").replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "kr.solbit.catchcharacter"
    compileSdk = 37
    experimentalProperties["android.nativeLibraryAlignmentPageSize"] = "16k"

    defaultConfig {
        applicationId = "kr.solbit.catchcharacter"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "SUPABASE_URL", "\"${configString("SUPABASE_URL")}\"")
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"${configString("SUPABASE_PUBLISHABLE_KEY")}\"")
    }
    signingConfigs {
        // 배포용 서명은 환경변수에 별도 키 파일과 암호가 제공될 때만 활성화.
        if (!System.getenv("RELEASE_KEYSTORE_PATH").isNullOrBlank()) {
            create("release") {
                storeFile = file(System.getenv("RELEASE_KEYSTORE_PATH"))
                storePassword = System.getenv("RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (!System.getenv("RELEASE_KEYSTORE_PATH").isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) } }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.12.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("io.github.sceneview:arsceneview:4.67.0")
}
