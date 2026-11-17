package com.focuslock

import java.util.Locale

object StudyFilter {
    // Real school subjects / exam words. At least ONE of these is required.
    private val strongTerms = listOf(
        "past paper", "science", "history", "maths", "math", "sinhala", "grade 11",
        "grade 10", "grade11", "o/l", "a/l", "algebra", "geometry", "trigonometry",
        "biology", "chemistry", "physics", "geography", "english", "civics",
        "buddhism", "ict", "commerce", "education", "ol", "tamil", "health",
        "model paper", "marking scheme", "important questions",
        "ගණිත", "විද්‍යා", "ඉතිහාස", "සිංහල", "විභාග"
    )

    // Generic words. These alone are NOT enough ("game tutorial" must not pass).
    private val genericTerms = listOf(
        "study", "lesson", "revision", "tutorial", "lecture", "school", "medium"
    )

    // If any of these appear, it is never a study search/video.
    private val blockTerms = listOf(
        "game", "games", "gaming", "gameplay", "gamer", "gamers", "play", "played", "playing", "minecraft", "pubg", "free fire",
        "freefire", "roblox", "fortnite", "gta", "song", "songs", "music", "lyrics",
        "movie", "film", "trailer", "drama", "cartoon", "anime", "funny", "comedy",
        "prank", "vlog", "reaction", "meme", "cricket", "football", "tiktok", "shorts",
        "asmr", "lofi", "live stream", "stream", "mukbang", "unboxing", "review"
    )

    private fun hasTerm(lower: String, term: String): Boolean =
        // Short terms (ol, ict, play, gta...) must match as whole words
        if (term.length <= 4) {
            Regex("(^|[^a-z0-9/])" + Regex.escape(term) + "($|[^a-z0-9/])")
                .containsMatchIn(lower)
        } else lower.contains(term)

    /** True if the text contains entertainment words (used to drop cards from the result feed). */
    fun isBlockedText(q: String): Boolean {
        val lower = q.lowercase(Locale.ROOT)
        return blockTerms.any { hasTerm(lower, it) }
    }

    fun isStudyQuery(q: String): Boolean {
        val lower = q.lowercase(Locale.ROOT)
        if (blockTerms.any { hasTerm(lower, it) }) return false
        return strongTerms.any { hasTerm(lower, it) }
    }
}
