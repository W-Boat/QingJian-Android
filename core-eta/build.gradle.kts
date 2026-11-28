plugins {
  alias(libs.plugins.android.library)
}

android {
  namespace = "com.qingjian.core.eta"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    minSdk = 26
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
}

dependencies {
  api(project(":core-storage"))
  implementation(libs.androidx.core.ktx)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.okhttp)
  implementation(libs.okhttp.sse)
  implementation(libs.nimbus.jose.jwt)
  implementation(libs.moshi.kotlin)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.org.json)
}
