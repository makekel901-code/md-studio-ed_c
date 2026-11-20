package com.amd.mdstudio.local

import java.io.File

/** Read-only structural diagnostics. It never edits imported project files. */
object ProjectDiagnostics {
    data class Report(val projectRoot: File, val checks: List<Check>) {
        val failures: List<Check> get() = checks.filter { it.level == Level.FAIL }
        val warnings: List<Check> get() = checks.filter { it.level == Level.WARN }
        val summary: String get() = "${failures.size} أخطاء، ${warnings.size} تحذيرات، ${checks.count { it.level == Level.PASS }} نقاط سليمة"
    }
    data class Check(val level: Level, val title: String, val detail: String)
    enum class Level { PASS, WARN, FAIL, INFO }

    fun analyze(project: File): Report {
        val checks = mutableListOf<Check>()
        fun add(level: Level, title: String, detail: String) { checks += Check(level, title, detail) }
        val settings = sequenceOf("settings.gradle.kts", "settings.gradle").map { File(project, it) }.firstOrNull { it.isFile }
        add(if (settings != null) Level.PASS else Level.FAIL, "إعداد Gradle", settings?.name ?: "ملف settings.gradle غير موجود")
        val buildRoot = sequenceOf("build.gradle.kts", "build.gradle").map { File(project, it) }.firstOrNull { it.isFile }
        add(if (buildRoot != null) Level.PASS else Level.WARN, "ملف البناء الرئيسي", buildRoot?.name ?: "غير موجود؛ قد يكون البناء معرفًا بطريقة مختلفة")
        val appGradle = sequenceOf("app/build.gradle.kts", "app/build.gradle").map { File(project, it) }.firstOrNull { it.isFile }
        add(if (appGradle != null) Level.PASS else Level.WARN, "وحدة app", appGradle?.name ?: "لم يتم العثور على app/build.gradle")
        val manifest = File(project, "app/src/main/AndroidManifest.xml")
        add(if (manifest.isFile) Level.PASS else Level.WARN, "Android Manifest", if (manifest.isFile) "موجود" else "غير موجود في المسار التقليدي؛ تحقق من مسارات الوحدات")
        val wrapper = File(project, "gradlew")
        add(if (wrapper.isFile) Level.PASS else Level.WARN, "Gradle Wrapper", if (wrapper.isFile) "موجود" else "غير موجود؛ يلزم Gradle مثبت أو إعداد Wrapper")
        val sourceRoots = project.walkTopDown().onEnter { dir -> dir == project || dir.name !in setOf(".git", ".gradle", "build") }
            .filter { it.isFile && it.extension.lowercase() in setOf("kt", "java") && it.path.contains("/src/") }.toList()
        add(if (sourceRoots.isNotEmpty()) Level.PASS else Level.FAIL, "ملفات المصدر", "${sourceRoots.size} ملف Kotlin/Java")
        val moduleCount = project.walkTopDown().onEnter { dir -> dir == project || dir.name !in setOf(".git", ".gradle", "build") }
            .count { it.isFile && (it.name == "build.gradle" || it.name == "build.gradle.kts") }
        add(if (moduleCount > 0) Level.PASS else Level.FAIL, "وحدات Gradle", "$moduleCount ملف بناء")
        val localProperties = File(project, "local.properties")
        if (localProperties.isFile) {
            val sdkLine = localProperties.readLines().firstOrNull { it.trim().startsWith("sdk.dir=") }
            add(Level.INFO, "إعداد SDK المحلي", if (sdkLine == null) "لا يوجد sdk.dir؛ ستعتمد البيئة على ANDROID_HOME/ANDROID_SDK_ROOT" else "يحتوي sdk.dir؛ يجب التأكد أن المسار مناسب لبيئة البناء الحالية")
        }
        add(Level.INFO, "حدود التقرير", "تحليل بنيوي للقراءة فقط؛ لا يثبت نجاح الترجمة أو تشغيل التطبيق")
        return Report(project.canonicalFile, checks)
    }
}
