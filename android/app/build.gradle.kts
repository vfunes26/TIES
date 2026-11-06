plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val ciVersionCode = providers.gradleProperty("versionCode").orNull?.toIntOrNull() ?: 1
val ciVersionName = providers.gradleProperty("versionName").orNull ?: "0.1.0"

android {
    namespace = "es.citaasistente.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "es.citaasistente.app"
        minSdk = 26
        targetSdk = 34
        versionCode = ciVersionCode
        versionName = ciVersionName
    }

    // Firma opcional: si defines estos secretos en GitHub Actions el APK sale firmado (release).
    // Si no, se genera el APK de depuración (debug), que ya se puede instalar directamente.
    signingConfigs {
        val ks = System.getenv("KEYSTORE_FILE")
        if (ks != null && file(ks).exists()) {
            create("release") {
                storeFile = file(ks)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    // El catálogo (provincias, sedes, trámites) es la fuente única compartida con el bot: /shared/catalog.json
    sourceSets["main"].assets.srcDirs("src/main/assets", "../../shared")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        resources {
            excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES", "META-INF/INDEX.LIST")
        }
    }
    buildFeatures { buildConfig = true }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // Envío de correo por SMTP (TLS)
    implementation("com.sun.mail:android-mail:1.6.7")
    implementation("com.sun.mail:android-activation:1.6.7")
    
    // Serialización JSON para gestión de Proxies y Cola
    implementation("com.google.code.gson:gson:2.10.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
