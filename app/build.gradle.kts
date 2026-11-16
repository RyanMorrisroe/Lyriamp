plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kover)
}

android {
    namespace = "com.ryanmorrisroe.lyriamp"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.ryanmorrisroe.lyriamp"
        minSdk = 36
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

kover {
    currentProject {
        createVariant("core") {
            add("debug")
        }
    }
    reports {
        variant("core") {
            filters {
                includes {
                    classes(
                        "com.ryanmorrisroe.lyriamp.data.lms.*",
                        "com.ryanmorrisroe.lyriamp.data.persistence.*",
                        "com.ryanmorrisroe.lyriamp.feature.connection.ConnectionViewModel",
                        "com.ryanmorrisroe.lyriamp.feature.library.LibraryCategory",
                        "com.ryanmorrisroe.lyriamp.feature.library.LibraryLocation",
                        "com.ryanmorrisroe.lyriamp.feature.library.LibraryUiState",
                        "com.ryanmorrisroe.lyriamp.feature.library.LibraryViewModel*",
                        "com.ryanmorrisroe.lyriamp.feature.library.LmsLibrary*PagingSource"
                    )
                }
                excludes {
                    classes("com.ryanmorrisroe.lyriamp.data.persistence.AndroidKeystoreCredentialCipher*")
                }
            }
            verify {
                rule {
                    minBound(90)
                }
            }
        }
    }
}

dependencies {
    //Base Android
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.graphics.path)
    implementation(libs.androidx.paging.compose)
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.concurrent.futures)
    implementation(libs.androidx.navigation.compose)

    // Networking
    implementation(libs.ktor.client.android)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // Image Loading
    implementation(libs.coil.compose)

    // Media3
    implementation(libs.androidx.media3.session)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)

    // Debug
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}