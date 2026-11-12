package com.example.pokeapi

import com.example.pokeapi.game.Fighter
import com.example.pokeapi.game.FighterMove
import com.example.pokeapi.game.GameRules
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GameRulesTest {
    private val bulbasaurBase = mapOf(
        "hp" to 45, "attack" to 49, "defense" to 49, "special-attack" to 65, "special-defense" to 65, "speed" to 45,
    )

    private fun fighter(level: Int, types: List<String> = listOf("grass"), defenses: Map<String, Double> = emptyMap()) = Fighter(
        uid = "x", pokemonId = 1, speciesId = 1, name = "bulbasaur", nameFr = "Bulbizarre", spriteUrl = null, artworkUrl = null,
        types = types, level = level, xp = GameRules.xpForLevel(level), hp = 1, base = bulbasaurBase, baseExperience = 64,
        captureRate = 45, moves = emptyList(), defenses = defenses,
    )

    private val ember = FighterMove("ember", "Flammèche", "fire", "special", 40, null)

    @Test
    fun `statistiques d'apres les stats de base et le niveau`() {
        val s = GameRules.stats(bulbasaurBase, 5)
        assertEquals(19, s.hp) // floor(2*45*5/100) + 5 + 10
        assertEquals(9, s.attack) // floor(2*49*5/100) + 5
        assertEquals(11, s.spAttack)
    }

    @Test
    fun `niveaux et experience`() {
        assertEquals(125, GameRules.xpForLevel(5))
        assertEquals(5, GameRules.levelForXp(215))
        assertEquals(6, GameRules.levelForXp(216))
        // Montée de niveau : les PV gagnés s'ajoutent
        val up = GameRules.gainXp(fighter(5).copy(hp = 10), 100, cap = 15)
        assertEquals(6, up.level)
        assertEquals(10 + GameRules.stats(bulbasaurBase, 6).hp - GameRules.stats(bulbasaurBase, 5).hp, up.hp)
    }

    @Test
    fun `la limite de niveau bloque l'experience`() {
        val capped = GameRules.gainXp(fighter(14), 1_000_000, cap = 15)
        assertEquals(15, capped.level)
        assertEquals(GameRules.xpForLevel(15), capped.xp)
        assertEquals(capped, GameRules.gainXp(capped, 500, cap = 15))
        assertEquals(listOf(15, 22, 28), (0..2).map { GameRules.levelCap(it) })
        assertEquals(100, GameRules.levelCap(8))
    }

    @Test
    fun `chance de recrutement selon le taux de capture`() {
        assertEquals(0.70, GameRules.joinChance(255), 1e-9)
        assertTrue(GameRules.joinChance(3) in 0.15..0.16) // légendaires
        assertTrue(GameRules.joinChance(45) < GameRules.joinChance(190))
    }

    @Test
    fun `degats selon l'efficacite du type`() {
        val attacker = fighter(20, types = listOf("fire"))
        val neutral = GameRules.attack(attacker, fighter(20), ember, Random(7))
        val superEffective = GameRules.attack(attacker, fighter(20, defenses = mapOf("fire" to 2.0)), ember, Random(7))
        val immune = GameRules.attack(attacker, fighter(20, defenses = mapOf("fire" to 0.0)), ember, Random(7))
        assertTrue(neutral.hit && neutral.damage > 0)
        assertTrue(superEffective.damage >= 2 * neutral.damage - 1)
        assertEquals(0, immune.damage)
        assertEquals("C'est super efficace !", GameRules.effectivenessText(2.0))
    }

    @Test
    fun `progression de Kanto`() {
        assertEquals("pallet-town", GameRules.STEPS.first().location)
        assertEquals(8, GameRules.STEPS.count { it.gym })
        assertEquals(listOf(0, 1, 2), GameRules.STEPS.withIndex().filter { it.value.gym }.take(3).map { GameRules.gymNumber(it.index) })
        // Arène : 3 niveaux au-dessus des sauvages, sans atteindre la limite
        assertEquals(9, GameRules.gymTopLevel(6, 15))
        assertEquals(14, GameRules.gymTopLevel(40, 15))
        // Surf et cannes plus tard
        assertTrue("surf" !in GameRules.allowedMethods(0))
        assertTrue("surf" in GameRules.allowedMethods(5))
    }
}
