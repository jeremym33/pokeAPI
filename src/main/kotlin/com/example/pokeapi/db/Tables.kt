package com.example.pokeapi.db

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/** Pokémon mis en cache depuis pokeapi.co. L'id est celui de PokéAPI. */
object PokemonTable : Table("pokemon") {
    val id = integer("id")
    val name = varchar("name", 100).uniqueIndex()
    /** Nom français (issu de pokemon-species), null si indisponible. */
    val nameFr = varchar("name_fr", 100).nullable()
    /** Numéro de génération (1 = Kanto … 9 = Paldea), null si l'espèce n'a pas encore été récupérée. */
    val generation = integer("generation").nullable().index()
    val region = varchar("region", 50).nullable().index()
    val height = integer("height")
    val weight = integer("weight")
    val baseExperience = integer("base_experience").nullable()
    val spriteUrl = varchar("sprite_url", 500).nullable()
    val artworkUrl = varchar("artwork_url", 500).nullable()
    /** Chaîne d'évolution PokéAPI de l'espèce (null tant que l'espèce n'a pas été récupérée). */
    val evolutionChainId = integer("evolution_chain_id").nullable()
    /** Somme des statistiques de base, stockée pour pouvoir trier dessus. */
    val statTotal = integer("stat_total").default(0)
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

/** Chaînes d'évolution, stockées telles que renvoyées par PokéAPI (JSON). */
object EvolutionChainTable : Table("evolution_chain") {
    val id = integer("id")
    val data = text("data")
    val fetchedAt = timestamp("fetched_at")

    override val primaryKey = PrimaryKey(id)
}

/** Cache des noms français (objets, capacités, lieux, espèces) utilisés dans les conditions d'évolution. */
object TranslationTable : Table("translation") {
    val kind = varchar("kind", 30)
    val slug = varchar("slug", 100)
    /** null si PokéAPI n'a pas de nom français : on ne redemande pas. */
    val nameFr = varchar("name_fr", 100).nullable()

    override val primaryKey = PrimaryKey(kind, slug)
}
