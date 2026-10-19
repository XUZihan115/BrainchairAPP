plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.fifteen.eegsender"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.fifteen.eegsender"
        minSdk = 24

        // 刻意停在 35：targetSdk >= 36 会触发 Android 16+ 的「局域网访问保护」，
        // 往 10.x / 192.168.x 这类地址发 UDP 可能被系统拒绝（sendto 返回 EPERM）。
        // 本应用只用局域网 UDP，targetSdk 抬高没有任何收益，故保持 35。
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.activity.ktx)
    testImplementation(libs.junit)
}
