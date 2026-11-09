package com.example.pokeapi.repository

import com.example.pokeapi.client.PokeApiPokemon
import com.example.pokeapi.client.PokeApiSpecies
import com.example.pokeapi.client.resourceId
import com.example.pokeapi.db.PokemonAbilityTable
import com.example.pokeapi.db.PokemonStatTable
import com.example.pokeapi.db.PokemonTable
import com.example.pokeapi.db.PokemonTypeTable
import com.example.pokeapi.model.*
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

    /** Noms déjà en base avec leurs données d'espèce (région) : inutile de les réimporter. */
    suspend fun completeNames(names: Collection<String>): Set<String> = dbQuery {
        if (names.isEmpty()) return@dbQuery emptySet()
        PokemonTable.select(PokemonTable.name)
            .where { (PokemonTable.name inList names) and PokemonTable.region.isNotNull() }
            .map { it[PokemonTable.name] }
            .toSet()
    }

    /** Insère ou met à jour un Pokémon et ses types / stats / talents. */
    suspend fun save(dto: PokeApiPokemon, species: PokeApiSpecies?): Unit = dbQuery {
        val generationNumber = species?.generation?.name?.let { parseGeneration(it) }
        PokemonTable.upsert {
            it[id] = dto.id
            it[name] = dto.name
            it[nameFr] = species?.names?.firstOrNull { n -> n.language.name == "fr" }?.name
            it[generation] = generationNumber
            it[region] = generationNumber?.let { g -> Region.ofGeneration(g)?.apiName }
            it[height] = dto.height
            it[weight] = dto.weight
            it[baseExperience] = dto.baseExperience
            it[spriteUrl] = dto.sprites.frontDefault
            it[artworkUrl] = dto.sprites.other?.officialArtwork?.frontDefault
            it[evolutionChainId] = species?.evolutionChain?.url?.let { url -> resourceId(url) }
            it[statTotal] = dto.stats.distinctBy { s -> s.stat.name }.sumOf { s -> s.baseStat }
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

    /** Pokédex : liste paginée filtrée (types, région, génération, nom) et triée. */
    suspend fun list(query: PokemonQuery): Page<PokemonSummary> = dbQuery {
        var condition: Op<Boolean> = Op.TRUE
        for (type in query.types.map { it.lowercase() }.distinct()) {
            condition = condition and (PokemonTable.id inSubQuery
                PokemonTypeTable.select(PokemonTypeTable.pokemonId)
                    .where { PokemonTypeTable.typeName eq type })
        }
        query.region?.let { condition = condition and (PokemonTable.region eq it.lowercase()) }
        query.generation?.let { condition = condition and (PokemonTable.generation eq it) }
        if (!query.name.isNullOrBlank()) {
            val pattern = "%${query.name.trim().lowercase().escapeLike()}%"
            condition = condition and (
                (PokemonTable.name like pattern) or (PokemonTable.nameFr.lowerCase() like pattern)
            )
        }

        val sortColumn: Expression<*> = when (query.sort) {
            PokemonSort.ID -> PokemonTable.id
            PokemonSort.NAME -> PokemonTable.name
            PokemonSort.NAME_FR -> Coalesce(PokemonTable.nameFr, PokemonTable.name)
            PokemonSort.HEIGHT -> PokemonTable.height
            PokemonSort.WEIGHT -> PokemonTable.weight
            PokemonSort.BASE_EXPERIENCE -> PokemonTable.baseExperience
            PokemonSort.STAT_TOTAL -> PokemonTable.statTotal
        }
        val order = if (query.descending) SortOrder.DESC_NULLS_LAST else SortOrder.ASC_NULLS_LAST

        val total = PokemonTable.selectAll().where(condition).count()
        val ids = PokemonTable.select(PokemonTable.id)
            .where(condition)
            // L'id sert de second critère pour un ordre stable entre les pages.
            .orderBy(sortColumn to order, PokemonTable.id to SortOrder.ASC)
            .limit(query.limit).offset(query.offset.toLong())
            .map { it[PokemonTable.id] }

        Page(total = total, limit = query.limit, offset = query.offset, items = summariesFor(ids))
    }

    suspend fun evolutionChainId(pokemonId: Int): Int? = dbQuery {
        PokemonTable.select(PokemonTable.evolutionChainId)
            .where { PokemonTable.id eq pokemonId }
            .singleOrNull()?.get(PokemonTable.evolutionChainId)
    }

    suspend fun summaries(ids: List<Int>): List<PokemonSummary> = dbQuery { summariesFor(ids) }

    /** Régions présentes en base avec leur nombre de Pokémon. */
    suspend fun regions(): List<RegionCount> = dbQuery {
        val count = PokemonTable.id.count()
        val counts = PokemonTable.select(PokemonTable.region, count)
            .where { PokemonTable.region.isNotNull() }
            .groupBy(PokemonTable.region)
            .associate { it[PokemonTable.region]!! to it[count] }
        Region.entries.map { RegionCount(it.apiName, it.generation, counts[it.apiName] ?: 0) }
    }

    /** Types présents en base avec leur nombre de Pokémon. */
    suspend fun types(): List<TypeCount> = dbQuery {
        val count = PokemonTypeTable.pokemonId.count()
        PokemonTypeTable.select(PokemonTypeTable.typeName, count)
            .groupBy(PokemonTypeTable.typeName)
            .orderBy(PokemonTypeTable.typeName)
            .map { TypeCount(it[PokemonTypeTable.typeName], it[count]) }
    }

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
            nameFr = row[PokemonTable.nameFr],
            generation = row[PokemonTable.generation],
            region = row[PokemonTable.region],
            height = row[PokemonTable.height],
            weight = row[PokemonTable.weight],
            baseExperience = row[PokemonTable.baseExperience],
            spriteUrl = row[PokemonTable.spriteUrl],
            artworkUrl = row[PokemonTable.artworkUrl],
            types = types,
            statTotal = row[PokemonTable.statTotal],
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
                nameFr = row[PokemonTable.nameFr],
                generation = row[PokemonTable.generation],
                region = row[PokemonTable.region],
                types = typesById[id].orEmpty(),
                statTotal = row[PokemonTable.statTotal],
                spriteUrl = row[PokemonTable.spriteUrl],
                artworkUrl = row[PokemonTable.artworkUrl],
            )
        }
    }
}
