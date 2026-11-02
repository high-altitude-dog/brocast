import groovy.json.JsonSlurper

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "org.beachsync.proto"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.beachsync.proto"
        minSdk = 31          // Android 12: full-tracking GNSS requests without fallbacks
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug")   // for testing on our own phones, not for distribution
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    // Compose is for screens only: the timing code must not depend on it.
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.3")
}

/*
 * design/tokens.json is the single source for the look, so the app's theme is generated from it on every build
 * and values are never copied by hand. Claude Desktop owns that file; a hand-off that changes it changes the app.
 */
val tokensJson = rootProject.file("../design/tokens.json")
val generatedTokens = layout.buildDirectory.dir("generated/tokens/kotlin")

val generateTokens = tasks.register("generateTokens") {
    inputs.file(tokensJson)
    outputs.dir(generatedTokens)
    doLast {
        @Suppress("UNCHECKED_CAST")
        val root = JsonSlurper().parse(tokensJson) as Map<String, Any?>

        fun camel(path: List<String>) = path.first() + path.drop(1).joinToString("") { it.replaceFirstChar(Char::uppercase) }

        fun argb(v: String): String? {
            val hex = Regex("^#([0-9A-Fa-f]{6})$").find(v)
            if (hex != null) return "0xFF${hex.groupValues[1].uppercase()}"
            val rgba = Regex("""^rgba\((\d+),\s*(\d+),\s*(\d+),\s*([\d.]+)\)$""").find(v) ?: return null
            val (r, g, b, a) = rgba.destructured
            return "0x%02X%02X%02X%02X".format(Math.round(a.toDouble() * 255), r.toInt(), g.toInt(), b.toInt())
        }

        @Suppress("UNCHECKED_CAST")
        val color = root.getValue("color") as Map<String, Any?>
        // A value can name a top-level colour instead of giving one, such as "accent".
        fun resolve(v: String): String? = argb(v) ?: (color[v] as? String)?.let { argb(it) }

        val colors = StringBuilder()
        val marks = StringBuilder()
        fun walkColors(path: List<String>, node: Map<String, Any?>) {
            for ((k, v) in node) {
                val p = path + k
                when (v) {
                    is String -> {
                        if (k == "mark") marks.append("        const val ${camel(path)} = \"${v.substringBefore(' ')}\"\n")
                        else if (k == "color" && path.isNotEmpty()) resolve(v)?.let { colors.append("        const val ${camel(path)} = ${it}L\n") }
                        else resolve(v)?.let { colors.append("        const val ${camel(p)} = ${it}L\n") }
                    }
                    is Map<*, *> -> @Suppress("UNCHECKED_CAST") walkColors(p, v as Map<String, Any?>)
                    else -> {}
                }
            }
        }
        walkColors(emptyList(), color)

        fun numbers(path: List<String>, node: Map<String, Any?>, out: StringBuilder) {
            for ((k, v) in node) {
                when (v) {
                    is Number -> out.append("        const val ${camel(path + k)} = ${v.toDouble()}f\n")
                    is Map<*, *> -> @Suppress("UNCHECKED_CAST") numbers(path + k, v as Map<String, Any?>, out)
                    else -> {}
                }
            }
        }

        fun section(key: String): String {
            val out = StringBuilder()
            @Suppress("UNCHECKED_CAST")
            numbers(emptyList(), root.getValue(key) as Map<String, Any?>, out)
            return out.toString()
        }

        val text = StringBuilder()
        @Suppress("UNCHECKED_CAST")
        for ((name, raw) in root.getValue("type") as Map<String, Map<String, Any?>>) {
            val numeric = raw["font"] == "numeric"
            val tracking = (raw["tracking"] as? String)?.removeSuffix("em")?.toDouble() ?: 0.0
            val tint = (raw["color"] as? String)?.let { resolve(it) }?.let { "${it}L" } ?: "null"
            text.append(
                "        val $name = TextToken(numeric = $numeric, size = ${(raw["size"] as Number).toDouble()}f, " +
                    "weight = ${(raw["weight"] as? Number)?.toInt() ?: 400}, trackingEm = ${tracking}f, color = $tint)\n",
            )
        }

        val file = generatedTokens.get().file("org/beachsync/proto/ui/Tokens.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |// Generated from design/tokens.json by the generateTokens task. Do not edit.
            |package org.beachsync.proto.ui
            |
            |object Tokens {
            |    object Colors {
            |$colors    }
            |    object Marks {
            |$marks    }
            |    object Text {
            |$text    }
            |    object Radius {
            |${section("radius")}    }
            |    object Space {
            |${section("space")}    }
            |    object Stroke {
            |${section("stroke")}    }
            |}
            |
            """.trimMargin(),
        )
    }
}

android.sourceSets["main"].java.srcDir(generatedTokens)
tasks.named("preBuild") { dependsOn(generateTokens) }
