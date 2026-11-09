package com.example.pokeapi.db

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/** Pokémon mis en cache depuis pokeapi.co. L'id est celui de PokéAPI. */
object PokemonTable : Table("pokemon") {
    val id = integer("id")
    val name = varchar("name", 100).uniqueIndex()
    val height = integer("height")
    val weight = integer("weight")
    val baseExperience = integer("base_experience").nullable()
    val spriteUrl = varchar("sprite_url", 500).nullable()
    val artworkUrl = varchar("artwork_url", 500).nullable()
    val fetchedAt = timestamp("fetched_at")

    override val primaryKey = PrimaryKey(id)
}

object PokemonTypeTable : Table("pokemon_type") {
    val pokemonId = integer("pokemon_id").references(PokemonTable.id, onDelete = ReferenceOption.CASCADE)
    val slot = integer("slot")
    val typeName = varchar("type_name", 50).index()

    override val primaryKey = PrimaryKey(pokemonId, slot)
}

object PokemonStatTable : Table("pokemon_stat") {
    val pokemonId = integer("pokemon_id").references(PokemonTable.id, onDelete = ReferenceOption.CASCADE)
    val statName = varchar("stat_name", 50)
    val baseStat = integer("base_stat")

    override val primaryKey = PrimaryKey(pokemonId, statName)
}

object PokemonAbilityTable : Table("pokemon_ability") {
    val pokemonId = integer("pokemon_id").references(PokemonTable.id, onDelete = ReferenceOption.CASCADE)
    val slot = integer("slot")
    val abilityName = varchar("ability_name", 100)
    val isHidden = bool("is_hidden")

    override val primaryKey = PrimaryKey(pokemonId, slot)
}

/** Équipes créées par les utilisateurs de l'API (6 Pokémon max). */
object TeamTable : IntIdTable("team") {
    val name = varchar("name", 100)
    val createdAt = timestamp("created_at")
}

object TeamMemberTable : Table("team_member") {
    val teamId = reference("team_id", TeamTable, onDelete = ReferenceOption.CASCADE)
    val position = integer("position")
    val pokemonId = integer("pokemon_id").references(PokemonTable.id, onDelete = ReferenceOption.CASCADE)

    override val primaryKey = PrimaryKey(teamId, position)
}
