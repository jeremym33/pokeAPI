package com.example.pokeapi.repository

import com.example.pokeapi.db.TeamMemberTable
import com.example.pokeapi.db.TeamTable
import com.example.pokeapi.model.Team
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.Instant

const val MAX_TEAM_SIZE = 6

sealed interface AddMemberResult {
    data class Added(val team: Team) : AddMemberResult
    data object TeamNotFound : AddMemberResult
    data object TeamFull : AddMemberResult
}

class TeamRepository(private val database: Database) {

    private suspend fun <T> dbQuery(block: suspend Transaction.() -> T): T =
        newSuspendedTransaction(Dispatchers.IO, database) { block() }

    suspend fun create(ownerId: Int, name: String, pokemonIds: List<Int>): Team = dbQuery {
        val teamId = TeamTable.insertAndGetId {
            it[TeamTable.name] = name
            it[TeamTable.ownerId] = ownerId
            it[createdAt] = Instant.now()
        }.value
        TeamMemberTable.batchInsert(pokemonIds.withIndex()) { (index, pokemonId) ->
            this[TeamMemberTable.teamId] = teamId
            this[TeamMemberTable.position] = index + 1
            this[TeamMemberTable.pokemonId] = pokemonId
        }
        load(teamId)!!
    }

    suspend fun findAll(ownerId: Int): List<Team> = dbQuery {
        TeamTable.selectAll().where { TeamTable.ownerId eq ownerId }.orderBy(TeamTable.id).map { toTeam(it) }
    }

    /** Propriétaire de l'équipe, null si elle n'existe pas (ou n'a pas de propriétaire). */
    suspend fun ownerOf(id: Int): Int? = dbQuery {
        TeamTable.select(TeamTable.ownerId).where { TeamTable.id eq id }.singleOrNull()?.get(TeamTable.ownerId)?.value
    }

    suspend fun find(id: Int): Team? = dbQuery { load(id) }

    suspend fun rename(id: Int, name: String): Team? = dbQuery {
        val updated = TeamTable.update({ TeamTable.id eq id }) { it[TeamTable.name] = name }
        if (updated == 0) null else load(id)
    }

    suspend fun delete(id: Int): Boolean = dbQuery {
        TeamTable.deleteWhere { TeamTable.id eq id } > 0
    }

    suspend fun addMember(teamId: Int, pokemonId: Int): AddMemberResult = dbQuery {
        // Verrouille la ligne de l'équipe pour éviter deux ajouts concurrents sur la même position.
        TeamTable.selectAll().where { TeamTable.id eq teamId }.forUpdate().singleOrNull()
            ?: return@dbQuery AddMemberResult.TeamNotFound

        val used = TeamMemberTable.select(TeamMemberTable.position)
            .where { TeamMemberTable.teamId eq teamId }
            .map { it[TeamMemberTable.position] }
            .toSet()
        val position = (1..MAX_TEAM_SIZE).firstOrNull { it !in used }
            ?: return@dbQuery AddMemberResult.TeamFull

        TeamMemberTable.insert {
            it[TeamMemberTable.teamId] = teamId
            it[TeamMemberTable.position] = position
            it[TeamMemberTable.pokemonId] = pokemonId
        }
        AddMemberResult.Added(load(teamId)!!)
    }

    /** Retire le membre à la position donnée. Retourne l'équipe mise à jour, ou null si l'équipe/position n'existe pas. */
    suspend fun removeMember(teamId: Int, position: Int): Team? = dbQuery {
        val deleted = TeamMemberTable.deleteWhere {
            (TeamMemberTable.teamId eq teamId) and (TeamMemberTable.position eq position)
        }
        if (deleted == 0) null else load(teamId)
    }

    private fun load(id: Int): Team? =
        TeamTable.selectAll().where { TeamTable.id eq id }.singleOrNull()?.let { toTeam(it) }

    private fun toTeam(row: ResultRow): Team {
        val teamId = row[TeamTable.id].value
        val memberIds = TeamMemberTable.selectAll()
            .where { TeamMemberTable.teamId eq teamId }
            .orderBy(TeamMemberTable.position)
            .map { it[TeamMemberTable.pokemonId] }
        return Team(
            id = teamId,
            name = row[TeamTable.name],
            createdAt = row[TeamTable.createdAt].toString(),
            members = summariesFor(memberIds),
        )
    }
}
