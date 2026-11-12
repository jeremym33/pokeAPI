package com.example.pokeapi.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Pokemon(
    val id: Int,
    /** Numéro du Pokédex national (différent de id pour les formes régionales). */
    val speciesId: Int,
    /** Région de la forme régionale (alola, galar, hisui, paldea), null pour la forme de base. */
    val regionalForm: String?,
    val name: String,
    val nameFr: String?,
    val generation: Int?,
    val region: String?,
    val height: Int,
    val weight: Int,
    val baseExperience: Int?,
    val spriteUrl: String?,
    val artworkUrl: String?,
    val types: List<String>,
    val statTotal: Int,
    val stats: Map<String, Int>,
    val abilities: List<Ability>,
    /** Facilité de capture de l'espèce (3 à 255), null si inconnue. */
    val captureRate: Int? = null,
)

@Serializable
data class Ability(
    val name: String,
    val hidden: Boolean,
    val nameFr: String? = null,
    /** Texte du jeu (français). */
    val description: String? = null,
    /** Effet détaillé, en français si disponible (voir effectLanguage). */
    val effect: String? = null,
    val effectLanguage: String? = null,
    /** Génération d'apparition du talent. */
    val generation: Int? = null,
)

@Serializable
data class AbilityPokemon(
    val id: Int,
    val name: String,
    val nameFr: String?,
    val hidden: Boolean,
    val spriteUrl: String?,
)

@Serializable
data class AbilityDetail(
    val name: String,
    val nameFr: String?,
    val description: String?,
    val effect: String?,
    val effectLanguage: String?,
    val generation: Int?,
    /** Pokémon ayant ce talent (formes alternatives exclues), triés par numéro. */
    val pokemon: List<AbilityPokemon>,
)

@Serializable
data class PokemonSummary(
    val id: Int,
    val speciesId: Int,
    val regionalForm: String?,
    val name: String,
    val nameFr: String?,
    val generation: Int?,
    val region: String?,
    val types: List<String>,
    val statTotal: Int,
    val spriteUrl: String?,
    val artworkUrl: String?,
)

/** Champs de tri disponibles pour GET /pokemon (?sort=...). */
enum class PokemonSort(val param: String) {
    ID("id"),
    NAME("name"),
    NAME_FR("nameFr"),
    HEIGHT("height"),
    WEIGHT("weight"),
    BASE_EXPERIENCE("baseExperience"),
    STAT_TOTAL("statTotal");

    companion object {
        fun ofParam(value: String): PokemonSort? = entries.firstOrNull { it.param.equals(value, ignoreCase = true) }
    }
}

/** Critères de recherche du Pokédex. */
data class PokemonQuery(
    val limit: Int = 20,
    val offset: Int = 0,
    /** Le Pokémon doit avoir tous ces types. */
    val types: List<String> = emptyList(),
    val region: String? = null,
    val generation: Int? = null,
    /** Recherche partielle sur le nom anglais ou français. */
    val name: String? = null,
    val sort: PokemonSort = PokemonSort.ID,
    val descending: Boolean = false,
)

@Serializable
data class RegionCount(val name: String, val generation: Int, val count: Long)

@Serializable
data class TypeCount(val name: String, val count: Long)

@Serializable
data class Page<T>(
    val total: Long,
    val limit: Int,
    val offset: Int,
    val items: List<T>,
)

@Serializable
data class ImportResult(
    val requested: Int,
    val imported: Int,
    val alreadyCached: Int,
    val failed: List<String>,
    /** Formes régionales importées en plus (Raichu d'Alola…). */
    val regionalForms: Int = 0,
)

@Serializable
data class Team(
    val id: Int,
    val name: String,
    val createdAt: String,
    val members: List<PokemonSummary>,
)

@Serializable
data class CreateTeamRequest(val name: String, val pokemon: List<String> = emptyList())

@Serializable
data class RenameTeamRequest(val name: String)

@Serializable
data class ErrorResponse(val error: String)

@Serializable
data class HealthResponse(val status: String, val database: String)

@Serializable
data class EvolutionChain(
    /** Id de la chaîne PokéAPI (null si l'espèce n'en a pas). */
    val id: Int?,
    /**
     * Lignées de la famille : la forme de base d'abord, puis les formes régionales qui ont leur propre lignée
     * (Miaouss → Persian, Miaouss d'Alola → Persian d'Alola, Miaouss de Galar → Berserkatt).
     */
    val roots: List<EvolutionNode>,
)

@Serializable
data class EvolutionNode(
    val speciesId: Int,
    /** Nom PokéAPI de la forme (« raichu-alola ») ou de l'espèce. */
    val name: String,
    /** Résumé du Pokémon (null s'il n'a pas pu être récupéré). */
    val pokemon: PokemonSummary?,
    val isBaby: Boolean,
    /** Comment le Pokémon précédent évolue en celui-ci (en français). Plusieurs entrées = plusieurs méthodes possibles. */
    val conditions: List<String>,
    val evolvesTo: List<EvolutionNode>,
)

@Serializable
data class VersionGroup(val name: String, val label: String)

@Serializable
data class LearnedMove(
    val name: String,
    val nameFr: String?,
    val type: String?,
    /** physical, special ou status */
    val damageClass: String?,
    val power: Int?,
    val accuracy: Int?,
    val pp: Int?,
    val priority: Int,
    val description: String?,
    /** level-up, machine, egg, tutor… */
    val method: String,
    val methodLabel: String,
    /** Niveau d'apprentissage (méthode level-up). 0 = à l'évolution. */
    val level: Int?,
    /** Statut infligé (paralysis, burn, poison, sleep, freeze…), null si aucun. */
    val ailment: String? = null,
    /** Chance d'infliger le statut (%) ; 0 pour une attaque de statut = toujours (si elle touche). */
    val ailmentChance: Int = 0,
)

@Serializable
data class PokemonMoves(
    val pokemonId: Int,
    /** Jeu le plus récent où le Pokémon apparaît (source des attaques), null s'il n'en a aucune. */
    val versionGroup: VersionGroup?,
    val moves: List<LearnedMove>,
)

@Serializable
data class TypeMultiplier(
    /** Type attaquant */
    val type: String,
    /** 0, 0.25, 0.5, 1, 2 ou 4 */
    val multiplier: Double,
)

@Serializable
data class AbilityTypeNote(val ability: String, val abilityFr: String?, val note: String)

@Serializable
data class TypeDefenses(
    val pokemonId: Int,
    val types: List<String>,
    /** Les 18 types attaquants, dans l'ordre officiel. */
    val multipliers: List<TypeMultiplier>,
    /** Multiplicateur > 1, du plus fort au plus faible. */
    val weaknesses: List<TypeMultiplier>,
    /** 0 < multiplicateur < 1, de la plus forte résistance à la plus faible. */
    val resistances: List<TypeMultiplier>,
    val immunities: List<String>,
    /** Talents du Pokémon qui modifient ces relations (non pris en compte dans les multiplicateurs). */
    val abilityNotes: List<AbilityTypeNote>,
)

@Serializable
enum class UpdateState {
    @SerialName("idle") IDLE,
    @SerialName("running") RUNNING,
    @SerialName("completed") COMPLETED,
    @SerialName("cancelled") CANCELLED,
    @SerialName("failed") FAILED,
}

/** État de la mise à jour du Pokédex lancée en arrière-plan. */
@Serializable
data class PokedexUpdateStatus(
    val state: UpdateState = UpdateState.IDLE,
    /** missing : ajoute ce qui manque ; all : récupère à nouveau tous les Pokémon. */
    val mode: String? = null,
    /** Nombre d'espèces du Pokédex national à traiter. */
    val total: Int = 0,
    /** Espèces traitées (importées, déjà à jour ou en échec). */
    val processed: Int = 0,
    val imported: Int = 0,
    val alreadyUpToDate: Int = 0,
    val regionalForms: Int = 0,
    /** Noms en échec (50 au plus). */
    val failed: List<String> = emptyList(),
    val failedCount: Int = 0,
    val startedAt: String? = null,
    val finishedAt: String? = null,
    val error: String? = null,
)

@Serializable
enum class LocationCategory {
    @SerialName("city") CITY,
    @SerialName("route") ROUTE,
    @SerialName("nature") NATURE,
    @SerialName("other") OTHER,
}

@Serializable
data class LocationSummary(
    val name: String,
    val nameFr: String?,
    val category: LocationCategory,
    /** Numéro de route (tri des routes), null sinon. */
    val routeNumber: Int?,
    /** false si PokéAPI n'a aucune donnée de rencontre pour ce lieu. */
    val hasEncounters: Boolean,
    /** Précision quand plusieurs lieux ont le même nom (« Ekaeka » : Beachfront, Marina…). */
    val detail: String? = null,
)

@Serializable
data class RegionLocations(
    val name: String,
    val nameFr: String?,
    val locations: List<LocationSummary>,
)

@Serializable
data class GameVersion(val name: String, val label: String)

@Serializable
data class EncounterMethod(
    val method: String,
    val label: String,
    val minLevel: Int,
    val maxLevel: Int,
    /** Somme des chances de rencontre de cette méthode (%), plafonnée à 100. */
    val chance: Int,
)

@Serializable
data class Encounter(
    val version: String,
    val pokemonId: Int,
    val name: String,
    /** Renseignés si le Pokémon est en base. */
    val nameFr: String?,
    val speciesId: Int?,
    val spriteUrl: String?,
    val types: List<String>,
    val methods: List<EncounterMethod>,
)

@Serializable
data class AreaEncounters(
    val name: String,
    /** Nom de la zone (« Manoir de Céladopole ») ; null pour la zone principale. */
    val label: String?,
    val encounters: List<Encounter>,
)

@Serializable
data class LocationDetail(
    val name: String,
    val nameFr: String?,
    val region: String,
    /** Jeux qui ont des rencontres ici, du plus récent au plus ancien. */
    val versions: List<GameVersion>,
    val areas: List<AreaEncounters>,
)
