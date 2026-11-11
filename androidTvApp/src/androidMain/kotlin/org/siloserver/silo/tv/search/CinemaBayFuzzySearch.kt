package org.siloserver.silo.tv.search

import org.siloserver.silo.model.catalog.BrowseItem
import kotlin.math.abs
import kotlin.math.min

/** Exact Levenshtein distance is required by the BK-tree's triangle inequality. */
internal fun cinemaBayDistance(a: String, b: String): Int {
    if (a == b) return 0
    var previous = IntArray(b.length + 1) { it }
    for (i in a.indices) {
        val current = IntArray(b.length + 1); current[0] = i + 1
        for (j in b.indices) current[j + 1] = min(min(current[j] + 1, previous[j + 1] + 1), previous[j] + if (a[i] == b[j]) 0 else 1)
        previous = current
    }
    return previous[b.length]
}

/** Vocabulary only, never whole poster payloads; one tree per verified profile snapshot. */
internal class CinemaBayVocabulary {
    private class Node(val word: String, val children: MutableMap<Int, Node> = mutableMapOf())
    private var root: Node? = null
    fun add(word: String) {
        if (word.length !in 3..48 || word.all { it.isDigit() }) return
        var node = root ?: run { root = Node(word); return }
        while (true) {
            val distance = cinemaBayDistance(word, node.word)
            if (distance == 0) return
            val child = node.children[distance]
            if (child == null) { node.children[distance] = Node(word); return }
            node = child
        }
    }
    fun alternatives(word: String): List<String> {
        if (word.length < 3 || word.length > 48 || word.all { it.isDigit() }) return listOf(word)
        val tolerance = if (word.length >= 5) 2 else 1
        val found = mutableListOf<Pair<String, Int>>()
        val stack = java.util.ArrayDeque<Node>(); root?.let(stack::add)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast(); val distance = cinemaBayDistance(word, node.word)
            if (distance <= tolerance) found += node.word to distance
            node.children.forEach { (edge, child) -> if (edge in (distance-tolerance)..(distance+tolerance)) stack.add(child) }
        }
        // A real library has many same-length neighbours (hard/hare/hart/mary,
        // poker/power/pater). Six candidates could discard Harry/Potter entirely.
        // Keep a bounded wider set; whole-title scoring ranks the final matches.
        return (listOf(word) + found.sortedWith(compareBy<Pair<String, Int>> { it.second }.thenBy { it.first }).take(24).map { it.first }).distinct()
    }
}

internal fun cinemaBaySearchScore(item: BrowseItem, query: String): Double {
    val title = CinemaBaySearchText.normalize(item.title)
    val wanted = CinemaBaySearchText.normalize(query)
    if (wanted.isBlank()) return 0.0
    if (title == wanted || title.replace(" ", "") == wanted.replace(" ", "")) return 1000.0
    if (title.startsWith(wanted)) return 900.0 - (title.length-wanted.length).coerceAtMost(50)
    if (title.contains(wanted)) return 800.0
    val compactTitle=title.replace(" ", "");val compactQuery=wanted.replace(" ", "")
    if (compactQuery.length >= 5 && abs(compactTitle.length-compactQuery.length)<=2) {
        val distance=cinemaBayDistance(compactQuery,compactTitle)
        if(distance<=2)return 750.0-distance*20
    }
    val words = title.split(' ')
    return CinemaBaySearchText.tokens(wanted).sumOf { token ->
        words.maxOfOrNull { word -> when {
            word == token -> 100.0
            word.startsWith(token) -> 85.0
            else -> (70.0 - cinemaBayDistance(token, word).coerceAtMost(5) * 20.0).coerceAtLeast(0.0)
        } } ?: 0.0
    }
}
