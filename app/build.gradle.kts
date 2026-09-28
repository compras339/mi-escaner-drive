// build.gradle.kts (módulo :app)
// Solo se muestran los bloques relevantes: packaging (necesario para las librerías
// de Google API Client) y dependencies. Mantén el resto de tu configuración de android {}.

android {
    // ... namespace, compileSdk, defaultConfig, buildTypes, etc.

    // Las librerías google-api-client / google-http-client incluyen archivos META-INF
    // duplicados que hacen fallar el empaquetado. Esta exclusión lo soluciona.
    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/INDEX.LIST"
            )
        }
    }

    // Java 17 recomendado para las últimas versiones de AGP
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {

    // ---------- AndroidX / UI ----------
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("com.google.android.material:material:1.12.0")

    // ---------- Escáner de documentos (Google Play Services) ----------
    implementation("com.google.android.gms:play-services-document-scanner:16.0.0-beta1")

    // ---------- Google Sign-In ----------
    implementation("com.google.android.gms:play-services-auth:20.7.0")

    // ---------- Google Drive API v3 ----------
    // Se excluye org.apache.httpcomponents porque Android ya incluye su propia
    // implementación y provoca conflictos de clases duplicadas.
    implementation("com.google.api-client:google-api-client-android:1.33.0") {
        exclude(group = "org.apache.httpcomponents")
    }
    implementation("com.google.apis:google-api-services-drive:v3-rev20220815-2.0.0") {
        exclude(group = "org.apache.httpcomponents")
    }

    // ---------- Corrutinas ----------
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
