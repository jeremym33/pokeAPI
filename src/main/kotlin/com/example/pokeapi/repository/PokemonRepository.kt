package com.example.pokeapi.repository

import com.example.pokeapi.client.PokeApiPokemon
import com.example.pokeapi.db.PokemonAbilityTable
import com.example.pokeapi.db.PokemonStatTable
import com.example.pokeapi.db.PokemonTable
import com.example.pokeapi.db.PokemonTypeTable
import com.example.pokeapi.model.Ability
import com.example.pokeapi.model.Page
import com.example.pokeapi.model.Pokemon
import com.example.pokeapi.model.PokemonSummary
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inSubQuery
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.Instant

class PokemonRepository(private val database: Database) {

    private suspend fun <T> dbQuery(block: suspend Transaction.() -> T): T =
        newSuspendedTransaction(Dispatchers.IO, database) { block() }

    /** Recherche par id numérique ou par nom (insensible à la casse). */
    suspend fun findByIdOrName(idOrName: String): Pokemon? = dbQuery {
        val condition = idOrName.toIntOrNull()
            ?.let { PokemonTable.id eq it }
            ?: (PokemonTable.name eq idOrName.lowercase())
        PokemonTable.selectAll().where(condition).singleOrNull()?.let { loadDetails(it) }
    }

    suspend fun existingNames(names: Collection<String>): Set<String> = dbQuery {
        if (names.isEmpty()) return@dbQuery emptySet()
        PokemonTable.select(PokemonTable.name)
            .where { PokemonTable.name inList names }
            .map { it[PokemonTable.name] }
            .toSet()
    }

    /** Insère ou met à jour un Pokémon et ses types / stats / talents. */
    suspend fun save(dto: PokeApiPokemon): Unit = dbQuery {
        PokemonTable.upsert {
            it[id] = dto.id
            it[name] = dto.name
            it[height] = dto.height
            it[weight] = dto.weight
            it[baseExperience] = dto.baseExperience
            it[spriteUrl] = dto.sprites.frontDefault
            it[artworkUrl] = dto.sprites.other?.officialArtwork?.frontDefault
            it[fetchedAt] = Instant.now()
        }

        PokemonTypeTable.deleteWhere { pokemonId eq dto.id }
        PokemonTypeTable.batchInsert(dto.types) { t ->
            this[PokemonTypeTable.pokemonId] = dto.id
            this[PokemonTypeTable.slot] = t.slot
            this[PokemonTypeTable.typeName] = t.type.name
        }

        PokemonStatTable.deleteWhere { pokemonId eq dto.id }
        PokemonStatTable.batchInsert(dto.stats.distinctBy { it.stat.name }) { s ->
            this[PokemonStatTable.pokemonId] = dto.id
            this[PokemonStatTable.statName] = s.stat.name
            this[PokemonStatTable.baseStat] = s.baseStat
        }

        PokemonAbilityTable.deleteWhere { pokemonId eq dto.id }
        PokemonAbilityTable.batchInsert(dto.abilities.distinctBy { it.slot }) { a ->
            this[PokemonAbilityTable.pokemonId] = dto.id
            this[PokemonAbilityTable.slot] = a.slot
            this[PokemonAbilityTable.abilityName] = a.ability.name
            this[PokemonAbilityTable.isHidden] = a.isHidden
        }
    }

    /** Liste paginée des Pokémon en cache, filtrable par type et par nom (contient). */
    suspend fun list(limit: Int, offset: Int, type: String?, nameContains: String?): Page<PokemonSummary> = dbQuery {
        var condition: Op<Boolean> = Op.TRUE
        if (!type.isNullOrBlank()) {
            condition = condition and (PokemonTable.id inSubQuery
                PokemonTypeTable.select(PokemonTypeTable.pokemonId)
                    .where { PokemonTypeTable.typeName eq type.lowercase() })
        }
        if (!nameContains.isNullOrBlank()) {
            condition = condition and (PokemonTable.name like "%${nameContains.lowercase().escapeLike()}%")
        }

        val total = PokemonTable.selectAll().where(condition).count()
        val ids = PokemonTable.select(PokemonTable.id)
            .where(condition)
            .orderBy(PokemonTable.id)
            .limit(limit).offset(offset.toLong())
            .map { it[PokemonTable.id] }

        Page(total = total, limit = limit, offset = offset, items = summariesFor(ids))
    }

    suspend fun count(): Long = dbQuery { PokemonTable.selectAll().count() }

    private fun loadDetails(row: ResultRow): Pokemon {
        val id = row[PokemonTable.id]
        val types = PokemonTypeTable.selectAll()
            .where { PokemonTypeTable.pokemonId eq id }
            .orderBy(PokemonTypeTable.slot)
            .map { it[PokemonTypeTable.typeName] }
        val stats = PokemonStatTable.selectAll()
            .where { PokemonStatTable.pokemonId eq id }
            .associate { it[PokemonStatTable.statName] to it[PokemonStatTable.baseStat] }
        val abilities = PokemonAbilityTable.selectAll()
            .where { PokemonAbilityTable.pokemonId eq id }
            .orderBy(PokemonAbilityTable.slot)
            .map { Ability(it[PokemonAbilityTable.abilityName], it[PokemonAbilityTable.isHidden]) }

        return Pokemon(
            id = id,
            name = row[PokemonTable.name],
            height = row[PokemonTable.height],
            weight = row[PokemonTable.weight],
            baseExperience = row[PokemonTable.baseExperience],
            spriteUrl = row[PokemonTable.spriteUrl],
            artworkUrl = row[PokemonTable.artworkUrl],
            types = types,
            stats = stats,
            abilities = abilities,
        )
    }
}

private fun String.escapeLike(): String =
    replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

/** Résumés de Pokémon (dans l'ordre des ids fournis). À appeler dans une transaction. */
internal fun summariesFor(ids: List<Int>): List<PokemonSummary> {
    if (ids.isEmpty()) return emptyList()
    val distinct = ids.distinct()
    val typesById = PokemonTypeTable.selectAll()
        .where { PokemonTypeTable.pokemonId inList distinct }
        .orderBy(PokemonTypeTable.slot)
        .groupBy({ it[PokemonTypeTable.pokemonId] }, { it[PokemonTypeTable.typeName] })
    val rowsById = PokemonTable.selectAll()
        .where { PokemonTable.id inList distinct }
        .associateBy { it[PokemonTable.id] }
    return ids.mapNotNull { id ->
        rowsById[id]?.let { row ->
            PokemonSummary(
                id = id,
                name = row[PokemonTable.name],
                types = typesById[id].orEmpty(),
                spriteUrl = row[PokemonTable.spriteUrl],
            )
        }
    }
}
