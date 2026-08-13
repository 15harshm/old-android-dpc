import java.util.Properties
import java.io.FileInputStream
import groovy.json.JsonSlurper

plugins {
    id("com.android.application")
    id("kotlin-android")
    id("dev.flutter.flutter-gradle-plugin")
    id("com.google.gms.google-services")
}

// Escape a String for embedding inside a Java string literal in a buildConfigField.
fun bcfString(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.renew.jss"
    compileSdk = 36
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        buildConfig = true
    }

    // -------------------------
    // Signing Config
    // -------------------------
    // Secrets are read from a git-ignored `keystore.properties` (android/app/).
    // If that file is absent we fall back to the historical hardcoded values so a
    // local release build still works. See keystore.properties.example.
    val keystoreProperties = Properties()
    val keystorePropertiesFile = file("keystore.properties")
    if (keystorePropertiesFile.exists()) {
        FileInputStream(keystorePropertiesFile).use { keystoreProperties.load(it) }
    }

    signingConfigs {
        create("release") {
            storeFile = file(keystoreProperties.getProperty("storeFile", "test-dpc.jks"))
            storePassword = keystoreProperties.getProperty("storePassword", "Jss@90912")
            keyAlias = keystoreProperties.getProperty("keyAlias", "test-dpc")
            keyPassword = keystoreProperties.getProperty("keyPassword", "Jss@90912")
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    defaultConfig {
        applicationId = "com.renew.jss"
        minSdk = flutter.minSdkVersion
        targetSdk = 36
        // CI drives the versionCode via BUILD_NUMBER; local builds stay at 4.
        versionCode = (System.getenv("BUILD_NUMBER")?.toIntOrNull() ?: 4)
        versionName = flutter.versionName
    }

    flavorDimensions += "branding"

    // -------------------------
    // Product Flavors (single source of truth)
    // -------------------------
    // Generated from tool/flavors/flavors.yaml -> android/flavors.gen.json by
    // tool/flavors/gen_flavors.dart. Do NOT hand-edit flavors here; edit the YAML
    // and regenerate. Each flavor injects DOMAIN/APP_NAME/API_BASE/SOCKET_BASE via
    // BuildConfig (consumed by ApiConfig.kt) plus app_name / accessibility_service_
    // description via resValue (so per-flavor strings.xml files are unnecessary).
    @Suppress("UNCHECKED_CAST")
    val flavorList = JsonSlurper()
        .parse(rootProject.file("flavors.gen.json")) as List<Map<String, Any>>

    productFlavors {
        flavorList.forEach { f ->
            create(f["id"] as String) {
                dimension = "branding"
                applicationId = "com.renew.jss"
                buildConfigField("String", "DOMAIN", bcfString(f["domain"] as String))
                buildConfigField("String", "APP_NAME", bcfString(f["appName"] as String))
                buildConfigField("String", "API_BASE", bcfString(f["apiBase"] as String))
                buildConfigField("String", "SOCKET_BASE", bcfString(f["socketBase"] as String))
                resValue("string", "app_name", f["appName"] as String)
                resValue(
                    "string",
                    "accessibility_service_description",
                    f["accessibilityDescription"] as String
                )
            }
        }
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.11.0")
    implementation("com.squareup.okio:okio:3.5.0")
    implementation("com.google.android.gms:play-services-location:21.0.1")
    // Removed: socket.io-client - FCM Only
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // 🎨 CardView dependency for modern UI
    implementation("androidx.cardview:cardview:1.0.0")

    // 🚨 FCM DEPENDENCIES - For FCM command processing
    // The BOM must be declared FIRST via platform() so it actually manages versions;
    // individual Firebase libs are then pulled UNVERSIONED and the BOM picks a
    // consistent set. (Previously the BOM was a plain implementation() no-op and
    // firebase-messaging was hard-pinned, defeating the BOM.)
    implementation(platform("com.google.firebase:firebase-bom:32.7.4"))
    implementation("com.google.firebase:firebase-messaging")
}

flutter {
    source = "../.."
}
