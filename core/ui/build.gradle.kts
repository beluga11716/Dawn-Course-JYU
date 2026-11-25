plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.hiltAndroid)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.dawncourse.core.ui"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    buildFeatures {
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // kotlinOptions 块已移除：built-in Kotlin 下 jvmTarget 默认等于
    // 上面的 compileOptions.targetCompatibility，无需重复声明
}

dependencies {
    implementation(project(":core:domain"))
    implementation(libs.core.ktx)
    implementation(platform(libs.compose.bom))
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)
    implementation(libs.material3)
    implementation(libs.material.icons.extended)
    implementation(libs.palette.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.hilt.android)
    implementation(libs.hilt.lifecycle.viewmodel.compose)
    ksp(libs.hilt.compiler)

    debugImplementation(libs.ui.tooling)
    debugImplementation(libs.ui.test.manifest)
}
