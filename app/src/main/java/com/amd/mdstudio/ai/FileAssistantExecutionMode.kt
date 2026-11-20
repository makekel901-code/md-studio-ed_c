package com.amd.mdstudio.ai

/** Execution ownership is separate from the Normal/Advanced/Professional AI intensity setting. */
enum class FileAssistantExecutionMode(val key: String, val title: String, val description: String) {
    FULL_AI("full_ai", "ذكاء اصطناعي كامل", "الذكاء الاصطناعي يجهز مجموعة الملفات كاملة، ثم تراجعها وتوافق على كتابتها."),
    CENTRAL_BRAIN("central_brain", "العقل المركزي", "الذكاء الاصطناعي يحلل ويوجه، ومحرك الملفات يتولى التحقق من المسارات والكتابة والاستعادة."),
    SHARED("shared", "تنفيذ مشترك", "الذكاء الاصطناعي يقترح الملفات، وتراجع كل ملف وتختار ما توافق على تطبيقه."),
    MANUAL("manual", "يدوي", "تكتب الملفات بنفسك دون استدعاء الذكاء الاصطناعي.");

    companion object { fun from(value: String?): FileAssistantExecutionMode = values().firstOrNull { it.key == value } ?: SHARED }
}
