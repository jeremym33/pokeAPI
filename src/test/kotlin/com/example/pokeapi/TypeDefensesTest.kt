package com.example.pokeapi

import com.example.pokeapi.service.ATTACKING_TYPES
import com.example.pokeapi.service.DefensiveRelations
import com.example.pokeapi.service.computeMultipliers
import kotlin.test.Test
import kotlin.test.assertEquals

/** Calcul des multiplicateurs, vérifié sur des Pokémon double type connus (table des types actuelle). */
class TypeDefensesTest {

    // Dégâts reçus par type (côté défense), d'après la table officielle (6e génération et suivantes)
    private val chart = mapOf(
        "grass" to rel(double = "flying poison bug fire ice", half = "ground water grass electric"),
        "poison" to rel(double = "ground psychic", half = "fighting poison bug grass fairy"),
        "water" to rel(double = "grass electric", half = "steel fire water ice"),
        "flying" to rel(double = "rock electric ice", half = "fighting bug grass", none = "ground"),
        "ghost" to rel(double = "ghost dark", half = "poison bug", none = "normal fighting"),
        "bug" to rel(double = "flying rock fire", half = "fighting ground grass"),
        "steel" to rel(
            double = "fighting ground fire",
            half = "normal flying rock bug steel grass psychic ice dragon fairy",
            none = "poison",
        ),
    )

    private fun rel(double: String, half: String, none: String = "") =
        DefensiveRelations(double.words(), half.words(), none.words())

    private fun String.words() = split(' ').filter { it.isNotBlank() }.toSet()

    private fun multipliers(vararg types: String) =
        computeMultipliers(types.map { chart.getValue(it) }).associate { it.type to it.multiplier }

    @Test
    fun `les 18 types attaquants sont toujours presents`() {
        assertEquals(ATTACKING_TYPES, computeMultipliers(listOf(chart.getValue("grass"))).map { it.type })
    }

    @Test
    fun `Bulbizarre (Plante, Poison)`() {
        val m = multipliers("grass", "poison")
        assertEquals(setOf("fire", "ice", "flying", "psychic"), m.filterValues { it == 2.0 }.keys)
        assertEquals(0.25, m["grass"])
        assertEquals(setOf("water", "electric", "fighting", "fairy"), m.filterValues { it == 0.5 }.keys)
        assertEquals(1.0, m["ground"]) // ×2 Poison × ½ Plante
    }

    @Test
    fun `Leviator (Eau, Vol) - x4 Electrik et immunite Sol`() {
        val m = multipliers("water", "flying")
        assertEquals(4.0, m["electric"])
        assertEquals(2.0, m["rock"])
        assertEquals(0.0, m["ground"])
        assertEquals(1.0, m["grass"]) // ×2 Eau × ½ Vol
    }

    @Test
    fun `Ectoplasma (Spectre, Poison) - deux immunites`() {
        val m = multipliers("ghost", "poison")
        assertEquals(setOf("normal", "fighting"), m.filterValues { it == 0.0 }.keys)
        assertEquals(0.25, m["bug"])
        assertEquals(setOf("ghost", "dark", "ground", "psychic"), m.filterValues { it == 2.0 }.keys)
    }

    @Test
    fun `Cizayox (Insecte, Acier) - x4 Feu, seule faiblesse`() {
        val m = multipliers("bug", "steel")
        assertEquals(mapOf("fire" to 4.0), m.filterValues { it > 1.0 })
        assertEquals(0.0, m["poison"])
        assertEquals(0.25, m["grass"])
    }
}
