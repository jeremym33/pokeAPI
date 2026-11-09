package com.example.pokeapi.model

import kotlinx.serialization.Serializable

@Serializable
data class Pokemon(
    val id: Int,
    val name: String,
    val height: Int,
    val weight: Int,
    val baseExperience: Int?,
    val spriteUrl: String?,
    val artworkUrl: String?,
    val types: List<String>,
    val stats: Map<String, Int>,
    val abilities: List<Ability>,
)

@Serializable
data class Ability(val name: String, val hidden: Boolean)

@Serializable
data class PokemonSummary(
    val id: Int,
    val name: String,
    val types: List<String>,
    val spriteUrl: String?,
)

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
