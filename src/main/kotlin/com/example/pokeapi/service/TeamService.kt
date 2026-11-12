package com.example.pokeapi.service

import com.example.pokeapi.model.Team
import com.example.pokeapi.repository.AddMemberResult
import com.example.pokeapi.repository.MAX_TEAM_SIZE
import com.example.pokeapi.repository.TeamRepository
import io.ktor.server.plugins.*

class TeamService(
    private val repository: TeamRepository,
    private val pokemonService: PokemonService,
) {
    suspend fun create(ownerId: Int, name: String, pokemon: List<String>): Team {
        val cleanName = validateName(name)
        if (pokemon.size > MAX_TEAM_SIZE) {
            throw BadRequestException("Une équipe contient au maximum $MAX_TEAM_SIZE Pokémon")
        }
        val ids = pokemon.map { resolve(it) }
        return repository.create(ownerId, cleanName, ids)
    }

    suspend fun findAll(ownerId: Int): List<Team> = repository.findAll(ownerId)

    suspend fun find(ownerId: Int, id: Int): Team {
        checkOwner(ownerId, id)
        return repository.find(id) ?: throw teamNotFound(id)
    }

    suspend fun rename(ownerId: Int, id: Int, name: String): Team {
        checkOwner(ownerId, id)
        return repository.rename(id, validateName(name)) ?: throw teamNotFound(id)
    }

    suspend fun delete(ownerId: Int, id: Int) {
        checkOwner(ownerId, id)
        if (!repository.delete(id)) throw teamNotFound(id)
    }

    suspend fun addMember(ownerId: Int, teamId: Int, idOrName: String): Team {
        checkOwner(ownerId, teamId)
        repository.find(teamId) ?: throw teamNotFound(teamId)
        val pokemonId = resolve(idOrName)
        return when (val result = repository.addMember(teamId, pokemonId)) {
            is AddMemberResult.Added -> result.team
            AddMemberResult.TeamNotFound -> throw teamNotFound(teamId)
            AddMemberResult.TeamFull -> throw BadRequestException("L'équipe est déjà complète ($MAX_TEAM_SIZE Pokémon)")
        }
    }

    suspend fun removeMember(ownerId: Int, teamId: Int, position: Int): Team =
        checkOwner(ownerId, teamId).let { repository.removeMember(teamId, position) }
            ?: throw NotFoundException("Aucun Pokémon à la position $position dans l'équipe $teamId")

    private suspend fun resolve(idOrName: String): Int =
        pokemonService.getPokemon(idOrName)?.id
            ?: throw BadRequestException("Pokémon inconnu : '$idOrName'")

    private fun validateName(name: String): String {
        val clean = name.trim()
        if (clean.isEmpty() || clean.length > 100) {
            throw BadRequestException("Le nom de l'équipe doit contenir entre 1 et 100 caractères")
        }
        return clean
    }

    /** L'équipe d'un autre compte est traitée comme inexistante (404) : on ne révèle pas son existence. */
    private suspend fun checkOwner(ownerId: Int, teamId: Int) {
        if (repository.ownerOf(teamId) != ownerId) throw teamNotFound(teamId)
    }

    private fun teamNotFound(id: Int) = NotFoundException("Équipe $id introuvable")
}
