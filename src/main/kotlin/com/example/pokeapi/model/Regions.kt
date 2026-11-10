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
    /** Région des formes de Hisui (Légendes Pokémon : Arceus) ; aucune espèce n'y est rattachée par sa génération. */
    HISUI(8, "hisui"),
    PALDEA(9, "paldea");

    companion object {
        /** Région d'origine des espèces d'une génération (la 8e génération donne Galar, pas Hisui). */
        fun ofGeneration(generation: Int): Region? = entries.firstOrNull { it.generation == generation }
        val MAX_GENERATION = entries.maxOf { it.generation }
        /** Régions qui ont des formes régionales. */
        private val FORM_REGIONS = listOf(ALOLA, GALAR, HISUI, PALDEA)

        /**
         * Région d'une forme régionale d'après son nom PokéAPI (« raichu-alola », « tauros-paldea-combat-breed »),
         * null sinon. Exclut les formes qui n'en sont pas vraiment : dominantes (totem), Pikachu à casquette,
         * mode Transe de Darumacho.
         */
        fun ofRegionalForm(speciesName: String, pokemonName: String): Region? {
            val suffix = pokemonName.removePrefix("$speciesName-").takeIf { it != pokemonName } ?: return null
            val parts = suffix.split('-')
            if (parts.any { it in setOf("totem", "cap", "zen") }) return null
            return FORM_REGIONS.firstOrNull { it.apiName == parts.first() }
        }
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
