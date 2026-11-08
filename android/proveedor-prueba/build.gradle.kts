// App de prueba: un proveedor de busqueda falso (contrato v2 de
// docs/proveedores.md) para probar el buscador en el emulador sin PixPin.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.max.flowlauncher.prueba"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.max.flowlauncher.prueba"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
