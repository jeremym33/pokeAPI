package com.example.pokeapi.model

import kotlinx.serialization.Serializable

@Serializable
data class Pokemon(
    val id: Int,
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
)

@Serializable
data class Ability(val name: String, val hidden: Boolean)

@Serializable
data class PokemonSummary(
    val id: Int,
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
