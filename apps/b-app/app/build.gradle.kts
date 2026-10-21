plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.yourteam.brainchair"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.yourteam.brainchair.receiver"
        minSdk = 24
        targetSdk = 35
        versionCode = 6
        versionName = "0.4.0-unity"
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions.unitTests.all {
        it.systemProperty("brainchair.fixtures", rootProject.file("test-fixtures/stage2").absolutePath)
        it.inputs.dir(rootProject.file("test-fixtures/stage2"))
        it.systemProperty("brainchair.stage27.evidence",providers.gradleProperty("stage27Evidence").orElse(rootProject.file("build/stage27-evidence").absolutePath).get())
        it.systemProperty("brainchair.stage3.evidence",providers.gradleProperty("stage3Evidence").orElse(rootProject.file("build/stage3-evidence").absolutePath).get())
        it.systemProperty("brainchair.stage3.data",providers.gradleProperty("stage3Data").orElse(rootProject.file("output/stage25/data").absolutePath).get())
        it.systemProperty("brainchair.stage3.expected",providers.gradleProperty("stage3Expected").orElse(rootProject.file("docs/cca-window-comparison-windows.csv").absolutePath).get())
    }
}
dependencies { testImplementation("junit:junit:4.13.2") }
