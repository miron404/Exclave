plugins {
    id("com.android.application")
    id("kotlin-parcelize")
    alias(libs.plugins.protobuf)
    alias(libs.plugins.ksp)
    alias(libs.plugins.aboutlibraries)
}

setupApp()

android {
    namespace = "io.nekohasekai.sagernet"
    defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
}

ksp {
    arg("room.incremental", "true")
    arg("room.schemaLocation", "$projectDir/schemas")
}

aboutLibraries {
    offlineMode = true
    collect {
        configPath = file("src/main/aboutlibraries/config")
        includePlatform = true
    }
    export {
        excludeFields.addAll("name", "description", "developers", "funding", "licenses", "organization", "scm", "website", "License")
        prettyPrint = true
    }
    exports {
        create("ossRelease") {
            outputFile = file("src/main/aboutlibraries/aboutlibraries.json")
        }
    }
}

dependencies {
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test.ext:junit-ktx:1.2.1")
    androidTestImplementation(libs.room.testing)
    // Instrumentation loads shared classes from the app APK first. Room's
    // schema serializers need the default interface methods added in 1.8.1;
    // the app otherwise brings in core 1.7.3 through a transitive dependency.
    "debugImplementation"("org.jetbrains.kotlinx:kotlinx-serialization-core:1.8.1")
    implementation(fileTree("libs"))
    implementation(project(":plugin:api"))
    implementation(project(":library:proto-stub"))
    implementation(libs.kotlinx.coroutines.android)
    "ossImplementation"(libs.core.ktx)
    "ossImplementation"(libs.activity.ktx)
    "ossImplementation"(libs.fragment.ktx)
    "ossImplementation"(libs.camera.view)
    "ossImplementation"(libs.camera.lifecycle)
    "ossImplementation"(libs.camera.camera2)
    implementation(libs.swiperefreshlayout)
    "ossImplementation"(libs.appcompat)
    implementation(libs.preference)
    implementation(libs.flexbox)
    "ossImplementation"(libs.work.runtime.ktx)
    "ossImplementation"(libs.work.multiprocess)
    "ossImplementation"(libs.room.runtime)
    "kspOss"(libs.room.compiler)
    "ossImplementation"(libs.room.ktx)
    "ossImplementation"(libs.material)
    implementation(libs.gson)
    implementation(libs.bouncycastle)
    implementation(libs.zxing.core)
    implementation(libs.snakeyaml)
    implementation(libs.material.about.library)
    implementation(libs.process.phoenix)
    implementation(libs.kryo)
    implementation(libs.jini.lib)
    implementation(libs.markwon.core)
    implementation(libs.recyclerview.fastscroll) {
        exclude(group = "androidx.recyclerview")
        exclude(group = "androidx.appcompat")
    }
    implementation(libs.editorkit)
    implementation(libs.editorkit.language.json)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}
