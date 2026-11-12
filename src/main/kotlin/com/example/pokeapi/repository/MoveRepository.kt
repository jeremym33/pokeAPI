package com.example.pokeapi.repository

import com.example.pokeapi.client.PokeApiMove
import com.example.pokeapi.client.cleanGameText
import com.example.pokeapi.db.MoveTable
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.Instant

data class MoveInfo(
    val name: String,
    val nameFr: String?,
    val type: String?,
    val damageClass: String?,
    val power: Int?,
    val accuracy: Int?,
    val pp: Int?,
    val priority: Int,
    val descriptionFr: String?,
    val ailment: String? = null,
    val ailmentChance: Int = 0,
    val drain: Int = 0,
    val healing: Int = 0,
    val minHits: Int? = null,
    val maxHits: Int? = null,
    /** false : attaque en cache d'avant la gestion des effets (statuts, drain, coups multiples…). */
    val metaLoaded: Boolean = true,
)

/** Version des effets d'attaque enregistrés en base (2 : drain, soin, coups multiples). */
const val MOVE_META_VERSION = 2

class MoveRepository(private val database: Database) {

    private suspend fun <T> dbQuery(block: suspend Transaction.() -> T): T =
        newSuspendedTransaction(Dispatchers.IO, database) { block() }

    suspend fun find(names: Collection<String>): Map<String, MoveInfo> = dbQuery {
        if (names.isEmpty()) return@dbQuery emptyMap()
        MoveTable.selectAll().where { MoveTable.name inList names }.associate {
            it[MoveTable.name] to MoveInfo(
                name = it[MoveTable.name],
                nameFr = it[MoveTable.nameFr],
                type = it[MoveTable.type],
                damageClass = it[MoveTable.damageClass],
                power = it[MoveTable.power],
                accuracy = it[MoveTable.accuracy],
                pp = it[MoveTable.pp],
                priority = it[MoveTable.priority],
                descriptionFr = it[MoveTable.descriptionFr]?.let(::cleanGameText),
                ailment = it[MoveTable.ailment],
                ailmentChance = it[MoveTable.ailmentChance],
                drain = it[MoveTable.drain],
                healing = it[MoveTable.healing],
                minHits = it[MoveTable.minHits],
                maxHits = it[MoveTable.maxHits],
                metaLoaded = it[MoveTable.metaLoaded] && it[MoveTable.metaVersion] >= MOVE_META_VERSION,
            )
        }
    }

    suspend fun save(move: PokeApiMove): MoveInfo {
        val info = MoveInfo(
            name = move.name,
            nameFr = move.names.firstOrNull { it.language.name == "fr" }?.name,
            type = move.type?.name,
            damageClass = move.damageClass?.name,
            power = move.power,
            accuracy = move.accuracy,
            pp = move.pp,
            priority = move.priority,
            // Dernière description française ; les retours à la ligne viennent des écrans des jeux.
            descriptionFr = move.flavorTextEntries.lastOrNull { it.language.name == "fr" }
                ?.flavorText?.let(::cleanGameText),
            ailment = move.meta?.ailment?.name?.takeIf { it != "none" },
            ailmentChance = move.meta?.ailmentChance ?: 0,
            drain = move.meta?.drain ?: 0,
            healing = move.meta?.healing ?: 0,
            minHits = move.meta?.minHits,
            maxHits = move.meta?.maxHits,
        )
        dbQuery {
            MoveTable.upsert {
                it[name] = info.name
                it[nameFr] = info.nameFr
                it[type] = info.type
                it[damageClass] = info.damageClass
                it[power] = info.power
                it[accuracy] = info.accuracy
                it[pp] = info.pp
                it[priority] = info.priority
                it[descriptionFr] = info.descriptionFr
                it[ailment] = info.ailment
                it[ailmentChance] = info.ailmentChance
                it[drain] = info.drain
                it[healing] = info.healing
                it[minHits] = info.minHits
                it[maxHits] = info.maxHits
                it[metaLoaded] = true
                it[metaVersion] = MOVE_META_VERSION
                it[fetchedAt] = Instant.now()
            }
        }
        return info
    }
}
