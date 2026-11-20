package com.amd.mdstudio.local

import com.amd.mdstudio.ai.AiConsumption
import com.amd.mdstudio.ai.AiOperatingMode
import com.amd.mdstudio.ai.AiUsageLedger
import com.amd.mdstudio.ai.FileAssistantExecutionMode
import com.amd.mdstudio.ai.GeminiRouter
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Isolated AI file workspace. It can draft, preview, write and roll back source files,
 * but intentionally has no build or APK APIs and never touches Android builder projects.
 */
class AiFileAssistantEngine(
    private val filesRoot: File,
    private val router: GeminiRouter
) {
    data class Workspace(val id: String, val name: String, val root: File, val updatedAt: Long)
    data class DraftFile(val path: String, val content: String, val previousContent: String?, val isNew: Boolean)
    data class Draft(val id: String, val summary: String, val files: List<DraftFile>)

    private val allowedExtensions = setOf(
        "kt", "kts", "java", "xml", "gradle", "properties", "md", "txt", "json",
        "yml", "yaml", "toml", "pro", "gitignore", "sh", "html", "css", "js", "ts"
    )
    private val protectedNames = setOf(".git", ".gradle", ".idea", "build", "local.properties", "secrets", "keystore", ".mdstudio-checkpoints", ".mdstudio-ai-workspace.json")

    init { filesRoot.mkdirs() }

    fun createWorkspace(name: String): Workspace {
        val clean = name.trim().replace(Regex("[^\\p{L}\\p{N} _-]"), "").replace(Regex("\\s+"), " ").take(48)
        require(clean.isNotBlank()) { "اكتب اسم مساحة العمل" }
        val id = "ai-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"
        val root = File(filesRoot, id).canonicalFile
        require(root.path.startsWith(filesRoot.canonicalPath + File.separator))
        check(root.mkdirs()) { "تعذر إنشاء مساحة العمل" }
        File(root, ".mdstudio-ai-workspace.json").writeText(
            JSONObject().put("id", id).put("name", clean).put("created_at", System.currentTimeMillis()).toString(2), Charsets.UTF_8
        )
        return Workspace(id, clean, root, root.lastModified())
    }

    fun listWorkspaces(): List<Workspace> = filesRoot.listFiles().orEmpty()
        .filter { it.isDirectory && File(it, ".mdstudio-ai-workspace.json").isFile }
        .mapNotNull { root -> runCatching {
            val meta = JSONObject(File(root, ".mdstudio-ai-workspace.json").readText())
            Workspace(meta.getString("id"), meta.getString("name"), root, root.lastModified())
        }.getOrNull() }.sortedByDescending { it.updatedAt }

    fun draft(
        workspace: Workspace,
        request: String,
        mode: AiOperatingMode,
        consumption: AiConsumption,
        executionMode: FileAssistantExecutionMode,
        usageLedger: AiUsageLedger
    ): Draft {
        require(request.isNotBlank()) { "اكتب وصف التعديل المطلوب" }
        require(executionMode != FileAssistantExecutionMode.MANUAL) { "الوضع اليدوي لا يستدعي الذكاء الاصطناعي" }
        verifyWorkspace(workspace.root)
        val existing = listFiles(workspace.root).take(120)
        val inventory = existing.joinToString("\n") { "- ${it.path} (${it.length()} bytes)" }
        val modeGuidance = when (mode) {
            AiOperatingMode.NORMAL -> "اعمل بأقل عدد استدعاءات وتحليل ممكن، مع الحفاظ على اكتمال الملفات والوظائف المطلوبة."
            AiOperatingMode.ADVANCED -> "حلل الملفات المرتبطة بالطلب والاعتماديات بينها، وقلل التغييرات غير الضرورية."
            AiOperatingMode.PROFESSIONAL -> "تصرف كمهندس مشارك: حلل التأثير، واكتب ملفات كاملة متناسقة، وراجع الاتساق بين الملفات قبل إرجاعها."
        }
        val executionGuidance = when (executionMode) {
            FileAssistantExecutionMode.FULL_AI -> "تولَّ إعداد جميع الملفات المطلوبة بصورة متكاملة، ولا تترك أجزاء ناقصة."
            FileAssistantExecutionMode.CENTRAL_BRAIN -> "حلل المهمة أولًا، ثم حوّلها إلى تغييرات ملفات محددة؛ محرك MD Studio سيتحقق من كل مسار وينفذ الكتابة والاستعادة."
            FileAssistantExecutionMode.SHARED -> "قلل التغييرات إلى الضروري، واجعل كل ملف مستقلًا واضح السبب حتى يراجعه المستخدم ويختار الملفات التي سيطبقها."
            FileAssistantExecutionMode.MANUAL -> error("الوضع اليدوي لا يستخدم التوليد")
        }
        val prompt = """
            أنت مساعد هندسي لكتابة وتعديل ملفات مشروع داخل مساحة عمل مستقلة في MD Studio.
            المطلوب: $request
            طريقة العمل: ${executionMode.title}. $modeGuidance $executionGuidance
            الملفات الموجودة حاليًا:
            ${inventory.ifBlank { "لا توجد ملفات بعد." }}

            أعد JSON صالحًا فقط بالشكل:
            {"summary":"ملخص عربي","files":[{"path":"relative/path.ext","content":"محتوى الملف كاملًا"}]}
            القواعد:
            - أعد الملفات التي يجب إنشاؤها أو تعديلها فقط، مع محتوى كامل لكل ملف.
            - لا تحذف ملفات ولا تنشئ صورًا أو فيديو أو ملفات ثنائية.
            - لا تكتب مفاتيح API أو كلمات مرور أو أسرارًا أو شهادات توقيع.
            - استخدم مسارات نسبية داخل مساحة العمل فقط، ولا تستخدم .. أو مسارات مطلقة.
            - لا تكتب إلى .git أو build أو .gradle أو local.properties أو secrets أو keystore.
            - لا تبدأ بناء APK ولا تدّع تنفيذ البناء؛ هذه المساحة مخصصة للملفات فقط.
            - لا تضع Markdown خارج JSON.
        """.trimIndent()
        val response = router.generate(prompt, mode = mode, consumption = consumption, usageLedger = usageLedger)
        check(response.success) { response.message ?: "فشل إنشاء مسودة الملفات" }
        val raw = response.text.orEmpty()
        val start = raw.indexOf('{'); val end = raw.lastIndexOf('}')
        require(start >= 0 && end > start) { "استجابة الذكاء الاصطناعي ليست JSON صالحًا" }
        val json = JSONObject(raw.substring(start, end + 1))
        val array = json.optJSONArray("files") ?: JSONArray()
        require(array.length() in 1..60) { "لم يتم إرجاع ملفات قابلة للتطبيق" }
        var total = 0L
        val files = (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            val path = item.getString("path").trim().replace('\\', '/')
            validatePath(path)
            val content = item.getString("content")
            val bytes = content.toByteArray(Charsets.UTF_8).size
            require(bytes <= 2 * 1024 * 1024) { "الملف كبير جدًا للمراجعة الآمنة: $path" }
            total += bytes
            require(total <= 24L * 1024 * 1024) { "حجم المسودة الإجمالي يتجاوز سعة المراجعة الحالية" }
            val target = resolve(workspace.root, path)
            require(!target.exists() || target.isFile) { "المسار المطلوب يشير إلى مجلد وليس ملفًا: $path" }
            if (target.isFile) require(target.length() <= 2L * 1024 * 1024) { "الملف الحالي أكبر من حد المعاينة الآمن: $path" }
            val old = if (target.isFile) {
                val oldBytes = target.readBytes()
                require(oldBytes.none { it == 0.toByte() }) { "الملف الحالي ثنائي ولا يمكن تعديله كنص: $path" }
                String(oldBytes, Charsets.UTF_8)
            } else null
            DraftFile(path, content, old, old == null)
        }.distinctBy { it.path }
        require(files.isNotEmpty()) { "المسودة لا تحتوي ملفات" }
        return Draft(UUID.randomUUID().toString(), json.optString("summary").ifBlank { "تم تجهيز ${files.size} ملفًا للمراجعة" }, files)
    }

    /** Writes only after explicit approval and creates a rollback snapshot first. */
    fun apply(workspace: Workspace, draft: Draft): String {
        verifyWorkspace(workspace.root)
        val checkpoint = File(workspace.root, ".mdstudio-checkpoints/${draft.id}").canonicalFile
        val checkpointRoot = File(workspace.root, ".mdstudio-checkpoints").canonicalFile
        require(checkpoint.path.startsWith(checkpointRoot.path + File.separator))
        check(checkpoint.mkdirs()) { "تعذر إنشاء نقطة الاستعادة" }
        val manifest = JSONArray()
        // Validate and snapshot every target before the first mutation, so partial writes remain recoverable.
        draft.files.forEach { item ->
            validatePath(item.path)
            val target = resolve(workspace.root, item.path)
            val existed = target.isFile
            if (existed) {
                val backup = File(checkpoint, item.path).canonicalFile
                require(backup.path.startsWith(checkpoint.path + File.separator))
                backup.parentFile?.mkdirs(); target.copyTo(backup, overwrite = true)
            }
            manifest.put(JSONObject().put("path", item.path).put("existed", existed))
        }
        File(checkpoint, "manifest.json").writeText(manifest.toString(2), Charsets.UTF_8)
        draft.files.forEach { item ->
            val target = resolve(workspace.root, item.path)
            target.parentFile?.mkdirs()
            target.writeText(item.content, Charsets.UTF_8)
        }
        return checkpoint.absolutePath
    }

    fun rollback(workspace: Workspace, checkpointPath: String): Int {
        verifyWorkspace(workspace.root)
        val checkpointRoot = File(workspace.root, ".mdstudio-checkpoints").canonicalFile
        val checkpoint = File(checkpointPath).canonicalFile
        require(checkpoint.path.startsWith(checkpointRoot.path + File.separator) && checkpoint.isDirectory) { "نقطة الاستعادة غير صالحة" }
        val manifest = JSONArray(File(checkpoint, "manifest.json").readText())
        var restored = 0
        for (i in 0 until manifest.length()) {
            val item = manifest.getJSONObject(i)
            val path = item.getString("path")
            val target = resolve(workspace.root, path)
            if (item.getBoolean("existed")) {
                val backup = File(checkpoint, path).canonicalFile
                require(backup.isFile && backup.path.startsWith(checkpoint.path + File.separator))
                target.parentFile?.mkdirs(); backup.copyTo(target, overwrite = true)
            } else target.delete()
            restored++
        }
        return restored
    }

    fun listFiles(root: File): List<File> {
        verifyWorkspace(root)
        return root.walkTopDown().onEnter { dir -> dir == root || (dir.name !in protectedNames && dir.name != ".mdstudio-checkpoints") }
            .filter { it.isFile && it.name != ".mdstudio-ai-workspace.json" }.toList()
    }

    fun readManual(workspace: Workspace, path: String): String {
        verifyWorkspace(workspace.root); validatePath(path)
        val target = resolve(workspace.root, path)
        require(target.isFile) { "الملف غير موجود" }
        require(target.length() <= 2L * 1024 * 1024) { "الملف أكبر من الحد الآمن للمحرر النصي" }
        val bytes = target.readBytes()
        require(bytes.none { it == 0.toByte() }) { "الملف ثنائي ولا يمكن فتحه كنص" }
        return String(bytes, Charsets.UTF_8)
    }

    fun writeManual(workspace: Workspace, path: String, content: String) {
        verifyWorkspace(workspace.root); validatePath(path)
        require(content.toByteArray(Charsets.UTF_8).size <= 2 * 1024 * 1024) { "الملف أكبر من الحد الآمن للمحرر" }
        val target = resolve(workspace.root, path)
        target.parentFile?.mkdirs(); target.writeText(content, Charsets.UTF_8)
    }

    private fun verifyWorkspace(root: File) {
        val canonical = root.canonicalFile
        require(canonical.path.startsWith(filesRoot.canonicalPath + File.separator) && File(canonical, ".mdstudio-ai-workspace.json").isFile) {
            "مساحة العمل غير صالحة أو لا تتبع مساعد الملفات"
        }
    }

    private fun resolve(root: File, path: String): File {
        val canonicalRoot = root.canonicalFile
        val target = File(canonicalRoot, path).canonicalFile
        require(target.path.startsWith(canonicalRoot.path + File.separator)) { "المسار خارج مساحة العمل" }
        return target
    }

    private fun validatePath(path: String) {
        require(path.isNotBlank() && !path.startsWith('/') && !path.contains(':')) { "مسار غير صالح" }
        val parts = path.split('/')
        require(parts.none { it.isBlank() || it == "." || it == ".." || it in protectedNames }) { "المسار يتضمن مجلدًا محميًا" }
        val extension = path.substringAfterLast('.', "").lowercase()
        require(extension in allowedExtensions || path.substringAfterLast('/') in setOf(".gitignore", "Dockerfile", "Makefile")) { "امتداد الملف غير مدعوم في مساعد الملفات" }
    }
}
