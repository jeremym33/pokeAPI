package com.example.pokeapi.model

/** Régions principales, indexées par génération (données PokéAPI : generation.main_region). */
enum class Region(val generation: Int, val apiName: String) {
    KANTO(1, "kanto"),
    JOHTO(2, "johto"),
    HOENN(3, "hoenn"),
    SINNOH(4, "sinnoh"),
    UNOVA(5, "unova"),
    KALOS(6, "kalos"),
    ALOLA(7, "alola"),
    GALAR(8, "galar"),
    PALDEA(9, "paldea");

    companion object {
        fun ofGeneration(generation: Int): Region? = entries.firstOrNull { it.generation == generation }
        fun ofName(name: String): Region? = entries.firstOrNull { it.apiName == name.lowercase() }
    }
}

private val romanValues = mapOf('i' to 1, 'v' to 5, 'x' to 10)

/** "generation-iv" -> 4 */
fun parseGeneration(name: String): Int? {
    val roman = name.removePrefix("generation-").lowercase()
    if (roman.isEmpty() || roman.any { it !in romanValues }) return null
    var total = 0
    for (i in roman.indices) {
        val value = romanValues.getValue(roman[i])
        val next = roman.getOrNull(i + 1)?.let { romanValues.getValue(it) } ?: 0
        total += if (value < next) -value else value
    }
    return total
}
