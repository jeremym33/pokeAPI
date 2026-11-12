package com.example.pokeapi.client

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/* DTOs reflétant (partiellement) le format JSON de https://pokeapi.co/api/v2 */

@Serializable
data class NamedApiResource(val name: String, val url: String)

/** Ressource sans nom (ex. evolution_chain d'une espèce). */
@Serializable
data class ApiResource(val url: String)

/** Id numérique en fin d'URL PokéAPI : ".../pokemon-species/25/" -> 25 */
fun resourceId(url: String): Int? = url.trimEnd('/').substringAfterLast('/').toIntOrNull()

@Serializable
data class NamedApiResourceList(
    val count: Int,
    val next: String? = null,
    val previous: String? = null,
    val results: List<NamedApiResource>,
)

@Serializable
data class PokeApiPokemon(
    val id: Int,
    val name: String,
    val height: Int,
    val weight: Int,
    @SerialName("base_experience") val baseExperience: Int? = null,
    val sprites: PokeApiSprites = PokeApiSprites(),
    val types: List<PokeApiTypeSlot> = emptyList(),
    val stats: List<PokeApiStat> = emptyList(),
    val abilities: List<PokeApiAbilitySlot> = emptyList(),
    val species: NamedApiResource? = null,
    val moves: List<PokeApiMoveSlot> = emptyList(),
    /** false pour les formes alternatives (régionales, méga…), dont l'id commence à 10001. */
    @SerialName("is_default") val isDefault: Boolean = true,
    val forms: List<NamedApiResource> = emptyList(),
)

/** Forme d'un Pokémon : donne son nom complet traduit (« Raichu d'Alola »). */
@Serializable
data class PokeApiPokemonForm(val names: List<PokeApiLocalizedName> = emptyList())

@Serializable
data class PokeApiVariety(
    @SerialName("is_default") val isDefault: Boolean = false,
    val pokemon: NamedApiResource,
)

@Serializable
data class PokeApiMoveSlot(
    val move: NamedApiResource,
    @SerialName("version_group_details") val versionGroupDetails: List<PokeApiMoveVersionDetail> = emptyList(),
)

@Serializable
data class PokeApiMoveVersionDetail(
    @SerialName("level_learned_at") val levelLearnedAt: Int = 0,
    @SerialName("move_learn_method") val moveLearnMethod: NamedApiResource,
    @SerialName("version_group") val versionGroup: NamedApiResource,
)

@Serializable
data class PokeApiMove(
    val id: Int,
    val name: String,
    val names: List<PokeApiLocalizedName> = emptyList(),
    val type: NamedApiResource? = null,
    @SerialName("damage_class") val damageClass: NamedApiResource? = null,
    val power: Int? = null,
    val accuracy: Int? = null,
    val pp: Int? = null,
    val priority: Int = 0,
    @SerialName("flavor_text_entries") val flavorTextEntries: List<PokeApiFlavorText> = emptyList(),
    val meta: PokeApiMoveMeta? = null,
    /** Statistiques modifiées (Danse Lames : attack +2, Rugissement : attack -1). */
    @SerialName("stat_changes") val statChanges: List<PokeApiStatChange> = emptyList(),
    /** Cible : user, selected-pokemon, all-opponents… */
    val target: NamedApiResource? = null,
)

@Serializable
data class PokeApiStatChange(val change: Int, val stat: NamedApiResource)

/** Effets secondaires d'une attaque : statut infligé (paralysis, burn…) et sa chance (0 = toujours pour une attaque de statut). */
@Serializable
data class PokeApiMoveMeta(
    val ailment: NamedApiResource? = null,
    @SerialName("ailment_chance") val ailmentChance: Int = 0,
    /** % des dégâts infligés récupérés en PV (Vole-Vie : 50) ; négatif = contrecoup (Damoclès : -33). */
    val drain: Int = 0,
    /** % des PV max récupérés (Soin : 50). */
    val healing: Int = 0,
    /** Nombre de coups (Combo-Griffe : 2 à 5, Double Pied : 2), null pour un seul coup. */
    @SerialName("min_hits") val minHits: Int? = null,
    @SerialName("max_hits") val maxHits: Int? = null,
    /** damage-raise (le lanceur change ses statistiques), damage-lower (la cible), net-good-stats… */
    val category: NamedApiResource? = null,
    /** Chance de modifier les statistiques (%), 0 = toujours. */
    @SerialName("stat_chance") val statChance: Int = 0,
    /** Chance d'apeurer la cible (%) : elle ne peut pas attaquer ce tour-ci (Morsure : 30). */
    @SerialName("flinch_chance") val flinchChance: Int = 0,
)

@Serializable
data class PokeApiFlavorText(
    @SerialName("flavor_text") val flavorText: String,
    val language: NamedApiResource,
)

@Serializable
data class PokeApiSpecies(
    val id: Int,
    val name: String,
    val names: List<PokeApiLocalizedName> = emptyList(),
    val generation: NamedApiResource? = null,
    @SerialName("evolution_chain") val evolutionChain: ApiResource? = null,
    /** Pokémon de l'espèce : forme par défaut, formes régionales, méga-évolutions… */
    val varieties: List<PokeApiVariety> = emptyList(),
    /** Facilité de capture, de 3 (légendaires) à 255 (Chenipan…). */
    @SerialName("capture_rate") val captureRate: Int? = null,
    /** Groupes d'œufs (monster, plant… ; « ditto » pour Métamorph, « no-eggs » : pas d'œuf). */
    @SerialName("egg_groups") val eggGroups: List<NamedApiResource> = emptyList(),
    /** Cycles d'éclosion d'un œuf (5 à 120). */
    @SerialName("hatch_counter") val hatchCounter: Int? = null,
)

@Serializable
data class PokeApiLocalizedName(val name: String, val language: NamedApiResource)

@Serializable
data class PokeApiSprites(
    @SerialName("front_default") val frontDefault: String? = null,
    val other: PokeApiOtherSprites? = null,
)

@Serializable
data class PokeApiOtherSprites(
    @SerialName("official-artwork") val officialArtwork: PokeApiArtwork? = null,
)

@Serializable
data class PokeApiArtwork(
    @SerialName("front_default") val frontDefault: String? = null,
)

@Serializable
data class PokeApiTypeSlot(val slot: Int, val type: NamedApiResource)

@Serializable
data class PokeApiStat(
    @SerialName("base_stat") val baseStat: Int,
    val effort: Int = 0,
    val stat: NamedApiResource,
)

@Serializable
data class PokeApiAbilitySlot(
    val ability: NamedApiResource,
    @SerialName("is_hidden") val isHidden: Boolean,
    val slot: Int,
)

/** Ressource avec noms traduits (item, move, location, pokemon-species…). */
@Serializable
data class PokeApiNamedResource(val names: List<PokeApiLocalizedName> = emptyList())

@Serializable
data class PokeApiEvolutionChain(val id: Int, val chain: PokeApiChainLink)

@Serializable
data class PokeApiChainLink(
    val species: NamedApiResource,
    @SerialName("is_baby") val isBaby: Boolean = false,
    @SerialName("evolution_details") val evolutionDetails: List<PokeApiEvolutionDetail> = emptyList(),
    @SerialName("evolves_to") val evolvesTo: List<PokeApiChainLink> = emptyList(),
)

/**
 * Condition d'évolution. Les versions récentes de PokéAPI donnent une entrée par groupe de versions de jeu,
 * la méthode actuelle étant marquée is_default.
 */
@Serializable
data class PokeApiEvolutionDetail(
    val trigger: NamedApiResource? = null,
    val item: NamedApiResource? = null,
    @SerialName("held_item") val heldItem: NamedApiResource? = null,
    @SerialName("known_move") val knownMove: NamedApiResource? = null,
    @SerialName("known_move_type") val knownMoveType: NamedApiResource? = null,
    val location: NamedApiResource? = null,
    val gender: Int? = null,
    @SerialName("min_level") val minLevel: Int? = null,
    @SerialName("min_happiness") val minHappiness: Int? = null,
    @SerialName("min_beauty") val minBeauty: Int? = null,
    @SerialName("min_affection") val minAffection: Int? = null,
    @SerialName("min_steps") val minSteps: Int? = null,
    @SerialName("min_move_count") val minMoveCount: Int? = null,
    @SerialName("min_damage_taken") val minDamageTaken: Int? = null,
    @SerialName("used_move") val usedMove: NamedApiResource? = null,
    @SerialName("needs_overworld_rain") val needsOverworldRain: Boolean = false,
    @SerialName("needs_multiplayer") val needsMultiplayer: Boolean = false,
    @SerialName("near_special_rock") val nearSpecialRock: Boolean = false,
    @SerialName("party_species") val partySpecies: NamedApiResource? = null,
    @SerialName("party_type") val partyType: NamedApiResource? = null,
    @SerialName("relative_physical_stats") val relativePhysicalStats: Int? = null,
    @SerialName("time_of_day") val timeOfDay: String? = null,
    @SerialName("trade_species") val tradeSpecies: NamedApiResource? = null,
    @SerialName("turn_upside_down") val turnUpsideDown: Boolean = false,
    val region: NamedApiResource? = null,
    @SerialName("is_default") val isDefault: Boolean = false,
    @SerialName("condition_expression") val conditionExpression: PokeApiConditionExpression? = null,
    /** Forme qui évolue (« meowth-galar ») ; null = forme par défaut. */
    @SerialName("required_pokemon_form") val requiredPokemonForm: NamedApiResource? = null,
    /** Forme obtenue (« raichu-alola ») ; null = forme par défaut. */
    @SerialName("evolved_pokemon_form") val evolvedPokemonForm: NamedApiResource? = null,
)

@Serializable
data class PokeApiConditionExpression(
    @SerialName("percentage_chance") val percentageChance: Double? = null,
)

@Serializable
data class PokeApiAbility(
    val id: Int,
    val name: String,
    val names: List<PokeApiLocalizedName> = emptyList(),
    val generation: NamedApiResource? = null,
    @SerialName("flavor_text_entries") val flavorTextEntries: List<PokeApiFlavorText> = emptyList(),
    @SerialName("effect_entries") val effectEntries: List<PokeApiEffectEntry> = emptyList(),
    val pokemon: List<PokeApiAbilityPokemon> = emptyList(),
)

@Serializable
data class PokeApiEffectEntry(
    val effect: String = "",
    @SerialName("short_effect") val shortEffect: String = "",
    val language: NamedApiResource,
)

@Serializable
data class PokeApiAbilityPokemon(
    @SerialName("is_hidden") val isHidden: Boolean = false,
    val pokemon: NamedApiResource,
)

/**
 * Nettoie un texte des jeux : retours à la ligne (réels ou « \n » littéraux présents dans certaines données),
 * sauts de page, césures et espaces multiples.
 */
fun cleanGameText(text: String): String =
    text.replace("\\n", " ")
        .replace("\u00ad\n", "")
        .replace(Regex("[\\s\u000c]+"), " ")
        .trim()

/** Comme [cleanGameText], mais garde les paragraphes (lignes vides) des descriptions d'effet. */
fun cleanEffectText(text: String): String =
    text.replace("\\n", "\n")
        .split(Regex("\n\\s*\n"))
        .map { cleanGameText(it) }
        .filter { it.isNotEmpty() }
        .joinToString("\n\n")

@Serializable
data class PokeApiType(
    val name: String,
    @SerialName("damage_relations") val damageRelations: PokeApiDamageRelations = PokeApiDamageRelations(),
)

/** Côté défense uniquement : ce que ce type subit des types attaquants. */
@Serializable
data class PokeApiDamageRelations(
    @SerialName("double_damage_from") val doubleDamageFrom: List<NamedApiResource> = emptyList(),
    @SerialName("half_damage_from") val halfDamageFrom: List<NamedApiResource> = emptyList(),
    @SerialName("no_damage_from") val noDamageFrom: List<NamedApiResource> = emptyList(),
)

@Serializable
data class PokeApiRegion(
    val id: Int,
    val name: String,
    val names: List<PokeApiLocalizedName> = emptyList(),
    val locations: List<NamedApiResource> = emptyList(),
)

@Serializable
data class PokeApiLocation(
    val id: Int,
    val name: String,
    val names: List<PokeApiLocalizedName> = emptyList(),
    val areas: List<NamedApiResource> = emptyList(),
    val region: NamedApiResource? = null,
)

@Serializable
data class PokeApiLocationArea(
    val name: String,
    val names: List<PokeApiLocalizedName> = emptyList(),
    @SerialName("pokemon_encounters") val pokemonEncounters: List<PokeApiPokemonEncounter> = emptyList(),
)

@Serializable
data class PokeApiPokemonEncounter(
    val pokemon: NamedApiResource,
    @SerialName("version_details") val versionDetails: List<PokeApiVersionEncounter> = emptyList(),
)

@Serializable
data class PokeApiVersionEncounter(
    val version: NamedApiResource,
    @SerialName("encounter_details") val encounterDetails: List<PokeApiEncounterDetail> = emptyList(),
)

@Serializable
data class PokeApiEncounterDetail(
    @SerialName("min_level") val minLevel: Int = 0,
    @SerialName("max_level") val maxLevel: Int = 0,
    val chance: Int = 0,
    val method: NamedApiResource,
)

@Serializable
data class PokeApiVersion(
    val name: String,
    val names: List<PokeApiLocalizedName> = emptyList(),
    @SerialName("version_group") val versionGroup: NamedApiResource? = null,
)
