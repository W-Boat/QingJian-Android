plugins {
  alias(libs.plugins.android.library)
}

android {
  namespace = "com.qingjian.core.sandbox"
  compileSdk { version = release(37) }

  defaultConfig {
    minSdk = 26
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.androidx.javascriptengine)
  implementation(libs.moshi.kotlin)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
}
