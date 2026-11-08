plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.max.flowlauncher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.max.flowlauncher"
        // Android 7.0: casi cualquier movil, tambien los de gama baja y Go.
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Hasta tener una clave propia: la de depuracion, para poder
            // instalar el APK de release y probarlo.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    packaging {
        jniLibs {
            // Los plugins son programas que se ejecutan desde
            // nativeLibraryDir: tienen que estar descomprimidos en disco.
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Sin dependencias: ni AndroidX ni corrutinas. Solo el SDK de Android y la
// libreria estandar de Kotlin, para un APK pequeno y un arranque rapido.
// (JUnit solo para las pruebas: no entra en el APK.)
dependencies {
    testImplementation("junit:junit:4.13.2")
}
