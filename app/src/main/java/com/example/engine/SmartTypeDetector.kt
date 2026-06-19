package com.example.engine

object SmartTypeDetector {
    // Weighted keywords dictionary across the specified categories
    private val weights: Map<String, Map<String, Int>> = mapOf(
        "عملي" to mapOf(
            "كود" to 3,
            "برمجة" to 3,
            "تطبيق" to 3,
            "شفرة" to 3,
            "تثبيت" to 2,
            "أمر" to 2,
            "خطوات" to 3,
            "تنفيذ" to 2,
            "طريقة" to 1,
            "إنشاء" to 1,
            "دالة" to 3,
            "فئة" to 3,
            "مكتبة" to 2,
            "تشغيل" to 2,
            "بناء" to 2,
            "تجربة" to 3,
            "معمل" to 2,
            "تمرين" to 2,
            "code" to 3,
            "run" to 2,
            "kotlin" to 4,
            "gradle" to 4,
            "install" to 3
        ),
        "نظري" to mapOf(
            "مفهوم" to 3,
            "تعريف" to 3,
            "شرح" to 3,
            "خصائص" to 2,
            "وظائف" to 2,
            "نظرية" to 3,
            "درس" to 2,
            "مقدمة" to 2,
            "خلاصة" to 2,
            "فلسفة" to 3,
            "تحليل" to 2,
            "دراسة" to 2,
            "مبادئ" to 2,
            "مستند" to 2,
            "أفكار" to 1,
            "تاريخ" to 2,
            "أساسيات" to 2,
            "concept" to 3,
            "theory" to 4,
            "definition" to 3,
            "explanation" to 2
        ),
        "دليل مرئي" to mapOf(
            "تصميم" to 3,
            "واجهة" to 3,
            "ألوان" to 3,
            "حجم" to 1,
            "خط" to 1,
            "تنسيق" to 2,
            "عرض" to 1,
            "صورة" to 3,
            "زر" to 2,
            "تخطيط" to 2,
            "شاشة" to 2,
            "أيقونة" to 3,
            "شكل" to 3,
            "رسم" to 3,
            "جدول" to 2,
            "مخطط" to 2,
            "ui" to 3,
            "ux" to 3,
            "design" to 3,
            "theme" to 3,
            "layout" to 3
        )
    )

    /**
     * Cleans HTML tags, calculates frequency score of weighted keywords, and returns the highest matching category.
     * Returns "غير محدد" if the difference between the top two scores is less than 2.
     */
    fun detectType(text: String): String {
        // 1. Clean HTML tags if any (e.g., <[^>]*>)
        val cleaned = text.replace(Regex("<[^>]*>"), " ")

        val scores = mutableMapOf<String, Int>(
            "عملي" to 0,
            "نظري" to 0,
            "دليل مرئي" to 0
        )

        // 2. Count weighted keyword scores
        for ((category, keywordMap) in weights) {
            var categoryScore = 0
            for ((keyword, weight) in keywordMap) {
                var count = 0
                var index = cleaned.indexOf(keyword, ignoreCase = true)
                while (index != -1) {
                    count++
                    index = cleaned.indexOf(keyword, index + keyword.length, ignoreCase = true)
                }
                categoryScore += count * weight
            }
            scores[category] = categoryScore
        }

        // 3. Find type with highest score and compare with second highest
        val sortedScores = scores.toList().sortedByDescending { it.second }
        val top = sortedScores[0]
        val second = sortedScores[1]
        val diff = top.second - second.second

        // 4. Return result based on threshold
        return if (diff < 2) {
            "غير محدد"
        } else {
            top.first
        }
    }
}
