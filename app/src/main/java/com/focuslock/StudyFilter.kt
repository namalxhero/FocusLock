package com.focuslock

import java.util.Locale

object StudyFilter {
    val studyTerms = listOf(
        "study", "lesson", "past paper", "science", "history", "maths",
        "sinhala", "revision", "grade 11", "o/l", "tutorial", "lecture",
        "education", "school", "algebra", "biology", "chemistry", "physics",
        "geography", "english", "civics", "buddhism", "ict", "commerce",
        "ol", "grade11", "a/l", "grade 10", "medium"
    )

    // Short terms (ol, ict, ...) must match as whole words, so "tool"/"predict" don't pass
    fun isStudyQuery(q: String): Boolean {
        val lower = q.lowercase(Locale.ROOT)
        return studyTerms.any { term ->
            if (term.length <= 3) {
                Regex("(^|[^a-z0-9/])" + Regex.escape(term) + "($|[^a-z0-9/])")
                    .containsMatchIn(lower)
            } else lower.contains(term)
        }
    }
}
