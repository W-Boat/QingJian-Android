plugins {
  alias(libs.plugins.android.library)
}

android {
  namespace = "com.qingjian.core.git"
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
  implementation(libs.eclipse.jgit)
  implementation(libs.moshi.kotlin)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
}
