plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ru.fieldjournal"
    compileSdk = 35
    buildToolsVersion = "35.0.1"
    defaultConfig {
        applicationId = "ru.fieldjournal"
        minSdk = 26
        targetSdk = 35
        versionCode = 19
        versionName = "0.3.1"
    }
    val signingFile = System.getenv("FIELD_JOURNAL_KEYSTORE")
    if (!signingFile.isNullOrBlank()) {
        signingConfigs.getByName("debug") {
            storeFile = file(signingFile)
            storePassword = System.getenv("FIELD_JOURNAL_STORE_PASSWORD").orEmpty().ifBlank { "android" }
            keyAlias = System.getenv("FIELD_JOURNAL_KEY_ALIAS").orEmpty().ifBlank { "androiddebugkey" }
            keyPassword = System.getenv("FIELD_JOURNAL_KEY_PASSWORD").orEmpty().ifBlank { "android" }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions.unitTests {
        isIncludeAndroidResources = true
        all {
            it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
            System.getenv("ROBOLECTRIC_SDK_DIR")?.takeIf(String::isNotBlank)?.let { directory ->
                it.systemProperty("robolectric.dependency.dir", directory)
                it.systemProperty("robolectric.offline", true)
            }
            for (property in listOf("https.proxyHost", "https.proxyPort", "http.proxyHost", "http.proxyPort")) {
                System.getProperty(property)?.let { value -> it.systemProperty(property, value) }
            }
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
