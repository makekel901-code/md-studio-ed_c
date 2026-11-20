package com.amd.mdstudio.local

import com.amd.mdstudio.ai.AiConsumption
import com.amd.mdstudio.ai.AiOperatingMode
import com.amd.mdstudio.ai.AiUsageLedger
import com.amd.mdstudio.ai.GeminiRouter
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** AI repair is opt-in and restricted to existing, failure-related text files. */
class ProjectRepairEngine(private val router: GeminiRouter) {
    data class Change(val path: String, val oldContent: String, val newContent: String)
    data class Draft(val id: String, val summary: String, val changes: List<Change>)

    fun prepare(
        project: File,
        failureLog: String,
        mode: AiOperatingMode,
        consumption: AiConsumption,
        usage: AiUsageLedger
    ): Draft {
        require(project.isDirectory) { "لا يوجد مشروع بناء نشط" }
        require(failureLog.isNotBlank()) { "لا يوجد سجل فشل؛ نفّذ محاولة بناء أولًا" }
        val files = candidateFiles(project, failureLog)
        require(files.isNotEmpty()) { "لم أجد ملفات نصية آمنة مرتبطة بالخطأ لإرسالها للإصلاح" }
        val fileBlock = files.joinToString("\n\n") { (path, file) ->
            "--- FILE: $path ---\n" + file.readText(Charsets.UTF_8).take(90_000)
        }
        val prompt = """
            أنت مهندس Android/Gradle مسؤول عن إصلاح فشل البناء التالي.
            أصلح سبب الفشل المحدد فقط، ولا تعِد تصميم المشروع ولا تغيّر وظائف غير مرتبطة.
            سجل الفشل:
            ${failureLog.takeLast(12_000)}

            الملفات المسموح تعديلها فقط:
            $fileBlock

            أعد JSON صالحًا فقط بهذا الشكل:
            {"summary":"شرح عربي موجز للسبب والإصلاح","files":[{"path":"مسار موجود كما هو","content":"المحتوى الكامل الجديد"}]}
            قواعد صارمة:
            - لا تعدل إلا الملفات المعروضة أعلاه، وبحد أقصى 5 ملفات.
            - لا تنشئ ملفات جديدة ولا تحذف ملفات.
            - لا تغيّر package/applicationId أو التوقيع أو الإصدارات أو الصلاحيات إلا إذا كان ذلك سبب الخطأ المباشر واذكر ذلك بوضوح في summary.
            - لا تضف مفاتيح أو أسرارًا أو مستودعات غير موثوقة.
            - حافظ على كل الوظائف غير المرتبطة بالخطأ.
            - لا تدّع أن البناء نجح؛ أنت تقترح تعديلًا فقط.
            - أعد محتوى كل ملف كاملًا داخل JSON دون Markdown.
        """.trimIndent()
        val response = router.generate(prompt, mode = mode, consumption = consumption, usageLedger = usage)
        check(response.success) { response.message ?: "تعذر الاتصال بمزود الذكاء الاصطناعي" }
        val raw = response.text.orEmpty()
        val start = raw.indexOf('{'); val end = raw.lastIndexOf('}')
        require(start >= 0 && end > start) { "استجابة الإصلاح ليست JSON صالحًا" }
        val json = JSONObject(raw.substring(start, end + 1))
        val allowed = files.associate { it.first to it.second }
        val array = json.optJSONArray("files") ?: JSONArray()
        require(array.length() in 1..5) { "لم ينتج الذكاء الاصطناعي تعديلات محددة" }
        val changes = (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            val path = item.getString("path").replace('\\', '/')
            val original = allowed[path] ?: error("رفض تعديل ملف خارج نطاق الفشل: $path")
            val content = item.getString("content")
            require(content.toByteArray(Charsets.UTF_8).size <= 1_500_000) { "الملف المقترح كبير جدًا: $path" }
            require(content != original.readText(Charsets.UTF_8)) { "المحتوى المقترح لم يغير الملف: $path" }
            Change(path, original.readText(Charsets.UTF_8), content)
        }.distinctBy { it.path }
        return Draft(UUID.randomUUID().toString(), json.optString("summary", "إصلاح موجه لفشل البناء"), changes)
    }

    fun apply(project: File, draft: Draft): File {
        val root = project.canonicalFile
        val checkpoint = File(root, ".mdstudio-repair-backups/${draft.id}").canonicalFile
        require(checkpoint.path.startsWith(File(root, ".mdstudio-repair-backups").canonicalPath + File.separator))
        check(checkpoint.mkdirs()) { "تعذر إنشاء نقطة استعادة" }
        draft.changes.forEach { change ->
            val target = File(root, change.path).canonicalFile
            require(target.path.startsWith(root.path + File.separator) && target.isFile) { "مسار تعديل غير صالح: ${change.path}" }
            require(target.readText(Charsets.UTF_8) == change.oldContent) { "تغير الملف بعد تجهيز مسودة الإصلاح؛ أعد تحليل الخطأ بدل الكتابة فوق تعديل أحدث: ${change.path}" }
            val backup = File(checkpoint, change.path)
            backup.parentFile?.mkdirs()
            target.copyTo(backup, overwrite = true)
        }
        try {
            draft.changes.forEach { change ->
                val target = File(root, change.path).canonicalFile
                val temp = File(target.parentFile, target.name + ".mdstudio-tmp")
                temp.writeText(change.newContent, Charsets.UTF_8)
                if (!temp.renameTo(target)) {
                    temp.delete()
                    target.writeText(change.newContent, Charsets.UTF_8)
                }
            }
        } catch (failure: Throwable) {
            draft.changes.forEach { change ->
                runCatching {
                    val backup = File(checkpoint, change.path)
                    val target = File(root, change.path)
                    if (backup.isFile) { target.parentFile?.mkdirs(); backup.copyTo(target, overwrite = true) }
                }
            }
            throw failure
        }
        return checkpoint
    }

    fun rollback(project: File, checkpoint: File): Int {
        val root = project.canonicalFile
        val backupRoot = File(root, ".mdstudio-repair-backups").canonicalFile
        val canonical = checkpoint.canonicalFile
        require(canonical.path.startsWith(backupRoot.path + File.separator) && canonical.isDirectory)
        var count = 0
        canonical.walkTopDown().filter { it.isFile }.forEach { backup ->
            val relative = backup.relativeTo(canonical)
            val target = File(root, relative.path).canonicalFile
            require(target.path.startsWith(root.path + File.separator))
            target.parentFile?.mkdirs(); backup.copyTo(target, overwrite = true); count++
        }
        return count
    }

    private fun candidateFiles(project: File, log: String): List<Pair<String, File>> {
        val all = project.walkTopDown().onEnter { dir -> dir == project || dir.name !in setOf(".git", ".gradle", "build", ".mdstudio-repair-backups") }
            .filter { file ->
                val name = file.name.lowercase()
                file.isFile && file.length() <= 150_000 && file.extension.lowercase() in setOf("gradle", "kts", "properties", "xml", "java", "kt") &&
                    name !in setOf("local.properties", "secrets.properties", "google-services.json") &&
                    !name.startsWith(".env") && !name.contains("secret") && !name.endsWith(".jks") && !name.endsWith(".keystore") &&
                    runCatching { !Regex("(?i)(api[_-]?key|access[_-]?token|password|secret|storepass|keypass|client_secret)\\s*[:=]").containsMatchIn(file.readText(Charsets.UTF_8)) }.getOrDefault(false)
            }
            .toList()
        val mentioned = all.filter { file ->
            val relative = file.relativeTo(project).invariantSeparatorsPath
            log.contains(relative, ignoreCase = true) || log.contains(file.name, ignoreCase = true)
        }
        val selected = if (mentioned.isNotEmpty()) mentioned else all.filter { file ->
            val path = file.relativeTo(project).invariantSeparatorsPath
            path in setOf("settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts", "gradle.properties", "app/build.gradle", "app/build.gradle.kts", "app/src/main/AndroidManifest.xml")
        }
        return selected.take(5).map { it.relativeTo(project).invariantSeparatorsPath to it }
    }
}
