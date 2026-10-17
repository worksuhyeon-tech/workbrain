plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// CI가 넣어 주는 고정 서명 열쇠. 없으면(로컬 빌드) 기본 디버그 열쇠로 서명한다.
// 열쇠가 빌드마다 바뀌면 휴대폰에서 '업데이트'가 안 되고 지웠다 깔아야 해서 녹음이 사라지므로 CI는 항상 같은 열쇠를 쓴다.
val keystorePath: String? = System.getenv("WB_KEYSTORE_FILE")
val keystorePassword: String? = System.getenv("WB_KEYSTORE_PASSWORD")

android {
    namespace = "com.workbrain.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.workbrain.app"
        minSdk = 29
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1.$versionCode"
        buildConfigField("String", "SUPABASE_URL", "\"${property("workbrain.supabaseUrl")}\"")
        buildConfigField("String", "SUPABASE_KEY", "\"${property("workbrain.supabaseKey")}\"")
    }

    signingConfigs {
        create("fixed") {
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = keystorePassword
                keyAlias = "workbrain"
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        debug {
            if (keystorePath != null) signingConfig = signingConfigs.getByName("fixed")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.03.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation(platform("io.github.jan-tennert.supabase:bom:3.1.4"))
    implementation("io.github.jan-tennert.supabase:auth-kt")
    implementation("io.ktor:ktor-client-okhttp:3.1.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
