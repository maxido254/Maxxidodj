import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.example.djapp"
    compileSdk = 34
    ndkVersion = "26.1.10909125"
    defaultConfig {
        applicationId = "com.example.djapp"
        minSdk = 26; targetSdk = 34; versionCode = 1; versionName = "0.1"
        externalNativeBuild { cmake { cppFlags += "-std=c++17"; arguments += "-DANDROID_STL=c++_shared" } }
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    buildFeatures { compose = true; prefab = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs { pickFirsts += "**/libc++_shared.so" } }
    // Keep the bundled AI model uncompressed: faster build, and the app can read its exact size.
    androidResources { noCompress += "onnx" }
}

// ---- Bundle the HT-Demucs model so AI stems work fully offline ------------------------------------------
// The ~166 MB model is downloaded ONCE at build time into src/main/assets (git-ignored) and packed into the APK.
// The phone never needs internet. To use a copy you already have, just put it at that path with that name.
val demucsModel = layout.projectDirectory.file("src/main/assets/htdemucs_fp16.onnx").asFile
val fetchDemucsModel by tasks.registering {
    description = "Downloads the HT-Demucs ONNX model into src/main/assets so it is packed into the APK."
    outputs.file(demucsModel)
    onlyIf { !demucsModel.exists() || demucsModel.length() < 100_000_000L }
    doLast {
        demucsModel.parentFile.mkdirs()
        val part = File(demucsModel.parentFile, demucsModel.name + ".part")
        var lastError: Exception? = null
        for (attempt in 1..3) {
            try {
                val conn = URL("https://huggingface.co/adowu/htdemucs-onnx/resolve/main/htdemucs_fp16weights.onnx")
                    .openConnection() as HttpURLConnection
                conn.connectTimeout = 30_000; conn.readTimeout = 60_000; conn.instanceFollowRedirects = true
                conn.inputStream.use { input -> part.outputStream().use { out -> input.copyTo(out) } }
                check(part.length() > 100_000_000L) { "downloaded file is too small (${part.length()} bytes)" }
                demucsModel.delete()
                check(part.renameTo(demucsModel)) { "could not move model into place" }
                lastError = null
                break
            } catch (e: Exception) {
                lastError = e
                part.delete()
                println("Demucs model download attempt $attempt failed: ${e.message}")
            }
        }
        lastError?.let { throw GradleException("Could not download the Demucs model: ${it.message}", it) }
        val md = MessageDigest.getInstance("SHA-256")
        demucsModel.inputStream().use { i ->
            val b = ByteArray(1 shl 16)
            while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) }
        }
        println("Demucs model ready: ${demucsModel.length()} bytes, sha256=" + md.digest().joinToString("") { "%02x".format(it) })
    }
}
tasks.named("preBuild") { dependsOn(fetchDemucsModel) }
dependencies {
    implementation("com.google.oboe:oboe:1.9.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.0")
}
