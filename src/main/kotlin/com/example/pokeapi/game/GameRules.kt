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

    /** Statuts gérés. */
    val STATUSES = setOf("burn", "poison", "paralysis", "sleep", "freeze")

    val STATUS_FR = mapOf(
        "burn" to "brûlé", "poison" to "empoisonné", "paralysis" to "paralysé", "sleep" to "endormi", "freeze" to "gelé",
    )

    val STAT_FR = mapOf(
        "attack" to "l'Attaque", "defense" to "la Défense", "special-attack" to "l'Attaque Spéciale",
        "special-defense" to "la Défense Spéciale", "speed" to "la Vitesse", "accuracy" to "la Précision", "evasion" to "l'Esquive",
    )

    /** Multiplicateur d'un niveau de statistique : ×(2+n)/2 en hausse, ×2/(2-n) en baisse (×4 à +6, ×¼ à -6). */
    fun stageMultiplier(stage: Int): Double = if (stage >= 0) (2 + stage) / 2.0 else 2.0 / (2 - stage)

    /** Précision et esquive : ×(3+n)/3 en hausse, ×3/(3-n) en baisse. */
    fun accuracyMultiplier(stage: Int): Double = if (stage >= 0) (3 + stage) / 3.0 else 3.0 / (3 - stage)

    /**
     * Applique des changements de niveaux (bornés à -6..+6). Renvoie le Pokémon et les phrases du journal
     * (« L'Attaque de Bulbizarre augmente beaucoup ! »).
     */
    fun applyStages(f: Fighter, changes: Map<String, Int>, who: String): Pair<Fighter, List<String>> {
        var stages = f.stages
        val lines = changes.mapNotNull { (stat, change) ->
            val name = STAT_FR[stat] ?: return@mapNotNull null
            val before = stages[stat] ?: 0
            val after = (before + change).coerceIn(-6, 6)
            stages = (stages + (stat to after)).filterValues { it != 0 }
            val label = "${name.replaceFirstChar(Char::uppercase)} de $who"
            when {
                after == before && change > 0 -> "$label ne peut plus augmenter !"
                after == before -> "$label ne peut plus baisser !"
                after - before >= 3 -> "$label augmente énormément !"
                after - before == 2 -> "$label augmente beaucoup !"
                after > before -> "$label augmente !"
                before - after >= 2 -> "$label baisse beaucoup !"
                else -> "$label baisse !"
            }
        }
        return f.copy(stages = stages) to lines
    }

    /** Types insensibles à un statut (un Pokémon Feu ne peut pas être brûlé…). */
    private val STATUS_IMMUNE_TYPES = mapOf(
        "burn" to setOf("fire"), "poison" to setOf("poison", "steel"), "paralysis" to setOf("electric"), "freeze" to setOf("ice"),
    )

    /** Le statut peut-il être infligé ? (pas de deuxième statut, immunités de type) */
    fun canReceive(f: Fighter, status: String): Boolean = when (status) {
        CONFUSION -> f.confusedTurns == 0
        else -> status in STATUSES && f.status == null && f.types.none { it in STATUS_IMMUNE_TYPES[status].orEmpty() }
    }

    /** Confusion : à part des statuts (cumulable avec eux), elle dure 2 à 5 tours et disparaît en quittant le combat. */
    const val CONFUSION = "confusion"

    /** Effets gérés par le jeu : statuts et confusion. */
    val AILMENTS = STATUSES + CONFUSION

    /** Dégâts que se fait un Pokémon confus : attaque physique de puissance 40, sans type, avec ses propres stats. */
    fun confusionDamage(f: Fighter, random: Random): Int {
        val s = stats(f)
        val atk = s.attack * stageMultiplier(f.stages["attack"] ?: 0)
        val def = s.defense * stageMultiplier(f.stages["defense"] ?: 0)
        val damage = (floor(floor(2.0 * f.level / 5 + 2) * 40 * atk / def / 50) + 2) * (0.85 + random.nextDouble() * 0.15)
        return max(1, damage.toInt())
    }

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
        if (checkAccuracy && !hits(attacker, defender, move, random)) return AttackResult(0, false, false, effectiveness)
        if (effectiveness == 0.0) return AttackResult(0, true, false, 0.0)

        val a = stats(attacker)
        val d = stats(defender)
        val special = move.damageClass == "special"
        val critical = random.nextInt(24) == 0
        // Niveaux de statistiques ; un coup critique ignore les baisses du lanceur et les hausses de la cible.
        val atkStage = (attacker.stages[if (special) "special-attack" else "attack"] ?: 0).let { if (critical) maxOf(it, 0) else it }
        val defStage = (defender.stages[if (special) "special-defense" else "defense"] ?: 0).let { if (critical) minOf(it, 0) else it }
        val atk0 = (if (special) a.spAttack else a.attack) * stageMultiplier(atkStage)
        val def = (if (special) d.spDefense else d.defense) * stageMultiplier(defStage)
        // La brûlure divise par deux l'Attaque physique.
        val atk = if (attacker.status == "burn" && move.damageClass == "physical") atk0 / 2 else atk0
        var damage = floor(floor(2.0 * attacker.level / 5 + 2) * move.power * atk / def / 50) + 2
        if (move.type in attacker.types) damage *= 1.5
        damage *= effectiveness
        if (critical) damage *= 1.5
        damage *= 0.85 + random.nextDouble() * 0.15
        return AttackResult(max(1, damage.toInt()), true, critical, effectiveness)
    }

    /** L'attaque touche-t-elle ? Précision de l'attaque, modifiée par la précision du lanceur et l'esquive de la cible. */
    fun hits(attacker: Fighter, defender: Fighter, move: FighterMove, random: Random): Boolean {
        val accuracy = move.accuracy ?: return true
        val stage = ((attacker.stages["accuracy"] ?: 0) - (defender.stages["evasion"] ?: 0)).coerceIn(-6, 6)
        return random.nextDouble() * 100 < accuracy * accuracyMultiplier(stage)
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
            if (m.ailment != null && canReceive(defender, m.ailment)) return 45.0 * accuracy
            if (m.statChanges.isEmpty() || attacker.hp * 2 < attacker.maxHp) return 0.0
            // Modifier les statistiques tant qu'elles ne sont pas déjà bien modifiées
            val target = if (m.statSelf) attacker else defender
            val useful = m.statChanges.any { (stat, change) ->
                val stage = target.stages[stat] ?: 0
                if (change > 0) stage < 2 else stage > -2
            }
            return if (useful) 35.0 * accuracy else 0.0
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
    fun speed(f: Fighter): Int =
        (stats(f).speed * stageMultiplier(f.stages["speed"] ?: 0)).toInt() / if (f.status == "paralysis") 2 else 1

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
