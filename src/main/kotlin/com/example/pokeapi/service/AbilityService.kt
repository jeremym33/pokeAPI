package com.example.pokeapi.service

import com.example.pokeapi.client.PokeApiClient
import com.example.pokeapi.model.AbilityDetail
import com.example.pokeapi.model.AbilityPokemon
import com.example.pokeapi.model.Pokemon
import com.example.pokeapi.repository.AbilityInfo
import com.example.pokeapi.repository.AbilityRepository
import com.example.pokeapi.repository.PokemonRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory

/** Formes alternatives (méga, régionales…) : ids PokéAPI à partir de 10001. */
private const val FIRST_FORM_ID = 10_001

/** Détail des talents (nom et textes en français), mis en cache en base. */
class AbilityService(
    private val abilityRepository: AbilityRepository,
    private val pokemonRepository: PokemonRepository,
    private val pokeApi: PokeApiClient,
) {
    private val log = LoggerFactory.getLogger(AbilityService::class.java)

    /** Ajoute le détail des talents à un Pokémon. Un talent indisponible garde ses informations de base. */
    suspend fun withAbilityDetails(pokemon: Pokemon): Pokemon {
        val details = load(pokemon.abilities.map { it.name })
        return pokemon.copy(
            abilities = pokemon.abilities.map { a ->
                details[a.name]?.let {
                    a.copy(
                        nameFr = it.nameFr,
                        description = it.descriptionFr,
                        effect = it.effect,
                        effectLanguage = it.effectLanguage,
                        generation = it.generation,
                    )
                } ?: a
            }
        )
    }

    /** Fiche d'un talent avec la liste des Pokémon qui l'ont ; null s'il n'existe pas. */
    suspend fun ability(name: String): AbilityDetail? {
        val info = load(listOf(name.lowercase())).values.firstOrNull() ?: return null
        val holders = info.pokemon.filter { it.id < FIRST_FORM_ID }.sortedBy { it.id }
        val summaries = pokemonRepository.summaries(holders.map { it.id }).associateBy { it.id }
        return AbilityDetail(
            name = info.name,
            nameFr = info.nameFr,
            description = info.descriptionFr,
            effect = info.effect,
            effectLanguage = info.effectLanguage,
            generation = info.generation,
            pokemon = holders.map { h ->
                val s = summaries[h.id]
                AbilityPokemon(h.id, h.name, s?.nameFr, h.hidden, s?.spriteUrl)
            },
        )
    }

    private suspend fun load(names: List<String>): Map<String, AbilityInfo> = coroutineScope {
        val cached = abilityRepository.find(names)
        val fetched = names.distinct().filterNot { it in cached }.map { name ->
            async {
                runCatching { pokeApi.getAbility(name)?.let { abilityRepository.save(it) } }
                    .onFailure { log.warn("Talent '{}' indisponible : {}", name, it.message) }
                    .getOrNull()
            }
        }.awaitAll().filterNotNull()
        cached + fetched.associateBy { it.name }
    }
}
