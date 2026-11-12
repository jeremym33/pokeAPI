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
    fun `statuts - immunites, fin de tour, brulure et paralysie`() {
        val grass = fighter(20)
        assertTrue(GameRules.canReceive(grass, "burn"))
        assertTrue(!GameRules.canReceive(fighter(20, types = listOf("fire")), "burn"))
        assertTrue(!GameRules.canReceive(fighter(20, types = listOf("poison")), "poison"))
        assertTrue(!GameRules.canReceive(fighter(20, types = listOf("electric")), "paralysis"))
        assertTrue(!GameRules.canReceive(grass.copy(status = "sleep"), "burn")) // un seul statut à la fois
        assertTrue(!GameRules.canReceive(grass, "confusion")) // statut non géré

        val full = grass.copy(hp = grass.maxHp)
        assertEquals(full.maxHp / 8, GameRules.residualDamage(full.copy(status = "poison")))
        assertEquals(full.maxHp / 16, GameRules.residualDamage(full.copy(status = "burn")))
        assertEquals(0, GameRules.residualDamage(full.copy(status = "paralysis")))

        assertEquals(GameRules.stats(grass).speed / 2, GameRules.speed(grass.copy(status = "paralysis")))

        val tackle = FighterMove("tackle", "Charge", "normal", "physical", 40, null)
        val normal = GameRules.attack(fighter(30), fighter(30), tackle, Random(3)).damage
        val burned = GameRules.attack(fighter(30).copy(status = "burn"), fighter(30), tackle, Random(3)).damage
        assertTrue(burned < normal, "brûlure : $burned >= $normal")
    }

    @Test
    fun `l'adversaire n'inflige pas un statut deja present`() {
        val sleepPowder = FighterMove("sleep-powder", "Poudre Dodo", "grass", "status", 0, 75, ailment = "sleep")
        val tackle = FighterMove("tackle", "Charge", "normal", "physical", 40, 100)
        val attacker = fighter(20).copy(moves = listOf(sleepPowder, tackle))
        repeat(20) { seed ->
            assertEquals("tackle", GameRules.chooseMove(attacker, fighter(20).copy(status = "sleep"), Random(seed)).name)
        }
    }

    private fun item(name: String) = GameRules.ITEMS.single { it.item == name }

    @Test
    fun `evolution par pierre d'apres les conditions`() {
        val eeveeLike = listOf(134 to listOf("Utiliser Pierre Eau"), 136 to listOf("Utiliser Pierre Feu"), 196 to listOf("Gagner un niveau, avec un bonheur élevé, de jour"))
        assertEquals(136, GameRules.itemEvolution(eeveeLike, item("fire-stone")))
        assertEquals(null, GameRules.itemEvolution(eeveeLike, item("moon-stone")))
        assertEquals(null, GameRules.itemEvolution(eeveeLike, item("link-cable")))
        // Variante régionale (« dans la région Alola ») : pas une évolution par pierre simple
        assertEquals(null, GameRules.itemEvolution(listOf(10100 to listOf("Utiliser Pierre Foudre, dans la région Alola")), item("thunder-stone")))
    }

    @Test
    fun `cable link pour les evolutions par echange`() {
        val cable = item("link-cable")
        assertEquals(65, GameRules.itemEvolution(listOf(65 to listOf("Échange")), cable)) // Kadabra → Alakazam
        assertEquals(199, GameRules.itemEvolution(listOf(80 to listOf("Niveau 37"), 199 to listOf("Échange, en tenant Roche Royale")), cable)) // Ramoloss → Roigada
        assertEquals(null, GameRules.itemEvolution(listOf(589 to listOf("Échange, contre Escargaume")), cable))
        assertEquals(null, GameRules.itemEvolution(listOf(65 to listOf("Échange")), item("thunder-stone")))
    }

    @Test
    fun `butin - objet selon le type, toujours un objet en arene`() {
        // Sur de nombreux tirages : environ 10 % de butin, objet lié au type
        val drops = (0 until 2000).mapNotNull { GameRules.drop(listOf("fire"), gym = false, Random(it)) }
        assertTrue(drops.size in 120..280, "butin : ${drops.size} sur 2000")
        assertTrue(drops.all { it.item == "fire-stone" })
        assertTrue((0 until 200).mapNotNull { GameRules.drop(listOf("psychic"), gym = false, Random(it)) }.all { it.item == "link-cable" })
        // Arène : un objet à chaque fois
        assertTrue((0 until 50).all { GameRules.drop(emptyList(), gym = true, Random(it)) != null })
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
