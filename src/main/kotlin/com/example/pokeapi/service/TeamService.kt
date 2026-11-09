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
    suspend fun create(name: String, pokemon: List<String>): Team {
        val cleanName = validateName(name)
        if (pokemon.size > MAX_TEAM_SIZE) {
            throw BadRequestException("Une équipe contient au maximum $MAX_TEAM_SIZE Pokémon")
        }
        val ids = pokemon.map { resolve(it) }
        return repository.create(cleanName, ids)
    }

    suspend fun findAll(): List<Team> = repository.findAll()

    suspend fun find(id: Int): Team = repository.find(id) ?: throw teamNotFound(id)

    suspend fun rename(id: Int, name: String): Team =
        repository.rename(id, validateName(name)) ?: throw teamNotFound(id)

    suspend fun delete(id: Int) {
        if (!repository.delete(id)) throw teamNotFound(id)
    }

    suspend fun addMember(teamId: Int, idOrName: String): Team {
        repository.find(teamId) ?: throw teamNotFound(teamId)
        val pokemonId = resolve(idOrName)
        return when (val result = repository.addMember(teamId, pokemonId)) {
            is AddMemberResult.Added -> result.team
            AddMemberResult.TeamNotFound -> throw teamNotFound(teamId)
            AddMemberResult.TeamFull -> throw BadRequestException("L'équipe est déjà complète ($MAX_TEAM_SIZE Pokémon)")
        }
    }

    suspend fun removeMember(teamId: Int, position: Int): Team =
        repository.removeMember(teamId, position)
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

    private fun teamNotFound(id: Int) = NotFoundException("Équipe $id introuvable")
}
