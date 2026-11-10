plugins {
    alias(libs.plugins.android.application)
}

// ABI(s) to BUILD *and* PACKAGE. Kept in one place on purpose: packaging must match what
// CMake actually built. Declaring an ABI in ndk.abiFilters that CMake did not build produces
//   "There are no .so files available to package in the APK for arm64-v8a."
// and ships an APK that Android considers installable on arm64 but that has no native libs
// there, so the app would crash on launch.
//
// Defaults to x86_64 for a fast AVD loop. Override per invocation:
//   -PddsAbi=arm64-v8a            -> phone only
//   -PddsAbi=x86_64,arm64-v8a     -> one APK covering both (builds the stack twice)
val ddsAbis: List<String> = providers.gradleProperty("ddsAbi")
    .getOrElse("x86_64")
    .split(',')
    .map { it.trim() }
    .filter { it.isNotEmpty() }

android {
    namespace = "com.example.fastddstest"
    compileSdk {
        version = release(37)
    }

    // NDK r30 (API 21-37) - pinned; matches the toolchain used by the Fast DDS ExternalProjects.
    ndkVersion = "30.0.16248370"

    buildFeatures {
        viewBinding = true
    }

    defaultConfig {
        applicationId = "com.example.fastddstest"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Built *and* packaged - see ddsAbis above.
        ndk {
            abiFilters += ddsAbis
        }

        externalNativeBuild {
            cmake {
                // Fast DDS / Fast CDR are built as shared libs against the shared STL.
                // NOTE: no cppFlags/cFlags here on purpose - the NDK guide forbids passing
                // compiler flags from Gradle; flags belong in CMakeLists.txt.
                arguments += "-DANDROID_STL=c++_shared"

                // Built only. Keeps the Fast DDS ExternalProject build to the ABI(s)
                // requested via -PddsAbi instead of always building both.
                abiFilters += ddsAbis
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }
}

dependencies {
    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}

// Codegen for the DDS IDL -> C++ types.
// Deliberately NOT wired into assembleDebug/preBuild: the generated sources are committed,
// so a normal build never needs a JDK on PATH or network access.
// Run manually with: ./gradlew :app:generateDdsIdl
val ddsGeneratedDir = file("src/main/cpp/generated")

tasks.register<Exec>("generateDdsIdl") {
    group = "dds"
    description = "Regenerates C++ DDS types from the IDL in app/src/main/cpp/idl via Fast DDS-Gen."
    // Paths below are repo-root relative.
    workingDir = rootProject.projectDir
    executable = "java"
    args(
        "-jar", "tools/.cache/fastddsgen/fastddsgen.jar",
        // Case-sensitive grammar. Fast DDS-Gen matches IDL keywords case-insensitively by
        // default, so `struct String` would be rejected as a redefinition of the `string`
        // keyword. ROS 2's own rosidl fastrtps typesupport passes -cs for exactly this reason.
        "-cs",
        "-typeros2",
        "-de", "final",
        // No C preprocessor. Fast DDS-Gen shells out to an MSVC-style `cl /E /I...` on
        // Windows, which does not exist on a pure-Android host; the IDL here has no
        // #include/#define so preprocessing is unnecessary. Drop this flag on a host with
        // Visual Studio if the IDL ever gains includes.
        "-ppDisable",
        // Flatten the output: without this the generator mirrors the input path
        // (generated/app/src/main/cpp/idl/std_msgs/msg/...) and CMakeLists.txt's
        // `generated/*.cxx` glob finds nothing.
        "-flat-output-dir",
        "-I", "app/src/main/cpp/idl",
        "-d", "app/src/main/cpp/generated",
        "app/src/main/cpp/idl/std_msgs/msg/String.idl",
    )
    outputs.dir(ddsGeneratedDir)
    doFirst { ddsGeneratedDir.mkdirs() }
}
