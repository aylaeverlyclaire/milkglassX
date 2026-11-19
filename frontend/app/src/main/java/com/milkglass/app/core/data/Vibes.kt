package com.milkglass.app.core.data

val VIBES = listOf("relax", "tense", "hype", "touching")

val GENRE_VIBES: Map<String, Set<String>> = mapOf(
    "slice-of-life" to setOf("relax", "touching"),
    "comedy" to setOf("relax"),
    "music" to setOf("relax", "touching"),
    "fantasy" to setOf("relax", "touching"),
    "drama" to setOf("touching"),
    "mystery" to setOf("tense"),
    "supernatural" to setOf("tense"),
    "action" to setOf("hype", "tense"),
    "adventure" to setOf("hype"),
    "sports" to setOf("hype"),
    "mecha" to setOf("hype"),
    "romance" to setOf("touching", "relax"),
    "sci-fi" to setOf("hype", "tense"),
    "horror" to setOf("tense"),
    "psychological" to setOf("tense"),
    "thriller" to setOf("tense"),
    "mahou-shoujo" to setOf("relax", "hype"),
)

fun vibesOf(genres: List<String>): Set<String> = genres.flatMapTo(linkedSetOf()) { GENRE_VIBES[it].orEmpty() }
