package com.example.pokeapi.repository

import com.example.pokeapi.db.EvolutionChainTable
import com.example.pokeapi.db.TranslationTable
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.Instant

class EvolutionRepository(private val database: Database) {

    private suspend fun <T> dbQuery(block: suspend Transaction.() -> T): T =
        newSuspendedTransaction(Dispatchers.IO, database) { block() }

    suspend fun findChainJson(id: Int): String? = dbQuery {
        EvolutionChainTable.select(EvolutionChainTable.data)
            .where { EvolutionChainTable.id eq id }
            .singleOrNull()?.get(EvolutionChainTable.data)
    }

    suspend fun saveChainJson(id: Int, json: String): Unit = dbQuery {
        EvolutionChainTable.upsert {
            it[EvolutionChainTable.id] = id
            it[data] = json
            it[fetchedAt] = Instant.now()
        }
    }

    /** Traductions en cache : slug -> nom français (null = pas de traduction connue). Les slugs absents ne sont pas dans la map. */
    suspend fun translations(kind: String, slugs: Collection<String>): Map<String, String?> = dbQuery {
        if (slugs.isEmpty()) return@dbQuery emptyMap()
        TranslationTable.selectAll()
            .where { (TranslationTable.kind eq kind) and (TranslationTable.slug inList slugs) }
            .associate { it[TranslationTable.slug] to it[TranslationTable.nameFr] }
    }

    suspend fun saveTranslation(kind: String, slug: String, nameFr: String?): Unit = dbQuery {
        TranslationTable.upsert {
            it[TranslationTable.kind] = kind
            it[TranslationTable.slug] = slug
            it[TranslationTable.nameFr] = nameFr
        }
    }
}
