package com.example.pokeapi.service

import com.example.pokeapi.client.PokeApiClient
import com.example.pokeapi.db.TypeRelationTable
import com.example.pokeapi.model.AbilityTypeNote
import com.example.pokeapi.model.TypeDefenses
import com.example.pokeapi.model.TypeMultiplier
import com.example.pokeapi.repository.AbilityRepository
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.upsert
import java.time.Instant

/** Les 18 types, dans l'ordre officiel. */
val ATTACKING_TYPES = listOf(
    "normal", "fire", "water", "electric", "grass", "ice", "fighting", "poison", "ground",
    "flying", "psychic", "bug", "rock", "ghost", "dragon", "dark", "steel", "fairy",
)

/** Dégâts reçus par un type défensif : type attaquant -> multiplicateur (absent = ×1). */
data class DefensiveRelations(val doubleFrom: Set<String>, val halfFrom: Set<String>, val noFrom: Set<String>) {
    fun multiplierFrom(attacking: String): Double = when (attacking) {
        in noFrom -> 0.0
        in doubleFrom -> 2.0
        in halfFrom -> 0.5
        else -> 1.0
    }
}

/** Multiplicateur de chaque type attaquant contre une combinaison de types (produit des facteurs). */
fun computeMultipliers(defending: List<DefensiveRelations>): List<TypeMultiplier> =
    ATTACKING_TYPES.map { attacking ->
        TypeMultiplier(attacking, defending.fold(1.0) { acc, rel -> acc * rel.multiplierFrom(attacking) })
    }

/**
 * Talents qui modifient les faiblesses et résistances (effet principal, en français).
 * Non appliqués aux multiplicateurs : ils dépendent du talent actif du Pokémon.
 */
private val ABILITY_TYPE_EFFECTS = mapOf(
    "levitate" to "immunisé contre le type Sol",
    "earth-eater" to "immunisé contre le type Sol (et récupère des PV)",
    "flash-fire" to "immunisé contre le type Feu (et renforce ses capacités Feu)",
    "well-baked-body" to "immunisé contre le type Feu (et augmente sa Défense)",
    "volt-absorb" to "immunisé contre le type Électrik (et récupère des PV)",
    "lightning-rod" to "immunisé contre le type Électrik (et augmente son Attaque Spéciale)",
    "motor-drive" to "immunisé contre le type Électrik (et augmente sa Vitesse)",
    "water-absorb" to "immunisé contre le type Eau (et récupère des PV)",
    "storm-drain" to "immunisé contre le type Eau (et augmente son Attaque Spéciale)",
    "dry-skin" to "immunisé contre le type Eau, mais subit 25 % de dégâts en plus du type Feu",
    "sap-sipper" to "immunisé contre le type Plante (et augmente son Attaque)",
    "thick-fat" to "dégâts des types Feu et Glace divisés par deux",
    "heatproof" to "dégâts du type Feu divisés par deux",
    "water-bubble" to "dégâts du type Feu divisés par deux",
    "purifying-salt" to "dégâts du type Spectre divisés par deux",
    "fluffy" to "dégâts du type Feu doublés (mais contacts divisés par deux)",
    "filter" to "dégâts super efficaces réduits de 25 %",
    "solid-rock" to "dégâts super efficaces réduits de 25 %",
    "prism-armor" to "dégâts super efficaces réduits de 25 %",
    "wonder-guard" to "ne peut être touché que par les capacités super efficaces",
    "tera-shell" to "PV au maximum : toutes les capacités sont peu efficaces",
)

class TypeService(
    private val database: Database,
    private val pokemonService: PokemonService,
    private val abilityRepository: AbilityRepository,
    private val pokeApi: PokeApiClient,
) {
    suspend fun defenses(idOrName: String): TypeDefenses? {
        val pokemon = pokemonService.getPokemon(idOrName) ?: return null
        val multipliers = computeMultipliers(pokemon.types.map { relations(it) })

        val abilityNames = abilityRepository.find(pokemon.abilities.map { it.name })
        val notes = pokemon.abilities.mapNotNull { a ->
            ABILITY_TYPE_EFFECTS[a.name]?.let { AbilityTypeNote(a.name, abilityNames[a.name]?.nameFr, it) }
        }

        return TypeDefenses(
            pokemonId = pokemon.id,
            types = pokemon.types,
            multipliers = multipliers,
            weaknesses = multipliers.filter { it.multiplier > 1.0 }.sortedByDescending { it.multiplier },
            resistances = multipliers.filter { it.multiplier > 0.0 && it.multiplier < 1.0 }.sortedBy { it.multiplier },
            immunities = multipliers.filter { it.multiplier == 0.0 }.map { it.type },
            abilityNotes = notes,
        )
    }

    /** Relations d'un type défensif : depuis la base, sinon PokéAPI (puis mise en cache). */
    private suspend fun relations(type: String): DefensiveRelations {
        cached(type)?.let { return it }
        val dto = pokeApi.getType(type)
            ?: return DefensiveRelations(emptySet(), emptySet(), emptySet())
        val rel = DefensiveRelations(
            doubleFrom = dto.damageRelations.doubleDamageFrom.map { it.name }.toSet(),
            halfFrom = dto.damageRelations.halfDamageFrom.map { it.name }.toSet(),
            noFrom = dto.damageRelations.noDamageFrom.map { it.name }.toSet(),
        )
        newSuspendedTransaction(Dispatchers.IO, database) {
            TypeRelationTable.upsert {
                it[TypeRelationTable.type] = type
                it[doubleFrom] = rel.doubleFrom.joinToString(",")
                it[halfFrom] = rel.halfFrom.joinToString(",")
                it[noFrom] = rel.noFrom.joinToString(",")
                it[fetchedAt] = Instant.now()
            }
        }
        return rel
    }

    private suspend fun cached(type: String): DefensiveRelations? =
        newSuspendedTransaction(Dispatchers.IO, database) {
            TypeRelationTable.selectAll().where { TypeRelationTable.type eq type }.singleOrNull()?.let {
                fun set(value: String) = value.split(',').filter { s -> s.isNotBlank() }.toSet()
                DefensiveRelations(set(it[TypeRelationTable.doubleFrom]), set(it[TypeRelationTable.halfFrom]), set(it[TypeRelationTable.noFrom]))
            }
        }
}
