package com.example.pokeapi

import com.example.pokeapi.game.Fighter
import com.example.pokeapi.game.AchievementContext
import com.example.pokeapi.game.Achievements
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
        assertTrue(!GameRules.canReceive(grass, "infatuation")) // effet non géré
        // La confusion se cumule avec un statut, mais pas avec elle-même
        assertTrue(GameRules.canReceive(grass.copy(status = "sleep"), "confusion"))
        assertTrue(!GameRules.canReceive(grass.copy(confusedTurns = 2), "confusion"))

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
    fun `succes - progression et deblocage`() {
        val start = AchievementContext(victories = 0, badges = 0, caught = 1, seen = 1, caughtByType = mapOf("grass" to 1, "poison" to 1))
        assertEquals(emptyList(), Achievements.newlyCompleted(start, emptySet()).map { it.id })
        val ctx = start.copy(victories = 12, caughtByType = mapOf("fire" to 2, "poison" to 2, "fairy" to 1))
        val done = Achievements.newlyCompleted(ctx, setOf("wins-1"))
        // « Premier combat » est déjà débloqué ; 10 victoires, 2 Pokémon Feu, 3 Poison ou Fée
        assertEquals(listOf("wins-10", "type-fire", "type-moon"), done.map { it.id })
        assertEquals(listOf("Pierre Feu"), Achievements.rewardNames(done[1]))
        // Chaque objet d'évolution peut s'obtenir, et chaque succès en donne au moins un
        assertEquals(GameRules.ITEMS.map { it.item }.toSet(), Achievements.ALL.flatMap { it.rewards }.toSet())
        assertTrue(Achievements.ALL.all { it.rewards.isNotEmpty() })
        assertEquals(Achievements.ALL.size, Achievements.ALL.map { it.id }.toSet().size)
        val view = Achievements.view(ctx, setOf("wins-1"))
        assertEquals(1, view.done)
        assertEquals(10, view.achievements.single { it.id == "wins-10" }.progress) // borné à l'objectif
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

    @Test
    fun `coups multiples comme dans les jeux`() {
        val furySwipes = FighterMove("fury-swipes", "Combo-Griffe", "normal", "physical", 18, 80, minHits = 2, maxHits = 5)
        val doubleKick = FighterMove("double-kick", "Double Pied", "fighting", "physical", 30, 100, minHits = 2, maxHits = 2)
        val random = Random(1)
        val counts = (1..10_000).groupingBy { GameRules.hitCount(furySwipes, random) }.eachCount()
        assertEquals(setOf(2, 3, 4, 5), counts.keys)
        assertTrue(counts.getValue(2) in 3200..3800 && counts.getValue(5) in 1200..1800, counts.toString())
        assertEquals(2, GameRules.hitCount(doubleKick, random))
        assertEquals(1, GameRules.hitCount(ember, random))
        assertEquals(3.1, GameRules.averageHits(furySwipes), 1e-9)
    }

    @Test
    fun `attaques de soin`() {
        val recover = FighterMove("recover", "Soin", "normal", "status", 0, null, healing = 50)
        val rest = FighterMove(FighterMove.REST, "Repos", "psychic", "status", 0, null)
        val f = fighter(20)
        val max = f.maxHp
        assertTrue(recover.heals && rest.heals && !ember.heals)
        assertEquals(max / 2, GameRules.healAmount(f.copy(hp = 1), recover))
        assertEquals(3, GameRules.healAmount(f.copy(hp = max - 3), recover)) // sans dépasser le maximum
        assertEquals(max - 1, GameRules.healAmount(f.copy(hp = 1), rest))
    }

    @Test
    fun `niveaux de statistiques`() {
        assertEquals(2.0, GameRules.stageMultiplier(2))
        assertEquals(4.0, GameRules.stageMultiplier(6))
        assertEquals(0.5, GameRules.stageMultiplier(-2))
        assertEquals(0.25, GameRules.stageMultiplier(-6))
        assertEquals(0.75, GameRules.accuracyMultiplier(-1), 1e-9)

        val f = fighter(20)
        val (boosted, lines) = GameRules.applyStages(f, mapOf("attack" to 2), "Bulbizarre")
        assertEquals(mapOf("attack" to 2), boosted.stages)
        assertEquals(listOf("L'Attaque de Bulbizarre augmente beaucoup !"), lines)
        val (maxed, maxLines) = GameRules.applyStages(boosted.copy(stages = mapOf("attack" to 6)), mapOf("attack" to 2), "Bulbizarre")
        assertEquals(6, maxed.stages["attack"])
        assertEquals(listOf("L'Attaque de Bulbizarre ne peut plus augmenter !"), maxLines)
        val (lowered, lowLines) = GameRules.applyStages(f, mapOf("defense" to -1), "Bulbizarre adverse")
        assertEquals(-1, lowered.stages["defense"])
        assertEquals(listOf("La Défense de Bulbizarre adverse baisse !"), lowLines)
        // Retour à 0 : le niveau disparaît
        assertEquals(emptyMap(), GameRules.applyStages(lowered, mapOf("defense" to 1), "x").first.stages)

        // Vitesse doublée à +2 ; dégâts plus forts avec l'Attaque augmentée
        assertEquals(GameRules.speed(f) * 2, GameRules.speed(f.copy(stages = mapOf("speed" to 2))), GameRules.speed(f).toString())
        val tackle = FighterMove("tackle", "Charge", "normal", "physical", 40, null)
        val target = fighter(20).copy(hp = 1000)
        val normal = (1..200).sumOf { GameRules.attack(f, target, tackle, Random(it)).damage }
        val strong = (1..200).sumOf { GameRules.attack(f.copy(stages = mapOf("attack" to 2)), target, tackle, Random(it)).damage }
        assertTrue(strong > normal * 1.6, "$normal -> $strong")
    }

    @Test
    fun `effets des badges`() {
        assertEquals(listOf("Niveau maximum : 15 → 22"), GameRules.badgeEffects(1))
        assertEquals(listOf("Niveau maximum : 28 → 34", "Pêche à la Super Canne : nouveaux Pokémon dans l'eau"), GameRules.badgeEffects(3))
        assertEquals(3, GameRules.badgeEffects(5).size) // niveau, Surf, Méga Canne
        assertEquals("Niveau maximum : 57 → 100", GameRules.badgeEffects(8).single())
    }
}
