package com.example.pokeapi.game

import com.example.pokeapi.client.PokeApiClient
import com.example.pokeapi.client.pokeApiJson
import com.example.pokeapi.db.GameSaveTable
import com.example.pokeapi.model.EvolutionNode
import com.example.pokeapi.model.PokemonQuery
import com.example.pokeapi.repository.PokemonRepository
import com.example.pokeapi.service.EvolutionService
import com.example.pokeapi.service.LocationService
import com.example.pokeapi.service.MoveService
import com.example.pokeapi.service.PokemonService
import com.example.pokeapi.service.TypeService
import io.ktor.server.plugins.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.upsert
import java.time.Instant
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/** Une rencontre possible dans un lieu (version Vert Feuille). */
private data class PoolEntry(val pokemonId: Int, val types: List<String>, val method: String, val minLevel: Int, val maxLevel: Int, val chance: Int)

private val TYPES_FR = mapOf(
    "normal" to "Normal", "fire" to "Feu", "water" to "Eau", "grass" to "Plante", "electric" to "Électrik",
    "ice" to "Glace", "fighting" to "Combat", "poison" to "Poison", "ground" to "Sol", "flying" to "Vol",
    "psychic" to "Psy", "bug" to "Insecte", "rock" to "Roche", "ghost" to "Spectre", "dragon" to "Dragon",
    "dark" to "Ténèbres", "steel" to "Acier", "fairy" to "Fée",
)

/** Types des arènes quand les Pokémon rencontrés ne suffisent pas à en choisir un. */
private val FALLBACK_GYM_TYPES = listOf("rock", "water", "electric", "grass", "poison", "psychic", "fire", "ground", "ice", "fighting", "ghost", "dragon")

private val TACKLE = FighterMove("tackle", "Charge", "normal", "physical", 40, 100)
private val LEVEL_EVOLUTION = Regex("^Niveau (\\d+)$")

/**
 * Jeu d'aventure : progression dans Kanto, combats, recrutement des Pokémon vaincus, arènes et badges.
 * Une seule partie, sauvegardée en base après chaque action.
 */
class GameService(
    private val database: Database,
    private val pokemonService: PokemonService,
    private val pokemonRepository: PokemonRepository,
    private val moveService: MoveService,
    private val typeService: TypeService,
    private val evolutionService: EvolutionService,
    private val locationService: LocationService,
    private val pokeApi: PokeApiClient,
    private val random: Random = Random.Default,
) {
    private val lock = Mutex()
    private val pools = mutableMapOf<String, List<PoolEntry>>()

    // ---------- Actions ----------

    suspend fun view(): GameView = lock.withLock { load()?.let { toView(it) } ?: GameView(started = false) }

    suspend fun newGame(starter: String): GameView = lock.withLock {
        if (starter !in GameRules.STARTERS) throw BadRequestException("Starter inconnu : '$starter' (${GameRules.STARTERS.joinToString()})")
        val first = buildFighter(starter, GameRules.STARTER_LEVEL, "p1")
        val state = GameState(starter = starter, team = listOf(first), caught = setOf(first.speciesId), seen = setOf(first.speciesId), uidCounter = 1)
        save(state)
        toView(state)
    }

    suspend fun reset(): Unit = lock.withLock {
        newSuspendedTransaction(Dispatchers.IO, database) { GameSaveTable.deleteWhere { id eq 1 } }
    }

    suspend fun travel(step: Int): GameView = act { s ->
        noBattle(s)
        if (step !in GameRules.STEPS.indices) throw BadRequestException("Étape inconnue : $step")
        if (step > unlockedStep(s)) throw BadRequestException("Cette étape n'est pas encore accessible")
        val city = GameRules.STEPS[step].city
        s.copy(stepIndex = step, lastCenter = if (city) step else s.lastCenter)
    }

    suspend fun heal(): GameView = act { s ->
        noBattle(s)
        if (!GameRules.STEPS[s.stepIndex].city) throw BadRequestException("Le Centre Pokémon se trouve dans les villes")
        s.copy(team = s.team.map { it.healed() }, box = s.box.map { it.healed() })
    }

    /** Achète une pierre d'évolution (dans une ville). */
    suspend fun buy(item: String): GameView = act { s ->
        noBattle(s)
        if (!GameRules.STEPS[s.stepIndex].city) throw BadRequestException("La boutique se trouve dans les villes")
        val stone = GameRules.STONES.firstOrNull { it.item == item } ?: throw BadRequestException("Objet inconnu : '$item'")
        if (s.money < stone.price) throw BadRequestException("Il vous manque ${stone.price - s.money} ₽ pour acheter ${stone.nameFr}")
        s.copy(money = s.money - stone.price, items = s.items + (item to (s.items[item] ?: 0) + 1))
    }

    /** Utilise une pierre d'évolution sur un Pokémon de l'équipe ou de la boîte. */
    suspend fun useItem(item: String, uid: String): GameView = act { s ->
        noBattle(s)
        val stone = GameRules.STONES.firstOrNull { it.item == item } ?: throw BadRequestException("Objet inconnu : '$item'")
        if ((s.items[item] ?: 0) <= 0) throw BadRequestException("Vous n'avez pas de ${stone.nameFr}")
        val f = (s.team + s.box).firstOrNull { it.uid == uid } ?: throw BadRequestException("Pokémon introuvable")
        val target = stoneEvolutions(f)[item] ?: throw BadRequestException("${stone.nameFr} n'a aucun effet sur ${f.displayName}")
        val evolved = evolve(f, target)
        val count = (s.items[item] ?: 0) - 1
        s.copy(
            team = s.team.replaceUid(evolved),
            box = s.box.replaceUid(evolved),
            caught = s.caught + evolved.speciesId,
            items = if (count > 0) s.items + (item to count) else s.items - item,
            lastEvent = "Quoi ? ${f.displayName} évolue en ${evolved.displayName} !",
        )
    }

    /** Accueille (ou non) le Pokémon sauvage qui veut rejoindre l'équipe après la victoire. */
    suspend fun recruit(accept: Boolean): GameView = act { s ->
        val b = s.battle ?: throw BadRequestException("Aucun combat en cours")
        val recruit = b.recruit ?: throw BadRequestException("Aucun Pokémon ne veut vous rejoindre")
        decideRecruit(s, recruit, accept)
    }

    suspend fun setTeam(uids: List<String>): GameView = act { s ->
        noBattle(s)
        val all = (s.team + s.box).associateBy { it.uid }
        if (uids.isEmpty() || uids.size > 6 || uids.distinct().size != uids.size || uids.any { it !in all }) {
            throw BadRequestException("L'équipe doit contenir de 1 à 6 Pokémon que vous possédez")
        }
        s.copy(team = uids.map { all.getValue(it) }, box = all.values.filter { it.uid !in uids })
    }

    /** Cherche un Pokémon sauvage à l'étape actuelle. */
    suspend fun explore(): GameView = act { s ->
        noBattle(s)
        val active = s.team.firstOrNull { !it.fainted } ?: throw BadRequestException("Votre équipe est K.O. : passez au Centre Pokémon")
        val pool = pool(s.stepIndex, s.badges.size)
        if (pool.isEmpty()) throw BadRequestException("Aucun Pokémon sauvage ici")
        val entry = weightedPick(pool)
        val level = random.nextInt(entry.minLevel, entry.maxLevel + 1)
        val enemy = buildFighter(entry.pokemonId.toString(), level, "wild")
        val battle = Battle(
            kind = BattleKind.WILD, step = s.stepIndex, enemies = listOf(enemy), activeUid = active.uid,
            participants = setOf(active.uid), log = listOf("Un ${enemy.displayName} sauvage (niv. $level) apparaît !", "Go, ${active.displayName} !"),
        )
        s.copy(battle = battle, seen = s.seen + enemy.speciesId)
    }

    /** Défie l'arène de la ville actuelle. */
    suspend fun challengeGym(): GameView = act { s0 ->
        noBattle(s0)
        val step = GameRules.STEPS[s0.stepIndex]
        if (!step.gym) throw BadRequestException("Pas d'arène ici")
        if (s0.stepIndex in s0.badges) throw BadRequestException("Vous avez déjà le badge de cette arène")
        val active = s0.team.firstOrNull { !it.fainted } ?: throw BadRequestException("Votre équipe est K.O. : passez au Centre Pokémon")
        val s = withGymType(s0, s0.stepIndex)
        val type = s.gymTypes.getValue(s.stepIndex)
        val enemies = gymTeam(s.stepIndex, type, gymTopLevel(s))
        val battle = Battle(
            kind = BattleKind.GYM, step = s.stepIndex, enemies = enemies, activeUid = active.uid, participants = setOf(active.uid),
            log = listOf(
                "Le champion de l'arène (type ${TYPES_FR[type] ?: type}) vous défie avec ${enemies.size} Pokémon !",
                "Le champion envoie ${enemies.first().displayName} (niv. ${enemies.first().level}) !",
                "Go, ${active.displayName} !",
            ),
        )
        s.copy(battle = battle, seen = s.seen + enemies.map { it.speciesId })
    }

    suspend fun attack(moveIndex: Int): GameView = act { s ->
        val b = activeBattle(s)
        if (b.mustSwitch) throw BadRequestException("Choisissez le Pokémon à envoyer")
        val player = s.team.first { it.uid == b.activeUid }
        val move = player.moves.getOrNull(moveIndex) ?: throw BadRequestException("Attaque inconnue")
        val enemyMove = GameRules.chooseMove(b.enemy, player, random)
        val playerFirst = GameRules.firstToAct(player, move, b.enemy, enemyMove, random)

        var state = s
        val log = mutableListOf<String>()
        val order = if (playerFirst) listOf(true, false) else listOf(false, true)
        for (playerTurn in order) {
            state = takeTurn(state, playerTurn, if (playerTurn) move else enemyMove, log)
        }
        state = endOfTurn(state, log)
        state.withLog(log)
    }

    /** Un Pokémon agit : statut qui l'en empêche (sommeil, gel, paralysie), puis attaque. */
    private suspend fun takeTurn(s: GameState, playerTurn: Boolean, move: FighterMove, log: MutableList<String>): GameState {
        val battle = s.battle!!
        if (battle.outcome != null || battle.mustSwitch) return s
        val p = s.team.first { it.uid == battle.activeUid }
        val e = battle.enemy
        if (p.fainted || e.fainted) return s
        val attacker = if (playerTurn) p else e
        val (ready, acts) = statusBeforeMove(attacker, enemy = !playerTurn, log)
        var state = if (playerTurn) s.copy(team = s.team.replaceUid(ready)) else s.withEnemy(ready)
        if (!acts) return state
        val defender = if (playerTurn) e else state.team.first { it.uid == battle.activeUid }
        val (hitDefender, lines) = hit(ready, defender, move, enemy = !playerTurn)
        log += lines
        state = if (playerTurn) state.withEnemy(hitDefender) else state.copy(team = state.team.replaceUid(hitDefender))
        return afterHit(state, log)
    }

    /** Sommeil, gel et paralysie peuvent empêcher d'attaquer. */
    private fun statusBeforeMove(f: Fighter, enemy: Boolean, log: MutableList<String>): Pair<Fighter, Boolean> {
        val who = if (enemy) "${f.displayName} adverse" else f.displayName
        return when (f.status) {
            "sleep" -> if (f.sleepTurns > 1) {
                log += "$who dort profondément."
                f.copy(sleepTurns = f.sleepTurns - 1) to false
            } else {
                log += "$who se réveille !"
                f.copy(status = null, sleepTurns = 0) to true
            }
            "freeze" -> if (random.nextInt(5) == 0) {
                log += "$who n'est plus gelé !"
                f.copy(status = null) to true
            } else {
                log += "$who est gelé et ne peut pas attaquer."
                f to false
            }
            "paralysis" -> if (random.nextInt(4) == 0) {
                log += "$who est paralysé ! Il ne peut pas attaquer."
                f to false
            } else f to true
            else -> f to true
        }
    }

    /** Fin du tour : dégâts du poison et de la brûlure. */
    private suspend fun endOfTurn(s: GameState, log: MutableList<String>): GameState {
        var state = s
        for (playerSide in listOf(true, false)) {
            val b = state.battle ?: return state
            if (b.outcome != null || b.mustSwitch) return state
            val f = if (playerSide) state.team.first { it.uid == b.activeUid } else b.enemy
            val damage = GameRules.residualDamage(f)
            if (damage == 0 || f.fainted) continue
            val who = if (playerSide) f.displayName else "${f.displayName} adverse"
            log += if (f.status == "poison") "$who souffre du poison." else "$who souffre de sa brûlure."
            val hurt = f.copy(hp = (f.hp - damage).coerceAtLeast(0))
            log += "${f.displayName} perd ${f.hp - hurt.hp} PV."
            state = if (playerSide) state.copy(team = state.team.replaceUid(hurt)) else state.withEnemy(hurt)
            state = afterHit(state, log)
        }
        return state
    }

    suspend fun switchTo(uid: String): GameView = act { s ->
        val b = activeBattle(s)
        val target = s.team.firstOrNull { it.uid == uid } ?: throw BadRequestException("Ce Pokémon n'est pas dans l'équipe")
        if (target.fainted) throw BadRequestException("${target.displayName} est K.O.")
        if (uid == b.activeUid) throw BadRequestException("${target.displayName} est déjà au combat")
        val log = mutableListOf("Go, ${target.displayName} !")
        var state = s.copy(battle = b.copy(activeUid = uid, mustSwitch = false, participants = b.participants + uid))
        if (!b.mustSwitch) {
            // Changement volontaire : l'adversaire attaque le Pokémon qui entre.
            val enemyMove = GameRules.chooseMove(b.enemy, target, random)
            state = takeTurn(state, playerTurn = false, enemyMove, log)
            state = endOfTurn(state, log)
        }
        state.withLog(log)
    }

    suspend fun flee(): GameView = act { s ->
        val b = activeBattle(s)
        if (b.kind != BattleKind.WILD) throw BadRequestException("Impossible de fuir un combat d'arène")
        s.copy(battle = b.copy(outcome = BattleOutcome.FLED, log = b.log + "Vous prenez la fuite."))
    }

    /** Ferme un combat terminé. */
    suspend fun closeBattle(): GameView = act { s ->
        val b = s.battle ?: throw BadRequestException("Aucun combat en cours")
        if (b.outcome == null) throw BadRequestException("Le combat n'est pas terminé")
        // Fermer sans répondre revient à laisser partir le Pokémon.
        val decided = b.recruit?.let { decideRecruit(s, it, accept = false) } ?: s
        decided.copy(battle = null)
    }

    // ---------- Combat ----------

    private fun hit(attacker: Fighter, defender: Fighter, move: FighterMove, enemy: Boolean): Pair<Fighter, List<String>> {
        val who = if (enemy) "${attacker.displayName} adverse" else attacker.displayName
        val target = if (enemy) defender.displayName else "${defender.displayName} adverse"
        val lines = mutableListOf("$who utilise ${move.nameFr ?: move.name} !")

        if (move.damageClass == "status") {
            val ailment = move.ailment
            if (move.accuracy != null && random.nextInt(100) >= move.accuracy) return defender to (lines + "Mais l'attaque échoue.")
            if (ailment == null || !GameRules.canReceive(defender, ailment)) return defender to (lines + "Mais cela n'a aucun effet.")
            return inflict(defender, ailment, target, lines)
        }

        val result = GameRules.attack(attacker, defender, move, random)
        if (!result.hit) return defender to (lines + "Mais l'attaque échoue.")
        if (result.critical) lines += "Coup critique !"
        GameRules.effectivenessText(result.effectiveness)?.let { lines += it }
        val hp = (defender.hp - result.damage).coerceAtLeast(0)
        if (result.damage > 0) lines += "${defender.displayName} perd ${defender.hp - hp} PV."
        val damaged = defender.copy(hp = hp)
        // Effet secondaire (Flammèche brûle 1 fois sur 10…)
        val ailment = move.ailment
        if (hp > 0 && result.damage > 0 && ailment != null && move.ailmentChance > 0 &&
            GameRules.canReceive(damaged, ailment) && random.nextInt(100) < move.ailmentChance
        ) return inflict(damaged, ailment, target, lines)
        return damaged to lines
    }

    private fun inflict(f: Fighter, status: String, who: String, lines: MutableList<String>): Pair<Fighter, List<String>> {
        lines += "$who est ${GameRules.STATUS_FR[status]} !"
        return f.copy(status = status, sleepTurns = if (status == "sleep") random.nextInt(1, 4) else 0) to lines
    }

    /** Après une attaque : K.O. éventuels, expérience, fin du combat. */
    private suspend fun afterHit(s: GameState, log: MutableList<String>): GameState {
        var state = s
        val b = state.battle!!
        val enemy = b.enemy
        val player = state.team.first { it.uid == b.activeUid }

        if (enemy.fainted) {
            log += "${enemy.displayName} adverse est K.O. !"
            state = shareXp(state, enemy, log)
            val battle = state.battle!!
            if (battle.enemyIndex + 1 < battle.enemies.size) {
                val next = battle.enemies[battle.enemyIndex + 1]
                log += "Le champion envoie ${next.displayName} (niv. ${next.level}) !"
                return state.copy(battle = battle.copy(enemyIndex = battle.enemyIndex + 1, participants = setOf(battle.activeUid)))
            }
            return victory(state, log)
        }
        if (player.fainted) {
            log += "${player.displayName} est K.O. !"
            if (state.team.any { !it.fainted }) return state.copy(battle = b.copy(mustSwitch = true))
            return defeat(state, log)
        }
        return state
    }

    private fun shareXp(s: GameState, enemy: Fighter, log: MutableList<String>): GameState {
        val b = s.battle!!
        val cap = GameRules.levelCap(s.badges.size)
        val gain = GameRules.xpReward(enemy, b.kind == BattleKind.GYM)
        val team = s.team.map { f ->
            if (f.uid !in b.participants || f.fainted) return@map f
            if (f.level >= cap) {
                log += "${f.displayName} a atteint le niveau maximum ($cap) : obtenez un badge pour progresser."
                return@map f
            }
            val after = GameRules.gainXp(f, gain, cap)
            log += "${f.displayName} gagne $gain points d'expérience."
            if (after.level > f.level) log += "${f.displayName} monte au niveau ${after.level} !"
            after
        }
        return s.copy(team = team)
    }

    private suspend fun victory(s: GameState, log: MutableList<String>): GameState {
        val b = s.battle!!
        var state = s
        if (b.kind == BattleKind.WILD) {
            val enemy = b.enemy
            val money = GameRules.moneyReward(enemy.level, gym = false)
            log += "Vous gagnez $money ₽."
            state = state.copy(wins = state.wins + (b.step to (state.wins[b.step] ?: 0) + 1), money = state.money + money)
            if (random.nextDouble() < GameRules.joinChance(enemy.captureRate)) {
                val recruit = enemy.copy(uid = "p${state.uidCounter + 1}", xp = GameRules.xpForLevel(enemy.level)).healed()
                log += "${enemy.displayName} veut vous rejoindre !" + if (enemy.speciesId in state.caught) " (vous en avez déjà un)" else " (nouveau !)"
                state = state.copy(battle = state.battle!!.copy(recruit = recruit))
            } else {
                log += "${enemy.displayName} s'éloigne."
            }
        } else {
            val type = state.gymTypes[b.step]
            val newCap = GameRules.levelCap(state.badges.size + 1)
            val money = GameRules.moneyReward(b.enemies.maxOf { it.level }, gym = true)
            log += "Vous avez gagné ! Vous obtenez le Badge ${TYPES_FR[type] ?: "d'arène"}. Niveau maximum : $newCap."
            log += "Le champion vous remet $money ₽."
            state = state.copy(badges = state.badges + b.step, money = state.money + money)
        }
        state = evolveAndLearn(state, log)
        return state.copy(battle = state.battle!!.copy(outcome = BattleOutcome.WON))
    }

    private fun defeat(s: GameState, log: MutableList<String>): GameState {
        log += "Votre équipe est K.O. ! Vous retournez au dernier Centre Pokémon visité et vos Pokémon sont soignés."
        return s.copy(
            team = s.team.map { it.healed() },
            stepIndex = s.lastCenter,
            battle = s.battle!!.copy(outcome = BattleOutcome.LOST),
        )
    }

    /** Après une victoire : évolutions par niveau et nouvelles attaques. */
    private suspend fun evolveAndLearn(s: GameState, log: MutableList<String>): GameState {
        var caught = s.caught
        val team = s.team.map { f ->
            val target = levelEvolution(f)
            val updated = if (target != null) {
                val evolved = evolve(f, target)
                log += "Quoi ? ${f.displayName} évolue en ${evolved.displayName} !"
                caught = caught + evolved.speciesId
                evolved
            } else {
                f.copy(moves = battleMoves(f.pokemonId, f.level))
            }
            updated.moves.filter { m -> m.name !in f.moves.map { it.name } }.forEach { m ->
                log += "${updated.displayName} apprend ${m.nameFr ?: m.name} !"
            }
            updated
        }
        return s.copy(team = team, caught = caught)
    }

    /** Le Pokémon évolue : même niveau, expérience, statut et proportion de PV. */
    private suspend fun evolve(f: Fighter, targetId: Int): Fighter {
        val evolved = buildFighter(targetId.toString(), f.level, f.uid)
        return evolved.copy(
            xp = f.xp,
            hp = (evolved.maxHp * f.hp.toDouble() / f.maxHp).roundToInt().coerceIn(if (f.fainted) 0 else 1, evolved.maxHp),
            status = f.status,
            sleepTurns = f.sleepTurns,
        )
    }

    /** Pierres qui font évoluer ce Pokémon : objet -> id du Pokémon obtenu. */
    private suspend fun stoneEvolutions(f: Fighter): Map<String, Int> {
        val chain = evolutionService.evolutions(f.pokemonId.toString()) ?: return emptyMap()
        fun find(nodes: List<EvolutionNode>): EvolutionNode? =
            nodes.firstNotNullOfOrNull { n -> if (n.pokemon?.id == f.pokemonId) n else find(n.evolvesTo) }
        val children = find(chain.roots)?.evolvesTo.orEmpty().map { (it.pokemon?.id ?: it.speciesId) to it.conditions }
        return GameRules.STONES.mapNotNull { stone -> GameRules.stoneEvolution(children, stone)?.let { stone.item to it } }.toMap()
    }

    private fun decideRecruit(s: GameState, recruit: Fighter, accept: Boolean): GameState {
        val b = s.battle!!
        if (!accept) {
            return s.copy(battle = b.copy(recruit = null, log = b.log + "Vous laissez partir ${recruit.displayName}."))
        }
        val inTeam = s.team.size < 6
        val line = "${recruit.displayName} rejoint " + if (inTeam) "votre équipe !" else "votre boîte (équipe complète)."
        return s.copy(
            team = if (inTeam) s.team + recruit else s.team,
            box = if (inTeam) s.box else s.box + recruit,
            caught = s.caught + recruit.speciesId,
            uidCounter = s.uidCounter + 1,
            battle = b.copy(recruit = null, log = b.log + line),
        )
    }

    /** Évolution par niveau atteinte (« Niveau 16 » sans autre condition), id du Pokémon obtenu. */
    private suspend fun levelEvolution(f: Fighter): Int? {
        val chain = evolutionService.evolutions(f.pokemonId.toString()) ?: return null
        fun find(nodes: List<EvolutionNode>): EvolutionNode? =
            nodes.firstNotNullOfOrNull { n -> if (n.pokemon?.id == f.pokemonId) n else find(n.evolvesTo) }
        val node = find(chain.roots) ?: return null
        return node.evolvesTo.firstOrNull { child ->
            child.conditions.any { c -> LEVEL_EVOLUTION.matchEntire(c)?.groupValues?.get(1)?.toInt()?.let { it <= f.level } == true }
        }?.let { it.pokemon?.id ?: it.speciesId }
    }

    // ---------- Construction des Pokémon ----------

    private suspend fun buildFighter(idOrName: String, level: Int, uid: String): Fighter {
        val p = pokemonService.getPokemon(idOrName) ?: throw NotFoundException("Pokémon '$idOrName' introuvable")
        val captureRate = p.captureRate ?: runCatching { pokeApi.getSpecies(p.speciesId.toString())?.captureRate }.getOrNull()
            ?.also { pokemonRepository.setCaptureRate(p.id, it) } ?: 45
        val defenses = typeService.defenses(p.id.toString())?.multipliers?.associate { it.type to it.multiplier }.orEmpty()
        val fighter = Fighter(
            uid = uid, pokemonId = p.id, speciesId = p.speciesId, name = p.name, nameFr = p.nameFr,
            spriteUrl = p.spriteUrl, artworkUrl = p.artworkUrl, types = p.types, level = level,
            xp = GameRules.xpForLevel(level), hp = 1, base = p.stats, baseExperience = p.baseExperience ?: 60,
            captureRate = captureRate, moves = battleMoves(p.id, level), defenses = defenses,
        )
        return fighter.copy(hp = fighter.maxHp)
    }

    /**
     * Les 4 attaques apprises par niveau les plus récentes : offensives, ou de statut infligeant un statut géré
     * (Cage Éclair, Poudre Dodo…). Au moins une attaque offensive (Charge à défaut).
     */
    private suspend fun battleMoves(pokemonId: Int, level: Int): List<FighterMove> {
        val learned = moveService.moves(pokemonId.toString())?.moves.orEmpty()
            .filter { it.method == "level-up" && (it.level ?: 0) <= level }
            .sortedByDescending { it.level ?: 0 }
            .distinctBy { it.name }
        fun toMove(m: com.example.pokeapi.model.LearnedMove) =
            FighterMove(m.name, m.nameFr, m.type ?: "normal", m.damageClass!!, m.power ?: 0, m.accuracy, m.priority, m.ailment, m.ailmentChance)
        val damaging = learned.filter { (it.power ?: 0) > 0 && it.damageClass in setOf("physical", "special") }
        val status = learned.filter { it.damageClass == "status" && it.ailment in GameRules.STATUSES }
        val chosen = learned.filter { it in damaging || it in status }.take(4)
        val moves = if (chosen.none { it in damaging }) {
            chosen.take(3) + (damaging.firstOrNull()?.let { listOf(it) } ?: emptyList())
        } else chosen
        val result = moves.map(::toMove)
        return if (result.none { it.damageClass != "status" }) result.take(3) + TACKLE else result
    }

    /**
     * Niveau du Pokémon le plus fort d'une arène : 3 niveaux au-dessus des Pokémon sauvages rencontrés jusque-là,
     * sans dépasser la limite de niveau du joueur.
     */
    private suspend fun gymTopLevel(s: GameState): Int {
        val wildMax = (0 until s.stepIndex).maxOfOrNull { i -> pool(i, s.badges.size).maxOfOrNull { it.maxLevel } ?: 0 } ?: 0
        return GameRules.gymTopLevel(wildMax, GameRules.levelCap(s.badges.size))
    }

    /** Équipe d'une arène : Pokémon de Kanto du type de l'arène, de plus en plus forts au fil des arènes. */
    private suspend fun gymTeam(step: Int, type: String, topLevel: Int): List<Fighter> {
        val number = GameRules.gymNumber(step)
        val size = minOf(6, 2 + number / 2)
        val target = 280 + 45 * number
        val candidates = pokemonService.list(PokemonQuery(limit = 100, types = listOf(type), region = "kanto")).items
            .filter { it.regionalForm == null }
            .sortedBy { abs(it.statTotal - target) }
            .take(size)
            .sortedBy { it.statTotal }
            .ifEmpty { throw BadRequestException("Importez le Pokédex de Kanto pour défier les arènes") }
        return candidates.mapIndexed { i, p ->
            buildFighter(p.id.toString(), (topLevel - (candidates.size - 1 - i) * 2).coerceAtLeast(2), "gym$i")
        }
    }

    // ---------- Progression ----------

    /** Rencontres possibles à une étape, selon les méthodes autorisées par les badges. */
    private suspend fun pool(step: Int, badges: Int): List<PoolEntry> {
        val location = GameRules.STEPS[step].location
        val all = pools.getOrPut(location) {
            val detail = locationService.location(location) ?: return@getOrPut emptyList()
            detail.areas.flatMap { a -> a.encounters.filter { it.version == GameRules.VERSION } }.flatMap { e ->
                e.methods.map { m -> PoolEntry(e.pokemonId, e.types, m.method, m.minLevel, m.maxLevel, m.chance) }
            }
        }
        val allowed = GameRules.allowedMethods(badges)
        return all.filter { it.method in allowed }
    }

    private fun weightedPick(pool: List<PoolEntry>): PoolEntry {
        val total = pool.sumOf { it.chance.coerceAtLeast(1) }
        var roll = random.nextInt(total)
        for (entry in pool) {
            roll -= entry.chance.coerceAtLeast(1)
            if (roll < 0) return entry
        }
        return pool.last()
    }

    private suspend fun cleared(s: GameState, step: Int): Boolean {
        val def = GameRules.STEPS[step]
        return when {
            def.city -> !def.gym || step in s.badges
            pool(step, s.badges.size).isEmpty() -> true
            else -> (s.wins[step] ?: 0) >= GameRules.ROUTE_WINS
        }
    }

    /** Étape la plus lointaine accessible : la première qui n'est pas encore franchie. */
    private suspend fun unlockedStep(s: GameState): Int {
        for (i in GameRules.STEPS.indices) if (!cleared(s, i)) return i
        return GameRules.STEPS.lastIndex
    }

    /** Type d'une arène : le plus fréquent chez les Pokémon sauvages rencontrés depuis l'arène précédente. */
    private suspend fun withGymType(s: GameState, step: Int): GameState {
        if (step in s.gymTypes) return s
        val previousGym = (step - 1 downTo 0).firstOrNull { GameRules.STEPS[it].gym } ?: -1
        val counts = mutableMapOf<String, Int>()
        for (i in (previousGym + 1) until step) {
            if (GameRules.STEPS[i].city) continue
            pool(i, 8).distinctBy { it.pokemonId }.flatMap { it.types }.forEach { counts[it] = (counts[it] ?: 0) + 1 }
        }
        val used = s.gymTypes.values.toSet() + "normal"
        val type = counts.entries.filter { it.key !in used }.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .firstOrNull()?.key ?: FALLBACK_GYM_TYPES.first { it !in used }
        return s.copy(gymTypes = s.gymTypes + (step to type))
    }

    // ---------- Vue, sauvegarde ----------

    private suspend fun toView(s0: GameState): GameView {
        val unlocked = unlockedStep(s0)
        // Le type d'une arène est révélé dès qu'elle est accessible.
        var s = s0
        GameRules.STEPS.forEachIndexed { i, step -> if (step.gym && i <= unlocked) s = withGymType(s, i) }
        if (s != s0) save(s)

        val names = locationService.regionLocations("kanto")?.locations?.associate { it.name to it.nameFr }.orEmpty()
        val steps = GameRules.STEPS.mapIndexed { i, step ->
            val hasEncounters = pool(i, s.badges.size).isNotEmpty()
            StepView(
                index = i, location = step.location, nameFr = names[step.location], city = step.city,
                gym = if (step.gym) GymView(GameRules.gymNumber(i) + 1, s.gymTypes[i], i in s.badges) else null,
                wins = s.wins[i] ?: 0,
                winsNeeded = if (step.city || !hasEncounters) 0 else GameRules.ROUTE_WINS,
                hasEncounters = hasEncounters,
                cleared = cleared(s, i),
                unlocked = i <= unlocked,
            )
        }
        val battle = s.battle?.let { b ->
            BattleView(
                kind = b.kind,
                title = if (b.kind == BattleKind.GYM) "Arène ${names[GameRules.STEPS[b.step].location] ?: ""}".trim() else "Pokémon sauvage",
                enemy = b.enemy.toView(),
                enemyRemaining = b.enemies.size - b.enemyIndex,
                active = s.team.first { it.uid == b.activeUid }.toView(),
                log = b.log.takeLast(40),
                mustSwitch = b.mustSwitch,
                outcome = b.outcome,
                canFlee = b.kind == BattleKind.WILD && b.outcome == null,
                recruit = b.recruit?.toView(),
                recruitIsNew = b.recruit?.let { it.speciesId !in s.caught } ?: false,
            )
        }
        // Pierres : effet calculé seulement pour celles qu'on possède (évite des recherches inutiles).
        val owned = GameRules.STONES.filter { (s.items[it.item] ?: 0) > 0 }
        val usable = if (owned.isEmpty() || s.battle != null) emptyMap()
        else (s.team + s.box).associate { f -> f.uid to stoneEvolutions(f).keys }
        val items = GameRules.STONES.map { stone ->
            ItemView(
                item = stone.item, name = stone.nameFr, price = stone.price, count = s.items[stone.item] ?: 0,
                usableOn = if (stone in owned) usable.filterValues { stone.item in it }.keys.toList() else emptyList(),
            )
        }
        return GameView(
            started = true,
            currentStep = s.stepIndex,
            unlockedStep = unlocked,
            badges = s.badges.size,
            levelCap = GameRules.levelCap(s.badges.size),
            caught = s.caught.count { it <= GameRules.DEX_SIZE },
            seen = s.seen.count { it <= GameRules.DEX_SIZE },
            steps = steps,
            team = s.team.map { it.toView() },
            box = s.box.map { it.toView() },
            battle = battle,
            money = s.money,
            items = items,
            shopOpen = GameRules.STEPS[s.stepIndex].city && s.battle == null,
            lastEvent = s.lastEvent,
        )
    }

    private fun Fighter.toView(): FighterView {
        val start = GameRules.xpForLevel(level)
        return FighterView(
            uid, pokemonId, speciesId, displayName, spriteUrl, artworkUrl, types, level, hp, maxHp,
            xpInLevel = xp - start, xpToNext = GameRules.xpForLevel(level + 1) - start, moves = moves, fainted = fainted,
            status = status,
        )
    }

    private suspend fun act(change: suspend (GameState) -> GameState): GameView = lock.withLock {
        val state = load() ?: throw NotFoundException("Aucune partie en cours")
        // Le message d'évènement ne vaut que pour l'action qui l'a produit.
        val updated = change(state.copy(lastEvent = null))
        save(updated)
        toView(updated)
    }

    private fun noBattle(s: GameState) {
        if (s.battle != null) throw BadRequestException("Terminez d'abord le combat en cours")
    }

    private fun activeBattle(s: GameState): Battle {
        val b = s.battle ?: throw BadRequestException("Aucun combat en cours")
        if (b.outcome != null) throw BadRequestException("Le combat est terminé")
        return b
    }

    private fun GameState.withLog(lines: List<String>): GameState = copy(battle = battle?.let { it.copy(log = it.log + lines) })

    private fun GameState.withEnemy(f: Fighter): GameState =
        copy(battle = battle!!.copy(enemies = battle.enemies.replaceAt(battle.enemyIndex, f)))

    private fun Fighter.healed(): Fighter = copy(hp = maxHp, status = null, sleepTurns = 0)

    private fun List<Fighter>.replaceAt(index: Int, f: Fighter) = mapIndexed { i, x -> if (i == index) f else x }
    private fun List<Fighter>.replaceUid(f: Fighter) = map { if (it.uid == f.uid) f else it }

    private suspend fun load(): GameState? = newSuspendedTransaction(Dispatchers.IO, database) {
        GameSaveTable.selectAll().where { GameSaveTable.id eq 1 }.singleOrNull()
            ?.let { pokeApiJson.decodeFromString(GameState.serializer(), it[GameSaveTable.data]) }
    }

    private suspend fun save(state: GameState): Unit = newSuspendedTransaction(Dispatchers.IO, database) {
        GameSaveTable.upsert {
            it[id] = 1
            it[data] = pokeApiJson.encodeToString(GameState.serializer(), state)
            it[updatedAt] = Instant.now()
        }
    }
}
