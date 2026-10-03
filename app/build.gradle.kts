import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

/*
 * بيانات مفتاح التوقيع: محفوظة خارج مجلد المشروع تماماً حتى لا تُرفع بالخطأ أبداً.
 * المكان الافتراضي: C:\Users\<اسمك>\.savetek-signing\keystore.properties
 * (ويمكن تغييره بخاصية savetek.signing في ملف gradle.properties الخاص بحسابك).
 * إذا لم يوجد الملف، تُبنى نسخة release بدون توقيع (لا تُثبَّت)، ولا يُكشف أي سر.
 */
val signingPropsFile = (findProperty("savetek.signing") as String?)?.let { file(it) }
    ?: File(System.getProperty("user.home"), ".savetek-signing/keystore.properties")
val keystoreProps = Properties().apply {
    if (signingPropsFile.exists()) signingPropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.nazeeltek.savetek"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.nazeeltek.savetek"
        minSdk = 24
        targetSdk = 36
        versionCode = 9
        versionName = "0.7.1"
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    // نبني ملف APK منفصلاً لكل نوع معالج (أصغر حجماً)، وملفاً شاملاً يعمل على كل الأجهزة
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    buildTypes {
        release {
            // نسخة الإصدار: غير قابلة للتصحيح، مع تصغير الكود وتشويشه (R8) وحذف الموارد غير المستخدمة
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // نُبقي فقط اللغات التي يدعمها التطبيق (المكتبات تضيف عشرات اللغات الأخرى)
    androidResources {
        localeFilters += listOf("en", "ar", "ur", "tr", "fr", "es", "in", "id", "hi")
    }

    // فحص Lint الإضافي عند بناء نسخة release مطفأ لتسريع البناء
    lint {
        checkReleaseBuilds = false
    }

    // ضروري لمكتبة yt-dlp: تحتاج أن تُفك ملفات بايثون و FFmpeg على الجهاز
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // مكتبة التحميل (yt-dlp) و FFmpeg لدمج الصوت والصورة
    val ytdlVersion = "0.18.1"
    implementation("io.github.junkfood02.youtubedl-android:library:$ytdlVersion")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$ytdlVersion")

    // أمان: مكتبة التحميل تجلب Jackson 2.11.1 وفيه ثغرات معروفة (CVE-2020-36518، CVE-2022-42003، CVE-2022-42004).
    // نفرض 2.13.5: آخر إصدار مُصلَح يدعم أندرويد 7 (الإصدارات 2.14+ تتطلب أندرويد 8 فما فوق).
    constraints {
        val jackson = "2.13.5"
        implementation("com.fasterxml.jackson.core:jackson-databind:$jackson") { because("CVE fixes") }
        implementation("com.fasterxml.jackson.core:jackson-core:$jackson") { because("CVE fixes") }
        implementation("com.fasterxml.jackson.core:jackson-annotations:$jackson") { because("CVE fixes") }
    }

    // أساسيات أندرويد
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")

    // Jetpack Compose (الواجهة)
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // المشغّل الداخلي للفيديو والصوت (Media3 ExoPlayer)
    val media3Version = "1.11.1"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")

    // عرض صورة الفيديو المصغّرة من الإنترنت
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")
}
