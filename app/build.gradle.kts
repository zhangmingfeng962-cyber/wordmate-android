import java.util.Properties

plugins {
 id("com.android.application")
 id("org.jetbrains.kotlin.android")
 id("org.jetbrains.kotlin.plugin.compose")
 id("org.jetbrains.kotlin.plugin.serialization")
 id("com.google.devtools.ksp")
}
android {
 // AGP 8.6's K2 UAST crashes on annotated data-class properties (b/369691626).
 // Keep all lint checks, using the stable K1 analysis frontend.
 experimentalProperties["android.lint.useK2Uast"] = false
 namespace="com.wordmate.app"
 compileSdk=35
 defaultConfig { applicationId="com.wordmate.app"; minSdk=26; targetSdk=35; versionCode=4; versionName="1.2.0"; testInstrumentationRunner="androidx.test.runner.AndroidJUnitRunner" }
 sourceSets {
   getByName("main").java.setSrcDirs(listOf("src/wordmate/kotlin"))
   getByName("test").java.setSrcDirs(listOf("src/wordmateTest/kotlin"))
   getByName("androidTest").java.setSrcDirs(listOf("src/wordmateAndroidTest/kotlin"))
 }
 compileOptions { sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget="17" }
 buildFeatures { compose=true; buildConfig=true }
 val signingPath = System.getenv("WORDMATE_SIGNING_PROPERTIES")
 if (!signingPath.isNullOrBlank()) {
   val signingValues = Properties().apply { file(signingPath).inputStream().use { load(it) } }
   signingConfigs.create("localRelease") {
     storeFile = file(signingValues.getProperty("storeFile"))
     storePassword = signingValues.getProperty("storePassword")
     keyAlias = signingValues.getProperty("keyAlias")
     keyPassword = signingValues.getProperty("keyPassword")
   }
 }
 buildTypes { release { isMinifyEnabled=false; if (!signingPath.isNullOrBlank()) signingConfig=signingConfigs.getByName("localRelease") } }
 if (!signingPath.isNullOrBlank() && providers.gradleProperty("upgradeTestSigning").orNull == "true") {
   buildTypes.getByName("debug").signingConfig = signingConfigs.getByName("localRelease")
 }
 packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
 testOptions { unitTests.isIncludeAndroidResources=true }
}
dependencies {
 implementation(platform("androidx.compose:compose-bom:2024.11.00"))
 implementation("androidx.activity:activity-compose:1.9.3")
 implementation("androidx.core:core-ktx:1.15.0")
 implementation("androidx.compose.ui:ui")
 implementation("androidx.compose.ui:ui-tooling-preview")
 implementation("androidx.compose.material3:material3")
 implementation("androidx.compose.material:material-icons-extended")
 implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
 implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
 implementation("androidx.room:room-runtime:2.6.1")
 implementation("androidx.room:room-ktx:2.6.1")
 ksp("androidx.room:room-compiler:2.6.1")
 implementation("androidx.datastore:datastore-preferences:1.1.1")
 implementation("androidx.work:work-runtime-ktx:2.10.0")
 implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
 implementation("com.squareup.okhttp3:okhttp:4.12.0")
 implementation("io.coil-kt:coil-compose:2.7.0")
 implementation("io.coil-kt:coil-svg:2.7.0")
 implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
 debugImplementation("androidx.compose.ui:ui-tooling")
 testImplementation("junit:junit:4.13.2")
 testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
 testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
 testImplementation("org.robolectric:robolectric:4.14.1")
 testImplementation("androidx.test:core:1.6.1")
 testImplementation("androidx.room:room-testing:2.6.1")
 androidTestImplementation(platform("androidx.compose:compose-bom:2024.11.00"))
 androidTestImplementation("androidx.compose.ui:ui-test-junit4")
 androidTestImplementation("androidx.test.ext:junit:1.3.0")
 androidTestImplementation("androidx.test:core:1.7.0")
 androidTestImplementation("androidx.test:runner:1.7.0")
 androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
 debugImplementation("androidx.compose.ui:ui-test-manifest")
}
