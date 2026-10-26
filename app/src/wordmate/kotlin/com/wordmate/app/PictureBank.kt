package com.wordmate.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

@Serializable data class BankPicture(
    val id: String, val path: String, val group: String,
    val aliases: List<String>, val source: String, val credit: String
)
fun imageModel(context: Context, path: String): Any? = when {
    path.startsWith("asset:") -> "file:///android_asset/" + path.removePrefix("asset:")
    path.startsWith("media/") -> File(context.filesDir, path)
    else -> null
}
class PictureBank(private val context: Context) {
    val entries: List<BankPicture> by lazy {
        codec.decodeFromString(context.assets.open("distractors/index.json").bufferedReader().use { it.readText() })
    }
    fun exists(path: String) = path.startsWith("media/") && File(context.filesDir, path).isFile
    private fun matches(text: String, alias: String): Boolean =
        if (alias.all { it.code < 128 }) Regex("(?i)(?<![a-z])" + Regex.escape(alias) + "(?![a-z])").containsMatchIn(text)
        else text.contains(alias)
    private fun concepts(card: WordCard): Set<String> = entries.filter { picture ->
        picture.aliases.any { matches(card.spelling + " " + card.meaning, it) }
    }.map { it.id }.toSet()
    private fun broadGroups(card: WordCard): Set<String> {
        val text = card.spelling + " " + card.meaning
        return mapOf("animal" to listOf("animal","animals","动物"), "fruit" to listOf("fruit","fruits","水果"),
            "transport" to listOf("vehicle","transport","交通工具"), "weather" to listOf("weather","天气"),
            "nature" to listOf("nature","自然景物")).filterValues { aliases -> aliases.any { matches(text, it) } }.keys
    }
    private fun meaningTerms(card: WordCard) = card.meaning.split(Regex("[\\s,，;；、/]+"))
        .map { it.trim().removeSuffix("的") }.filter { it.length >= 2 }.toSet()

    suspend fun choices(card: WordCard, others: List<WordCard>): List<ImageOption> = withContext(Dispatchers.IO) {
        if (!exists(card.image)) return@withContext emptyList()
        val correctHash = digest(File(context.filesDir, card.image).readBytes())
        val hashes = mutableSetOf(correctHash)
        val targetConcepts = concepts(card)
        val broad = broadGroups(card)
        val terms = meaningTerms(card)
        val selected = mutableListOf<ImageOption>()
        for (other in others.shuffled()) {
            if (other.id == card.id || !exists(other.image)) continue
            if (terms.intersect(meaningTerms(other)).isNotEmpty()) continue
            val otherConcepts = concepts(other)
            if (targetConcepts.intersect(otherConcepts).isNotEmpty()) continue
            if (entries.any { it.id in otherConcepts && it.group in broad }) continue
            if (!hashes.add(digest(File(context.filesDir, other.image).readBytes()))) continue
            selected += ImageOption("word:" + other.id, other.image)
            if (selected.size == 3) break
        }
        for (entry in entries.shuffled()) {
            if (selected.size == 3) break
            if (entry.id in targetConcepts || entry.group in broad) continue
            if (selected.any { it.id == "bank:" + entry.id }) continue
            selected += ImageOption("bank:" + entry.id, "asset:" + entry.path)
        }
        if (selected.size != 3) emptyList() else (selected + ImageOption("word:" + card.id, card.image)).shuffled()
    }
}
