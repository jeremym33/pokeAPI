package com.example.pokeapi.service

import com.example.pokeapi.client.PokeApiEvolutionDetail
import com.example.pokeapi.model.Region

/** Catégories de noms à traduire (= chemins PokéAPI). */
enum class TranslationKind(val path: String) {
    ITEM("item"),
    MOVE("move"),
    LOCATION("location"),
    SPECIES("pokemon-species"),
}

/** Traduit un slug PokéAPI ("fire-stone") en nom français ("Pierre Feu"). */
fun interface Translator {
    fun fr(kind: TranslationKind, slug: String): String
}

private val TYPES_FR = mapOf(
    "normal" to "Normal", "fire" to "Feu", "water" to "Eau", "grass" to "Plante", "electric" to "Électrik",
    "ice" to "Glace", "fighting" to "Combat", "poison" to "Poison", "ground" to "Sol", "flying" to "Vol",
    "psychic" to "Psy", "bug" to "Insecte", "rock" to "Roche", "ghost" to "Spectre", "dragon" to "Dragon",
    "dark" to "Ténèbres", "steel" to "Acier", "fairy" to "Fée",
)

private val REGIONS_FR = Region.entries.associate { it.apiName to it.apiName.replaceFirstChar(Char::uppercase) } +
    mapOf("unova" to "Unys", "hisui" to "Hisui")

fun prettySlug(slug: String): String =
    slug.split('-').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

/**
 * Description française d'une condition d'évolution PokéAPI, par ex. :
 * "Niveau 16", "Utiliser Pierre Foudre", "Gagner un niveau, avec un bonheur élevé, de jour",
 * "Échange, en tenant Roche Royale".
 */
fun describeEvolution(detail: PokeApiEvolutionDetail, t: Translator): String {
    val trigger = detail.trigger?.name
    val item = detail.item?.let { t.fr(TranslationKind.ITEM, it.name) }
    val usedMove = detail.usedMove?.let { t.fr(TranslationKind.MOVE, it.name) }
    val times = detail.minMoveCount?.let { " $it fois" } ?: ""

    // Éléments intégrés à la phrase principale, à ne pas répéter dans les précisions
    var usedItem = false
    var usedMoveInBase = false
    var usedDamage = false

    val base = when (trigger) {
        "level-up" -> detail.minLevel?.let { "Niveau $it" } ?: "Gagner un niveau"
        "trade" -> "Échange"
        "use-item" -> { usedItem = true; item?.let { "Utiliser $it" } ?: "Utiliser un objet" }
        "shed" -> "Niveau 20, avec une place libre dans l'équipe et une Poké Ball dans le Sac"
        "spin" -> "Faire tourner le dresseur sur lui-même"
        "tower-of-darkness" -> "Gravir la Tour des Ténèbres"
        "tower-of-waters" -> "Gravir la Tour des Eaux"
        "three-critical-hits" -> "Réussir 3 coups critiques en un seul combat"
        "take-damage" -> {
            usedDamage = true
            "Subir au moins ${detail.minDamageTaken ?: 49} PV de dégâts, puis passer sous l'arche de pierre"
        }
        "recoil-damage" -> {
            usedDamage = true
            "Subir au moins ${detail.minDamageTaken ?: 294} PV de dégâts de contrecoup sans être K.O."
        }
        "use-move" -> { usedMoveInBase = true; usedMove?.let { "Utiliser $it$times" } ?: "Utiliser une capacité$times" }
        "agile-style-move" -> { usedMoveInBase = true; "Utiliser ${usedMove ?: "une capacité"}$times en Style Rapide" }
        "strong-style-move" -> { usedMoveInBase = true; "Utiliser ${usedMove ?: "une capacité"}$times en Style Puissant" }
        "three-defeated-bisharp" -> "Vaincre 3 Scalproie menant un groupe de Scalpion"
        "gimmighoul-coins" -> "Réunir 999 Pièces de Mordudor"
        "other" -> "Condition spéciale"
        null -> "Évolution"
        else -> prettySlug(trigger)
    }

    val details = buildList {
        if (item != null && !usedItem) add("avec $item")
        if (detail.minHappiness != null) add("avec un bonheur élevé")
        if (detail.minAffection != null) add("avec beaucoup d'affection")
        if (detail.minBeauty != null) add("avec une Beauté élevée")
        when (detail.timeOfDay) {
            "day" -> add("de jour")
            "night" -> add("de nuit")
            "dusk" -> add("au crépuscule")
        }
        detail.heldItem?.let { add("en tenant ${t.fr(TranslationKind.ITEM, it.name)}") }
        detail.knownMove?.let { add("en connaissant ${t.fr(TranslationKind.MOVE, it.name)}") }
        detail.knownMoveType?.let { add("en connaissant une capacité de type ${TYPES_FR[it.name] ?: prettySlug(it.name)}") }
        if (usedMove != null && !usedMoveInBase) add("après avoir utilisé $usedMove$times")
        if (detail.minDamageTaken != null && !usedDamage) add("après avoir subi ${detail.minDamageTaken} PV de dégâts")
        detail.minSteps?.let { add("après $it pas hors de sa Poké Ball") }
        detail.location?.let { add("à ${t.fr(TranslationKind.LOCATION, it.name)}") }
        if (detail.nearSpecialRock) add("près d'un rocher spécial")
        if (detail.needsOverworldRain) add("sous la pluie")
        if (detail.turnUpsideDown) add("console à l'envers")
        detail.partySpecies?.let { add("avec ${t.fr(TranslationKind.SPECIES, it.name)} dans l'équipe") }
        detail.partyType?.let { add("avec un Pokémon de type ${TYPES_FR[it.name] ?: prettySlug(it.name)} dans l'équipe") }
        when (detail.relativePhysicalStats) {
            1 -> add("si Attaque > Défense")
            -1 -> add("si Attaque < Défense")
            0 -> add("si Attaque = Défense")
        }
        when (detail.gender) {
            1 -> add("femelle uniquement")
            2 -> add("mâle uniquement")
        }
        detail.tradeSpecies?.let { add("contre ${t.fr(TranslationKind.SPECIES, it.name)}") }
        if (detail.needsMultiplayer) add("en multijoueur (Union)")
        detail.region?.let { add("dans la région ${REGIONS_FR[it.name] ?: prettySlug(it.name)}") }
    }
    val chance = detail.conditionExpression?.percentageChance
        ?.takeIf { it < 100.0 }
        ?.let { " (${formatPercent(it)} % de chance)" } ?: ""

    return (listOf(base) + details).joinToString(", ") + chance
}

private fun formatPercent(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else value.toString().replace('.', ',')

/** Conditions à afficher : la méthode actuelle (is_default) si PokéAPI la précise, sans doublons. */
fun describeEvolutions(details: List<PokeApiEvolutionDetail>, t: Translator): List<String> {
    val current = details.filter { it.isDefault }.ifEmpty { details }
    return current.map { describeEvolution(it, t) }.distinct()
}

/** Slugs à traduire pour une liste de conditions. */
fun slugsToTranslate(details: List<PokeApiEvolutionDetail>): Set<Pair<TranslationKind, String>> = buildSet {
    for (d in details) {
        d.item?.let { add(TranslationKind.ITEM to it.name) }
        d.heldItem?.let { add(TranslationKind.ITEM to it.name) }
        d.knownMove?.let { add(TranslationKind.MOVE to it.name) }
        d.usedMove?.let { add(TranslationKind.MOVE to it.name) }
        d.location?.let { add(TranslationKind.LOCATION to it.name) }
        d.partySpecies?.let { add(TranslationKind.SPECIES to it.name) }
        d.tradeSpecies?.let { add(TranslationKind.SPECIES to it.name) }
    }
}
