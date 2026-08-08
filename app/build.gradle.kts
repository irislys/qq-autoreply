import java.util.Properties

plugins {
    alias(libs.plugins.agp.app)
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) {
        f.inputStream().use { load(it) }
    }
}

fun signingCred(name: String, envName: String): String {
    return System.getenv(envName)?.takeIf { it.isNotBlank() }
        ?: localProps.getProperty(name)?.takeIf { it.isNotBlank() }
        ?: error("缺少签名凭据 $name：请设置环境变量 $envName 或在 local.properties 中配置 $name")
}

val keystorePath = System.getenv("TFF_KEYSTORE_PATH")
    ?: localProps.getProperty("tff.keystorePath")
    ?: rootProject.file("release.keystore").absolutePath
val keystoreAlias = System.getenv("TFF_KEY_ALIAS")
    ?: localProps.getProperty("tff.keyAlias")
    ?: "tff"
val storePass = signingCred("tff.storePassword", "TFF_STORE_PASSWORD")
val keyPass = signingCred("tff.keyPassword", "TFF_KEY_PASSWORD")

android {
    namespace = "com.tff.qq"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("release") {
            storeFile = file(keystorePath)
            storePassword = storePass
            keyAlias = keystoreAlias
            keyPassword = keyPass
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs["release"]
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        aidl = true
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
            excludes += "META-INF/*.version"
            excludes += "META-INF/*.kotlin_module"
            excludes += "**/proguard.txt"
            excludes += "**/kotlin/**"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }
}

dependencies {
    compileOnly(libs.libxposed.api)
    compileOnly(libs.androidx.annotation)
    implementation(libs.dexkit.lib)
}
