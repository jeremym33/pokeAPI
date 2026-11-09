package com.example.pokeapi.client

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/* DTOs reflétant (partiellement) le format JSON de https://pokeapi.co/api/v2 */

@Serializable
data class NamedApiResource(val name: String, val url: String)

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
)

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
