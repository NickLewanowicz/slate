import kotlinx.kover.gradle.plugin.dsl.CoverageUnit

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
}

android {
    namespace = "dev.slate.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.slate.android"
        minSdk = 31
        targetSdk = 35
        versionCode = 200
        versionName = "2.0.0-alpha.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { test ->
                test.maxHeapSize = "3g"
            }
        }
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1,LICENSE.md,LICENSE-notice.md}"
    }
    lint {
        disable += "MissingTranslation"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.datastore.core)

    implementation(libs.androidx.work.runtime)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.androidx.work.testing)
}

kover {
    reports {
        filters {
            excludes {
                // Exclusions are deliberate and documented:
                // - di/**: manual wiring only, no branches.
                // - ui/**: Compose screens + ViewModels; all logic lives in covered
                //   classes (spec, data, api, interact, sync, widget).
                // - SlateApplication/MainActivity: bootstrap glue (wildcard patterns;
                //   kover class patterns are wildcard-based).
                // - The data-carrier DTOs below mirror the frozen schema
                //   (schema/slate.schema.json + the api.md envelopes). They contain
                //   NO authored branching logic - every counted branch is
                //   compiler-generated (equals/hashCode/copy$default) - and their
                //   real behavior (decode/encode, defaults, fallbacks) is asserted
                //   through SpecParserTest, WidgetRendererTest and SlateApiTest.
                //   Hand-written serializers (SlateElementListSerializer,
                //   UnknownElementSerializer, tone/style serializers), SlateSpec
                //   (aggregateTone/isExpired/parseIsoOrNull) and TodoListElement
                //   stay measured.
                packages("dev.slate.android.di", "dev.slate.android.ui")
                classes(
                    "dev.slate.android.SlateApplication*",
                    // Wire DTO mirroring schema/interactions.schema.json (same policy as
                    // the spec data carriers: behavior asserted via serialization tests).
                    "dev.slate.android.api.WireInteraction*",
                    "dev.slate.android.MainActivity*",
                    "*Preview*",
                    "*\$Preview*",
                    "*.ComposableSingletons*",
                    "*BuildConfig*",
                    "dev.slate.android.R*",
                    "dev.slate.android.spec.ColumnElement*",
                    "dev.slate.android.spec.RowElement*",
                    "dev.slate.android.spec.TextElement*",
                    "dev.slate.android.spec.StatusRowElement*",
                    "dev.slate.android.spec.QuestionElement*",
                    "dev.slate.android.spec.QuestionOption*",
                    "dev.slate.android.spec.ProgressElement*",
                    "dev.slate.android.spec.DividerElement*",
                    "dev.slate.android.spec.SpacerElement*",
                    "dev.slate.android.spec.TodoItem*",
                    "dev.slate.android.data.PendingInteraction*",
                    "dev.slate.android.data.AnsweredChoice*",
                    "dev.slate.android.data.QueueSnapshot*",
                    "dev.slate.android.data.CachedSlate*",
                    "dev.slate.android.data.CacheSnapshot*",
                    "dev.slate.android.api.SlateSummary*",
                    "dev.slate.android.api.SlateListResponse*",
                    "dev.slate.android.api.PingResponse*",
                    "dev.slate.android.api.FlushAck*",
                    "dev.slate.android.api.InteractionsBatchBody*",
                    "dev.slate.android.api.ApiError*",
                )
            }
        }
        verify {
            rule("Business-logic branch coverage must be at least 80%") {
                bound {
                    minValue = 80
                    coverageUnits = CoverageUnit.BRANCH
                }
            }
        }
    }
}

// Wire the coverage gate into `check`.
tasks.named("check") {
    dependsOn(tasks.named("koverVerify"))
}
