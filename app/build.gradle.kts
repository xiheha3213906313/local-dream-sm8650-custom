plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

ktlint {
    android.set(true)
    version.set("1.8.0")
    ignoreFailures.set(false)
    filter {
        exclude { it.file.path.contains("/build/") }
        exclude { it.file.path.contains("/cpp/3rdparty/") }
    }
}

detekt {
    toolVersion = "1.23.7"
    config.setFrom("$projectDir/detekt.yml")
    buildUponDefaultConfig = true
    parallel = true
    baseline = file("$projectDir/detekt-baseline.xml")
    source.setFrom(files("src/main/java", "src/main/kotlin"))
}

val sm8650Compat = providers.gradleProperty("sm8650Compat").orNull == "true"

android {
    namespace = "io.github.xororz.localdream"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.xororz.localdream"
        if (sm8650Compat) {
            applicationIdSuffix = ".sm8650"
            versionNameSuffix = "-sm8650"
        }
        manifestPlaceholders["localDreamLabel"] = if (sm8650Compat) "Local Dream SM8650" else "@string/app_name"
        minSdk = 28
//        minSdk = 31
        targetSdk = 36
        versionCode = 91
        versionName = "3.0.0-alpha.8.7"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        ndk {
            //noinspection ChromeOsAbiSupport
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        getByName("debug") {
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
        create("release") {
            storeFile = file(project.findProperty("RELEASE_STORE_FILE") as String? ?: "keystore.jks")
            storePassword = project.findProperty("RELEASE_STORE_PASSWORD") as String?
            keyAlias = project.findProperty("RELEASE_KEY_ALIAS") as String?
            keyPassword = project.findProperty("RELEASE_KEY_PASSWORD") as String?
        }
    }

    bundle {
        density {
            enableSplit = true
        }
        abi {
            enableSplit = true
        }
        language {
            enableSplit = false
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            signingConfig = signingConfigs.getByName(if (sm8650Compat) "debug" else "release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
            it.systemProperty("ui.screenshots.dir", layout.buildDirectory.dir("reports/ui-screenshots").get().asFile.absolutePath)
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
    flavorDimensions += "version"
    productFlavors {
        create("basic") {
            dimension = "version"
            versionNameSuffix = ""
        }
        create("filter") {
            dimension = "version"
            versionNameSuffix = "_with_filter"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val versionName = output.versionName.orNull
            if (output is com.android.build.api.variant.impl.VariantOutputImpl) {
                output.outputFileName.set("LocalDream_armv8a_$versionName.apk")
            }
        }
    }
}

dependencies {
    implementation(libs.miuix.ui)
    implementation(libs.miuix.preference)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material3.adaptive)
    implementation(libs.androidx.material3.window.size)
    implementation(libs.androidx.graphics.shapes)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.okhttp)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.material3.xml)
    implementation(libs.coil.compose)
    implementation(libs.cropify)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation(libs.androidx.ui.test.junit4)
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    // Adds the ktlint-rule wrappers to detekt; we only enable UnusedImports
    // (the standalone ktlint plugin's no-unused-imports does not flag them).
    detektPlugins(libs.detekt.formatting)
}

// Fail early instead of producing a UI-only APK without the compatible DSP engine.
if (sm8650Compat) {
    val verifySm8650Native by tasks.registering {
        doLast {
            listOf("libdit_engine.so", "libdit_diagnostic.so", "libstable_diffusion_core.so").forEach { name ->
                check(file("src/main/jniLibs/arm64-v8a/$name").isFile) {
                    "Missing $name: build dit/build.sh and stage the pinned runtime first"
                }
            }
            val skel = file("src/main/assets/ditlibs/libggml-htp-v75.so")
            check(skel.isFile) { "Missing v75 DSP library; run dit/build.sh" }
            val elf = skel.readBytes()
            check(
                elf.size > 40 && elf[4].toInt() == 1 && (elf[18].toInt() and 255) == 164 &&
                    (elf[36].toInt() and 255) == 0x75,
            ) { "Expected an ELF32 Hexagon v75 DSP library" }
        }
    }
    tasks.named("preBuild") { dependsOn(verifySm8650Native) }
}
