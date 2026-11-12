package com.example.pokeapi.game

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/** Étape de l'aventure : un lieu de Kanto, dans l'ordre de Vert Feuille. */
data class Step(val location: String, val city: Boolean = false, val gym: Boolean = false)

data class Stats(val hp: Int, val attack: Int, val defense: Int, val spAttack: Int, val spDefense: Int, val speed: Int)

data class AttackResult(val damage: Int, val hit: Boolean, val critical: Boolean, val effectiveness: Double)

/**
 * Objet d'évolution trouvé sur les Pokémon vaincus. Pour une pierre, nameFr est aussi le nom utilisé
 * par les conditions d'évolution (« Utiliser Pierre Feu »).
 */
data class EvolutionItem(val item: String, val nameFr: String, val trade: Boolean = false) {
    /** La condition d'évolution est-elle remplie par cet objet ? */
    fun matches(condition: String): Boolean =
        if (trade) condition == "Échange" || condition.startsWith("Échange, en tenant") else condition == "Utiliser $nameFr"
}

/** Règles du jeu, sans accès réseau ni base : testables directement. */
object GameRules {
    /** Progression de Kanto (ordre de Vert Feuille). Jadielle revient à la fin pour sa propre arène. */
    val STEPS: List<Step> = listOf(
        Step("pallet-town", city = true),
        Step("kanto-route-1"),
        Step("viridian-city", city = true),
        Step("kanto-route-22"),
        Step("kanto-route-2"),
        Step("viridian-forest"),
        Step("pewter-city", city = true, gym = true),
        Step("kanto-route-3"),
        Step("mt-moon"),
        Step("kanto-route-4"),
        Step("cerulean-city", city = true, gym = true),
        Step("kanto-route-24"),
        Step("kanto-route-25"),
        Step("kanto-route-5"),
        Step("kanto-route-6"),
        Step("vermilion-city", city = true, gym = true),
        Step("kanto-route-11"),
        Step("digletts-cave"),
        Step("kanto-route-9"),
        Step("kanto-route-10"),
        Step("kanto-power-plant"),
        Step("rock-tunnel"),
        Step("lavender-town", city = true),
        Step("kanto-route-8"),
        Step("kanto-route-7"),
        Step("celadon-city", city = true, gym = true),
        Step("pokemon-tower"),
        Step("kanto-route-12"),
        Step("kanto-route-13"),
        Step("kanto-route-14"),
        Step("kanto-route-15"),
        Step("fuchsia-city", city = true, gym = true),
        Step("kanto-safari-zone"),
        Step("kanto-route-18"),
        Step("kanto-route-17"),
        Step("kanto-route-16"),
        Step("saffron-city", city = true, gym = true),
        Step("kanto-sea-route-19"),
        Step("kanto-sea-route-20"),
        Step("seafoam-islands"),
        Step("cinnabar-island", city = true, gym = true),
        Step("pokemon-mansion"),
        Step("kanto-sea-route-21"),
        Step("viridian-city", city = true, gym = true),
        Step("kanto-route-23"),
        Step("kanto-victory-road-2"),
        Step("indigo-plateau", city = true),
        Step("cerulean-cave"),
    )

    /** Version de jeu dont viennent les rencontres. */
    const val VERSION = "leafgreen"

    /** Victoires nécessaires pour franchir une route. */
    const val ROUTE_WINS = 3

    /** Nombre d'espèces à capturer (Pokédex de Kanto). */
    const val DEX_SIZE = 151

    val STARTERS = listOf("bulbasaur", "charmander", "squirtle")
    const val STARTER_LEVEL = 5

    /** Niveau maximum selon le nombre de badges (0 à 8). */
    private val LEVEL_CAPS = listOf(15, 22, 28, 34, 42, 47, 52, 57, 100)

    fun levelCap(badges: Int): Int = LEVEL_CAPS[badges.coerceIn(0, LEVEL_CAPS.lastIndex)]

    /** Numéro (0 à 7) de l'arène d'une étape. */
    fun gymNumber(step: Int): Int = STEPS.take(step).count { it.gym }

    /** Méthodes de rencontre autorisées selon les badges (surf et cannes plus tard dans l'aventure). */
    fun allowedMethods(badges: Int): Set<String> = buildSet {
        add("walk"); add("old-rod")
        if (badges >= 3) add("good-rod")
        if (badges >= 5) { add("surf"); add("super-rod") }
    }

    /** Objets d'évolution : les 5 pierres de Kanto et le Câble Link (évolutions par échange). */
    val ITEMS = listOf(
        EvolutionItem("fire-stone", "Pierre Feu"),
        EvolutionItem("water-stone", "Pierre Eau"),
        EvolutionItem("thunder-stone", "Pierre Foudre"),
        EvolutionItem("leaf-stone", "Pierre Plante"),
        EvolutionItem("moon-stone", "Pierre Lune"),
        EvolutionItem("link-cable", "Câble Link", trade = true),
    )

    /** Chance qu'un Pokémon sauvage vaincu laisse tomber un objet d'évolution. */
    const val DROP_CHANCE = 0.10

    /** Objet lâché selon le type du Pokémon (Pierre Feu pour un Pokémon Feu…). */
    private val DROP_BY_TYPE = mapOf(
        "fire" to "fire-stone", "water" to "water-stone", "ice" to "water-stone", "electric" to "thunder-stone",
        "grass" to "leaf-stone", "bug" to "leaf-stone", "fairy" to "moon-stone", "normal" to "moon-stone",
        "psychic" to "link-cable", "fighting" to "link-cable", "rock" to "link-cable", "ghost" to "link-cable",
    )

    /** Objet lâché par un Pokémon vaincu (null s'il n'en lâche pas) ; une arène en donne toujours un. */
    fun drop(types: List<String>, gym: Boolean, random: Random): EvolutionItem? {
        if (!gym && random.nextDouble() >= DROP_CHANCE) return null
        val item = types.firstNotNullOfOrNull { DROP_BY_TYPE[it] }?.takeIf { !gym } ?: ITEMS.random(random).item
        return ITEMS.single { it.item == item }
    }

    /** Statuts gérés. */
    val STATUSES = setOf("burn", "poison", "paralysis", "sleep", "freeze")

    val STATUS_FR = mapOf(
        "burn" to "brûlé", "poison" to "empoisonné", "paralysis" to "paralysé", "sleep" to "endormi", "freeze" to "gelé",
    )

    /** Types insensibles à un statut (un Pokémon Feu ne peut pas être brûlé…). */
    private val STATUS_IMMUNE_TYPES = mapOf(
        "burn" to setOf("fire"), "poison" to setOf("poison", "steel"), "paralysis" to setOf("electric"), "freeze" to setOf("ice"),
    )

    /** Le statut peut-il être infligé ? (pas de deuxième statut, immunités de type) */
    fun canReceive(f: Fighter, status: String): Boolean =
        status in STATUSES && f.status == null && f.types.none { it in STATUS_IMMUNE_TYPES[status].orEmpty() }

    /** Dégâts de fin de tour : 1/8 des PV pour le poison, 1/16 pour la brûlure. */
    fun residualDamage(f: Fighter): Int = when (f.status) {
        "poison" -> maxOf(1, f.maxHp / 8)
        "burn" -> maxOf(1, f.maxHp / 16)
        else -> 0
    }

    /** Évolution déclenchée par un objet, parmi les évolutions possibles (conditions décrites en français). */
    fun <T> itemEvolution(children: List<Pair<T, List<String>>>, item: EvolutionItem): T? =
        children.firstOrNull { (_, conditions) -> conditions.any(item::matches) }?.first

    fun stats(f: Fighter): Stats = stats(f.base, f.level)

    fun stats(base: Map<String, Int>, level: Int): Stats {
        fun other(key: String) = floor(2.0 * (base[key] ?: 50) * level / 100).toInt() + 5
        return Stats(
            hp = floor(2.0 * (base["hp"] ?: 50) * level / 100).toInt() + level + 10,
            attack = other("attack"),
            defense = other("defense"),
            spAttack = other("special-attack"),
            spDefense = other("special-defense"),
            speed = other("speed"),
        )
    }

    fun xpForLevel(level: Int): Int = level * level * level

    fun levelForXp(xp: Int): Int {
        var level = 1
        while (xpForLevel(level + 1) <= xp && level < 100) level++
        return level
    }

    /** Expérience gagnée en battant un Pokémon. */
    fun xpReward(enemy: Fighter, gym: Boolean): Int {
        val base = enemy.baseExperience * enemy.level / 7.0
        return max(1, (if (gym) base * 1.5 else base).roundToInt())
    }

    /**
     * Chance qu'un Pokémon sauvage vaincu rejoigne l'équipe : de 15 % (légendaires) à 70 % (Chenipan…),
     * selon son taux de capture.
     */
    fun joinChance(captureRate: Int): Double = 0.15 + 0.55 * (captureRate.coerceIn(0, 255) / 255.0)

    /** Dégâts d'une attaque (formule simplifiée des jeux : niveau, puissance, stats, STAB, types, aléa). */
    fun attack(attacker: Fighter, defender: Fighter, move: FighterMove, random: Random, checkAccuracy: Boolean = true): AttackResult {
        val effectiveness = defender.defenses[move.type] ?: 1.0
        if (checkAccuracy && move.accuracy != null && random.nextInt(100) >= move.accuracy) return AttackResult(0, false, false, effectiveness)
        if (effectiveness == 0.0) return AttackResult(0, true, false, 0.0)

        val a = stats(attacker)
        val d = stats(defender)
        val (atk0, def) = if (move.damageClass == "special") a.spAttack to d.spDefense else a.attack to d.defense
        // La brûlure divise par deux l'Attaque physique.
        val atk = if (attacker.status == "burn" && move.damageClass == "physical") atk0 / 2 else atk0
        val critical = random.nextInt(24) == 0
        var damage = floor(floor(2.0 * attacker.level / 5 + 2) * move.power * atk / def / 50) + 2
        if (move.type in attacker.types) damage *= 1.5
        damage *= effectiveness
        if (critical) damage *= 1.5
        damage *= 0.85 + random.nextDouble() * 0.15
        return AttackResult(max(1, damage.toInt()), true, critical, effectiveness)
    }

    /**
     * Nombre de coups d'une attaque : fixe (Double Pied : 2) ou, de 2 à 5, comme dans les jeux
     * (2 ou 3 coups 35 % du temps chacun, 4 ou 5 coups 15 %).
     */
    fun hitCount(move: FighterMove, random: Random): Int {
        val min = move.minHits ?: return 1
        val max = move.maxHits ?: min
        if (max <= min) return min
        if (min == 2 && max == 5) {
            val r = random.nextInt(100)
            return when {
                r < 35 -> 2
                r < 70 -> 3
                r < 85 -> 4
                else -> 5
            }
        }
        return random.nextInt(min, max + 1)
    }

    /** Nombre moyen de coups (pour estimer l'intérêt d'une attaque). */
    fun averageHits(move: FighterMove): Double {
        val min = move.minHits ?: return 1.0
        val max = move.maxHits ?: min
        return if (min == 2 && max == 5) 3.1 else (min + max) / 2.0
    }

    /** PV récupérés par une attaque de soin (Repos : tous). */
    fun healAmount(f: Fighter, move: FighterMove): Int =
        if (move.name == FighterMove.REST) f.maxHp - f.hp
        else minOf(f.maxHp - f.hp, maxOf(1, f.maxHp * move.healing / 100))

    /** Intérêt d'une attaque pour l'adversaire : dégâts attendus, ou statut à infliger. */
    private fun moveScore(attacker: Fighter, defender: Fighter, m: FighterMove): Double {
        val accuracy = (m.accuracy ?: 100) / 100.0
        if (m.heals) {
            // Se soigner quand il reste moins de la moitié des PV
            return if (attacker.hp * 2 < attacker.maxHp && (m.name != FighterMove.REST || attacker.hp * 4 < attacker.maxHp)) 70.0 else 0.0
        }
        if (m.damageClass == "status") {
            return if (m.ailment != null && canReceive(defender, m.ailment)) 45.0 * accuracy else 0.0
        }
        return m.power * averageHits(m) * (if (m.type in attacker.types) 1.5 else 1.0) * (defender.defenses[m.type] ?: 1.0) * accuracy
    }

    /** Attaque choisie par l'adversaire : la plus intéressante, avec un peu de hasard. */
    fun chooseMove(attacker: Fighter, defender: Fighter, random: Random): FighterMove {
        val useful = attacker.moves.filter { moveScore(attacker, defender, it) > 0 }.ifEmpty { attacker.moves }
        if (random.nextInt(5) == 0) return useful.random(random)
        return useful.maxBy { moveScore(attacker, defender, it) }
    }

    /** Vitesse effective (divisée par deux par la paralysie). */
    fun speed(f: Fighter): Int = stats(f).speed / if (f.status == "paralysis") 2 else 1

    /** Le premier à attaquer : priorité, puis vitesse (égalité tirée au sort). */
    fun firstToAct(a: Fighter, aMove: FighterMove, b: Fighter, bMove: FighterMove, random: Random): Boolean = when {
        aMove.priority != bMove.priority -> aMove.priority > bMove.priority
        speed(a) != speed(b) -> speed(a) > speed(b)
        else -> random.nextBoolean()
    }

    /** Ajoute de l'expérience, sans dépasser la limite de niveau. */
    fun gainXp(f: Fighter, amount: Int, cap: Int): Fighter {
        val maxXp = xpForLevel(cap)
        if (f.level >= cap) return f
        val xp = min(f.xp + amount, maxXp)
        val level = min(levelForXp(xp), cap)
        if (level == f.level) return f.copy(xp = xp)
        // Les PV gagnés à la montée de niveau s'ajoutent aux PV actuels.
        val hpGain = stats(f.base, level).hp - stats(f.base, f.level).hp
        return f.copy(xp = xp, level = level, hp = f.hp + hpGain)
    }

    /** Niveau du meilleur Pokémon d'une arène : 3 au-dessus des sauvages déjà croisés, dans la limite de niveau. */
    fun gymTopLevel(wildMaxLevel: Int, levelCap: Int): Int = (wildMaxLevel + 3).coerceIn(5, levelCap - 1)

    fun effectivenessText(e: Double): String? = when {
        e == 0.0 -> "Ça n'affecte pas l'adversaire…"
        e >= 2.0 -> "C'est super efficace !"
        e < 1.0 -> "Ce n'est pas très efficace…"
        else -> null
    }
}
