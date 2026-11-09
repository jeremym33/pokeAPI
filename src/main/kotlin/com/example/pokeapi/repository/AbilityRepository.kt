package com.example.pokeapi.repository

import com.example.pokeapi.client.PokeApiAbility
import com.example.pokeapi.client.cleanEffectText
import com.example.pokeapi.client.cleanGameText
import com.example.pokeapi.client.pokeApiJson
import com.example.pokeapi.client.resourceId
import com.example.pokeapi.db.AbilityTable
import com.example.pokeapi.model.parseGeneration
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.Instant

@Serializable
data class AbilityHolder(val id: Int, val name: String, val hidden: Boolean)

data class AbilityInfo(
    val name: String,
    val nameFr: String?,
    val descriptionFr: String?,
    val effect: String?,
    val effectLanguage: String?,
    val generation: Int?,
    val pokemon: List<AbilityHolder>,
)

class AbilityRepository(private val database: Database) {

    private val holdersSerializer = ListSerializer(AbilityHolder.serializer())

    private suspend fun <T> dbQuery(block: suspend Transaction.() -> T): T =
        newSuspendedTransaction(Dispatchers.IO, database) { block() }

    suspend fun find(names: Collection<String>): Map<String, AbilityInfo> = dbQuery {
        if (names.isEmpty()) return@dbQuery emptyMap()
        AbilityTable.selectAll().where { AbilityTable.name inList names }.associate {
            it[AbilityTable.name] to AbilityInfo(
                name = it[AbilityTable.name],
                nameFr = it[AbilityTable.nameFr],
                descriptionFr = it[AbilityTable.descriptionFr],
                effect = it[AbilityTable.effect],
                effectLanguage = it[AbilityTable.effectLanguage],
                generation = it[AbilityTable.generation],
                pokemon = pokeApiJson.decodeFromString(holdersSerializer, it[AbilityTable.pokemon]),
            )
        }
    }

    suspend fun save(dto: PokeApiAbility): AbilityInfo {
        // Effet : français si disponible, sinon anglais ; version détaillée de préférence.
        val effectEntry = dto.effectEntries.firstOrNull { it.language.name == "fr" }
            ?: dto.effectEntries.firstOrNull { it.language.name == "en" }
        val info = AbilityInfo(
            name = dto.name,
            nameFr = dto.names.firstOrNull { it.language.name == "fr" }?.name,
            descriptionFr = dto.flavorTextEntries.lastOrNull { it.language.name == "fr" }?.flavorText?.let(::cleanGameText),
            effect = effectEntry?.let { (it.effect.ifBlank { it.shortEffect }) }?.let(::cleanEffectText)?.ifBlank { null },
            effectLanguage = effectEntry?.language?.name,
            generation = dto.generation?.name?.let(::parseGeneration),
            pokemon = dto.pokemon.mapNotNull { p ->
                resourceId(p.pokemon.url)?.let { AbilityHolder(it, p.pokemon.name, p.isHidden) }
            },
        )
        dbQuery {
            AbilityTable.upsert {
                it[name] = info.name
                it[nameFr] = info.nameFr
                it[descriptionFr] = info.descriptionFr
                it[effect] = info.effect
                it[effectLanguage] = info.effectLanguage
                it[generation] = info.generation
                it[pokemon] = pokeApiJson.encodeToString(holdersSerializer, info.pokemon)
                it[fetchedAt] = Instant.now()
            }
        }
        return info
    }
}
