package com.example.pokeapi.game

import com.example.pokeapi.client.PokeApiClient
import com.example.pokeapi.client.pokeApiJson
import com.example.pokeapi.db.GameSaveTable
import com.example.pokeapi.model.EvolutionNode
import com.example.pokeapi.model.LearnedMove
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

/** Version des attaques des parties (2 : drain, soin, coups multiples ; 3 : statistiques ; 4 : peur) ; une partie plus ancienne est mise à jour. */
private const val GAME_MOVES_VERSION = 4

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

    suspend fun view(userId: Int): GameView = lock.withLock { loadMigrated(userId)?.let { toView(userId, it) } ?: GameView(started = false) }

    /**
     * Vérifie chaque Pokémon de l'équipe et de la boîte d'après les données à jour (Pokédex, attaques, types) :
     * nom, images, types, statistiques, faiblesses, détail des attaques, attaques connues à son niveau,
     * attaques équipées (1 à 4, dont une offensive), expérience et PV cohérents. Renvoie la partie et les corrections.
     */
    suspend fun verify(userId: Int): GameView = lock.withLock {
        val s = loadMigrated(userId) ?: return@withLock GameView(started = false)
        val corrections = mutableListOf<String>()
        val details = moveService.details((s.team + s.box).flatMap { f -> f.allMoves.map { it.name } })
        suspend fun check(f: Fighter): Fighter {
            val fixes = mutableListOf<String>()
            var fixed = f
            pokemonService.getPokemon(f.pokemonId.toString())?.let { p ->
                val info = fixed.copy(
                    speciesId = p.speciesId, name = p.name, nameFr = p.nameFr, spriteUrl = p.spriteUrl, artworkUrl = p.artworkUrl,
                    types = p.types, base = p.stats.ifEmpty { fixed.base }, baseExperience = p.baseExperience ?: fixed.baseExperience,
                )
                if (info != fixed) fixes += "informations"
                fixed = info
            }
            typeService.defenses(f.pokemonId.toString())?.multipliers?.associate { it.type to it.multiplier }
                ?.takeIf { it.isNotEmpty() && it != fixed.defenses }
                ?.let { fixed = fixed.copy(defenses = it); fixes += "faiblesses" }

            // Détail des attaques (puissance, précision, effets…)
            fun refresh(m: FighterMove): FighterMove = details[m.name]?.takeIf { it.damageClass != null }?.let {
                m.copy(
                    nameFr = it.nameFr ?: m.nameFr, type = it.type ?: m.type, damageClass = it.damageClass!!, power = it.power ?: 0,
                    accuracy = it.accuracy, priority = it.priority, ailment = it.ailment, ailmentChance = it.ailmentChance,
                    drain = it.drain, healing = it.healing, minHits = it.minHits, maxHits = it.maxHits,
                    statChanges = it.statChanges.filterKeys { stat -> stat in GameRules.STAT_FR }, statSelf = it.statSelf,
                    statChance = it.statChance, flinchChance = it.flinchChance,
                )
            } ?: m
            val moves = fixed.moves.map(::refresh)
            val known = fixed.knownMoves.map(::refresh)
            if (moves != fixed.moves || known != fixed.knownMoves) fixes += "détail des attaques"
            fixed = fixed.copy(moves = moves, knownMoves = known)

            // Attaques connues : toutes celles apprises jusqu'à son niveau
            val missing = learnableMoves(fixed.pokemonId, fixed.level).filter { m -> fixed.allMoves.none { it.name == m.name } }
            if (missing.isNotEmpty()) {
                fixes += "${missing.size} attaque${if (missing.size > 1) "s" else ""} apprise${if (missing.size > 1) "s" else ""} ajoutée${if (missing.size > 1) "s" else ""}"
                fixed = fixed.copy(knownMoves = fixed.knownMoves + missing)
            }
            // Attaques équipées : 1 à 4, sans doublon, dont au moins une offensive
            var equipped = fixed.moves.distinctBy { it.name }.take(4)
            if (equipped.none { it.damageClass != "status" }) {
                val damaging = fixed.allMoves.firstOrNull { it.damageClass != "status" } ?: TACKLE
                equipped = equipped.take(3) + damaging
            }
            if (equipped != fixed.moves) {
                fixes += "attaques équipées"
                fixed = fixed.copy(moves = equipped)
            }
            // Les attaques connues comprennent les attaques équipées
            fixed = fixed.copy(knownMoves = fixed.allMoves)

            // Expérience et PV cohérents avec le niveau
            if (fixed.xp < GameRules.xpForLevel(fixed.level) || fixed.xp >= GameRules.xpForLevel(fixed.level + 1)) {
                fixes += "expérience"
                fixed = fixed.copy(xp = GameRules.xpForLevel(fixed.level))
            }
            if (fixed.hp !in 0..fixed.maxHp) {
                fixes += "PV"
                fixed = fixed.copy(hp = fixed.hp.coerceIn(0, fixed.maxHp))
            }
            if (fixes.isNotEmpty()) corrections += "${fixed.displayName} : ${fixes.joinToString(", ")} corrigé${if (fixes.size > 1) "s" else ""}."
            return fixed
        }
        val checked = s.copy(team = s.team.map { check(it) }, box = s.box.map { check(it) })
        if (checked != s) save(userId, checked)
        toView(userId, checked).copy(corrections = corrections)
    }

    suspend fun newGame(userId: Int, starter: String): GameView = lock.withLock {
        if (starter !in GameRules.STARTERS) throw BadRequestException("Starter inconnu : '$starter' (${GameRules.STARTERS.joinToString()})")
        val first = buildFighter(starter, GameRules.STARTER_LEVEL, "p1")
        val state = GameState(movesVersion = GAME_MOVES_VERSION, starter = starter, team = listOf(first), caught = setOf(first.speciesId), seen = setOf(first.speciesId), uidCounter = 1)
        save(userId, state)
        toView(userId, state)
    }

    suspend fun reset(userId: Int): Unit = lock.withLock {
        newSuspendedTransaction(Dispatchers.IO, database) { GameSaveTable.deleteWhere { id eq userId } }
    }

    suspend fun travel(userId: Int, step: Int): GameView = act(userId) { s ->
        noBattle(s)
        if (step !in GameRules.STEPS.indices) throw BadRequestException("Étape inconnue : $step")
        if (step > unlockedStep(s)) throw BadRequestException("Cette étape n'est pas encore accessible")
        val city = GameRules.STEPS[step].city
        s.copy(stepIndex = step, lastCenter = if (city) step else s.lastCenter)
    }

    suspend fun heal(userId: Int): GameView = act(userId) { s ->
        noBattle(s)
        if (!GameRules.STEPS[s.stepIndex].city) throw BadRequestException("Le Centre Pokémon se trouve dans les villes")
        s.copy(team = s.team.map { it.healed() }, box = s.box.map { it.healed() })
    }

    /** Utilise un objet d'évolution (pierre, Câble Link) sur un Pokémon de l'équipe ou de la boîte. */
    suspend fun useItem(userId: Int, item: String, uid: String): GameView = act(userId) { s ->
        noBattle(s)
        val evolutionItem = GameRules.ITEMS.firstOrNull { it.item == item } ?: throw BadRequestException("Objet inconnu : '$item'")
        if ((s.items[item] ?: 0) <= 0) throw BadRequestException("Vous n'avez pas de ${evolutionItem.nameFr}")
        val f = (s.team + s.box).firstOrNull { it.uid == uid } ?: throw BadRequestException("Pokémon introuvable")
        val target = itemEvolutions(f)[item] ?: throw BadRequestException("${evolutionItem.nameFr} n'a aucun effet sur ${f.displayName}")
        val form = evolve(f, target)
        val log = mutableListOf("Quoi ? ${f.displayName} évolue en ${form.displayName} !")
        // Attaques apprises à l'évolution (Raichu : Poing Éclair…)
        val evolved = learnNewMoves(form, sinceLevel = f.level, s.settings, log, evolved = true)
        val count = (s.items[item] ?: 0) - 1
        s.copy(
            team = s.team.replaceUid(evolved),
            box = s.box.replaceUid(evolved),
            caught = s.caught + evolved.speciesId,
            items = if (count > 0) s.items + (item to count) else s.items - item,
            lastEvent = log.joinToString(" "),
        )
    }

    /** Accueille (ou non) le Pokémon sauvage qui veut rejoindre l'équipe après la victoire. */
    suspend fun recruit(userId: Int, accept: Boolean): GameView = act(userId) { s ->
        val b = s.battle ?: throw BadRequestException("Aucun combat en cours")
        val recruit = b.recruit ?: throw BadRequestException("Aucun Pokémon ne veut vous rejoindre")
        decideRecruit(s, recruit, accept)
    }

    suspend fun setTeam(userId: Int, uids: List<String>): GameView = act(userId) { s ->
        noBattle(s)
        val all = (s.team + s.box).associateBy { it.uid }
        if (uids.isEmpty() || uids.size > 6 || uids.distinct().size != uids.size || uids.any { it !in all }) {
            throw BadRequestException("L'équipe doit contenir de 1 à 6 Pokémon que vous possédez")
        }
        s.copy(team = uids.map { all.getValue(it) }, box = all.values.filter { it.uid !in uids })
    }

    /** Cherche un Pokémon sauvage à l'étape actuelle. */
    suspend fun explore(userId: Int): GameView = act(userId) { s ->
        noBattle(s)
        startWildBattle(s)
    }

    /** Ferme le combat terminé et enchaîne sur le Pokémon sauvage suivant, au même endroit. */
    suspend fun nextEncounter(userId: Int): GameView = act(userId) { s ->
        val b = s.battle ?: throw BadRequestException("Aucun combat en cours")
        if (b.outcome == null) throw BadRequestException("Le combat n'est pas terminé")
        if (b.recruit != null) throw BadRequestException("Décidez d'abord si ${b.recruit.displayName} vous rejoint")
        startWildBattle(s.copy(battle = null))
    }

    /** Choisit les attaques équipées (1 à 4, dans l'ordre donné) parmi celles que le Pokémon connaît. */
    suspend fun setMoves(userId: Int, uid: String, names: List<String>): GameView = act(userId) { s ->
        noBattle(s)
        val f = (s.team + s.box).firstOrNull { it.uid == uid } ?: throw BadRequestException("Pokémon introuvable")
        val known = f.allMoves.associateBy { it.name }
        if (names.isEmpty() || names.size > 4 || names.distinct().size != names.size) {
            throw BadRequestException("Choisissez de 1 à 4 attaques différentes")
        }
        names.firstOrNull { it !in known }?.let { throw BadRequestException("${f.displayName} ne connaît pas l'attaque '$it'") }
        val moves = names.map { known.getValue(it) }
        if (moves.all { it.damageClass == "status" }) throw BadRequestException("Gardez au moins une attaque offensive")
        val updated = f.copy(moves = moves, knownMoves = f.allMoves)
        s.copy(team = s.team.replaceUid(updated), box = s.box.replaceUid(updated))
    }

    suspend fun updateSettings(userId: Int, settings: GameSettings): GameView = act(userId) { s -> s.copy(settings = settings) }

    private suspend fun startWildBattle(s: GameState): GameState {
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
        return s.copy(battle = battle, seen = s.seen + enemy.speciesId)
    }

    /** Défie l'arène de la ville actuelle. */
    suspend fun challengeGym(userId: Int): GameView = act(userId) { s0 ->
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

    suspend fun attack(userId: Int, moveIndex: Int): GameView = act(userId) { s ->
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
        // Un Pokémon confus peut se mettre K.O. lui-même
        if (!acts) return if (ready.fainted) afterHit(state, log) else state
        val defender = if (playerTurn) e else state.team.first { it.uid == battle.activeUid }
        val (user, hitDefender) = hit(ready, defender, move, enemy = !playerTurn, log)
        state = if (playerTurn) state.copy(team = state.team.replaceUid(user)).withEnemy(hitDefender)
        else state.withEnemy(user).copy(team = state.team.replaceUid(hitDefender))
        return afterHit(state, log)
    }

    /**
     * Ce qui peut empêcher d'attaquer, dans l'ordre des jeux : sommeil et gel, peur, confusion (le Pokémon
     * se blesse 1 fois sur 3), paralysie.
     */
    private fun statusBeforeMove(f0: Fighter, enemy: Boolean, log: MutableList<String>): Pair<Fighter, Boolean> {
        var f = f0
        val who = if (enemy) "${f.displayName} adverse" else f.displayName
        when (f.status) {
            "sleep" -> if (f.sleepTurns > 1) {
                log += "$who dort profondément."
                return f.copy(sleepTurns = f.sleepTurns - 1) to false
            } else {
                log += "$who se réveille !"
                f = f.copy(status = null, sleepTurns = 0)
            }
            "freeze" -> if (random.nextInt(5) == 0) {
                log += "$who n'est plus gelé !"
                f = f.copy(status = null)
            } else {
                log += "$who est gelé et ne peut pas attaquer."
                return f to false
            }
        }
        if (f.flinched) {
            log += "$who a peur ! Il ne peut pas attaquer."
            return f.copy(flinched = false) to false
        }
        if (f.confusedTurns > 0) {
            f = f.copy(confusedTurns = f.confusedTurns - 1)
            if (f.confusedTurns == 0) {
                log += "$who n'est plus confus !"
            } else {
                log += "$who est confus…"
                if (random.nextInt(3) == 0) {
                    val damage = minOf(f.hp, GameRules.confusionDamage(f, random))
                    log += "Il se blesse dans sa confusion ! (-$damage PV)"
                    return f.copy(hp = f.hp - damage) to false
                }
            }
        }
        if (f.status == "paralysis" && random.nextInt(4) == 0) {
            log += "$who est paralysé ! Il ne peut pas attaquer."
            return f to false
        }
        return f to true
    }

    /** Fin du tour : dégâts du poison et de la brûlure. */
    private suspend fun endOfTurn(s: GameState, log: MutableList<String>): GameState {
        // La peur ne dure qu'un tour ; on compte les tours passés au combat (Bluff).
        var state = s.battle?.let { b ->
            s.copy(team = s.team.map { if (it.uid == b.activeUid) it.copy(flinched = false, battleTurns = it.battleTurns + 1) else it })
                .withEnemy(b.enemy.copy(flinched = false, battleTurns = b.enemy.battleTurns + 1))
        } ?: s
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

    suspend fun switchTo(userId: Int, uid: String): GameView = act(userId) { s ->
        val b = activeBattle(s)
        val target = s.team.firstOrNull { it.uid == uid } ?: throw BadRequestException("Ce Pokémon n'est pas dans l'équipe")
        if (target.fainted) throw BadRequestException("${target.displayName} est K.O.")
        if (uid == b.activeUid) throw BadRequestException("${target.displayName} est déjà au combat")
        val log = mutableListOf("Go, ${target.displayName} !")
        // Le Pokémon qui sort perd ses changements de statistiques.
        var state = s.copy(
            team = s.team.map { if (it.uid == b.activeUid) it.leftBattle() else it },
            battle = b.copy(activeUid = uid, mustSwitch = false, participants = b.participants + uid),
        )
        if (!b.mustSwitch) {
            // Changement volontaire : l'adversaire attaque le Pokémon qui entre.
            val enemyMove = GameRules.chooseMove(b.enemy, target, random)
            state = takeTurn(state, playerTurn = false, enemyMove, log)
            state = endOfTurn(state, log)
        }
        state.withLog(log)
    }

    suspend fun flee(userId: Int): GameView = act(userId) { s ->
        val b = activeBattle(s)
        if (b.kind != BattleKind.WILD) throw BadRequestException("Impossible de fuir un combat d'arène")
        s.copy(team = s.team.map { it.leftBattle() }, battle = b.copy(outcome = BattleOutcome.FLED, log = b.log + "Vous prenez la fuite."))
    }

    /** Ferme un combat terminé. */
    suspend fun closeBattle(userId: Int): GameView = act(userId) { s ->
        val b = s.battle ?: throw BadRequestException("Aucun combat en cours")
        if (b.outcome == null) throw BadRequestException("Le combat n'est pas terminé")
        // Fermer sans répondre revient à laisser partir le Pokémon.
        val decided = b.recruit?.let { decideRecruit(s, it, accept = false) } ?: s
        decided.copy(battle = null)
    }

    // ---------- Combat ----------

    /**
     * Le lanceur utilise une attaque : soin, statut, ou dégâts (en plusieurs coups pour Combo-Griffe…),
     * avec PV drainés (Vole-Vie…) ou contrecoup (Damoclès…). Renvoie le lanceur et la cible modifiés.
     */
    private fun hit(attacker: Fighter, defender: Fighter, move: FighterMove, enemy: Boolean, lines: MutableList<String>): Pair<Fighter, Fighter> {
        val who = if (enemy) "${attacker.displayName} adverse" else attacker.displayName
        val target = if (enemy) defender.displayName else "${defender.displayName} adverse"
        lines += "$who utilise ${move.nameFr ?: move.name} !"

        if (move.heals) {
            val heal = GameRules.healAmount(attacker, move)
            if (move.name == FighterMove.REST) {
                if (heal == 0 && attacker.status == null) return attacker to defender.also { lines += "Mais cela échoue." }
                lines += "$who s'endort et récupère tous ses PV !"
                return attacker.copy(hp = attacker.maxHp, status = "sleep", sleepTurns = 3) to defender
            }
            if (heal <= 0) return attacker to defender.also { lines += "Mais ses PV sont déjà au maximum." }
            lines += "$who récupère $heal PV."
            return attacker.copy(hp = attacker.hp + heal) to defender
        }

        if (move.damageClass == "status") {
            val ailment = move.ailment
            // Une attaque sur soi (Danse Lames, Reflet…) ne peut pas rater
            if (!(move.statSelf && move.statChanges.isNotEmpty()) && !GameRules.hits(attacker, defender, move, random)) {
                return attacker to defender.also { lines += "Mais l'attaque échoue." }
            }
            var user = attacker
            var hitTarget = defender
            if (move.statChanges.isNotEmpty()) {
                if (move.statSelf) {
                    val (changed, stageLines) = GameRules.applyStages(user, move.statChanges, who)
                    user = changed
                    lines += stageLines
                } else {
                    val (changed, stageLines) = GameRules.applyStages(hitTarget, move.statChanges, target)
                    hitTarget = changed
                    lines += stageLines
                }
            }
            // Ultrason, Onde Folie, Rodomondade (Attaque +2 puis confusion)…
            if (ailment != null) {
                if (GameRules.canReceive(hitTarget, ailment)) hitTarget = inflict(hitTarget, ailment, target, lines)
                else if (move.statChanges.isEmpty()) lines += if (ailment == GameRules.CONFUSION) "$target est déjà confus !" else "Mais cela n'a aucun effet."
            }
            return user to hitTarget
        }

        // Bluff (100 % de peur) ne marche qu'au premier tour du lanceur au combat.
        if (move.flinchChance >= 100 && attacker.battleTurns > 0) return attacker to defender.also { lines += "Mais cela échoue." }

        // Coups successifs : la précision n'est vérifiée qu'au premier, l'attaque s'arrête si la cible est K.O.
        val hits = GameRules.hitCount(move, random)
        var hp = defender.hp
        var dealt = 0
        var landed = 0
        var effectiveness = 1.0
        for (i in 0 until hits) {
            val result = GameRules.attack(attacker, defender.copy(hp = hp), move, random, checkAccuracy = i == 0)
            if (!result.hit) return attacker to defender.also { lines += "Mais l'attaque échoue." }
            effectiveness = result.effectiveness
            if (result.effectiveness == 0.0) break
            if (result.critical) lines += "Coup critique !"
            val damage = minOf(result.damage, hp)
            hp -= damage
            dealt += damage
            landed++
            if (hp <= 0) break
        }
        GameRules.effectivenessText(effectiveness)?.let { lines += it }
        if (dealt > 0) lines += "${defender.displayName} perd $dealt PV."
        if (hits > 1 && landed > 0) lines += "Touché $landed fois !"
        var damaged = defender.copy(hp = hp)

        // PV drainés ou contrecoup, proportionnels aux dégâts infligés
        var user = attacker
        if (dealt > 0 && move.drain > 0 && attacker.hp < attacker.maxHp) {
            val gain = minOf(attacker.maxHp - attacker.hp, maxOf(1, dealt * move.drain / 100))
            lines += "$target a son énergie drainée ! $who récupère $gain PV."
            user = attacker.copy(hp = attacker.hp + gain)
        } else if (dealt > 0 && move.drain < 0) {
            val recoil = minOf(attacker.hp, maxOf(1, dealt * -move.drain / 100))
            lines += "$who est blessé par le contrecoup ! (-$recoil PV)"
            user = attacker.copy(hp = attacker.hp - recoil)
        }

        // Statistiques modifiées par une attaque offensive (Poing Boost, Acide 1 fois sur 10…)
        if (dealt > 0 && move.statChanges.isNotEmpty() && random.nextInt(100) < move.statChance) {
            if (move.statSelf && user.hp > 0) {
                val (changed, stageLines) = GameRules.applyStages(user, move.statChanges, who)
                user = changed
                lines += stageLines
            } else if (!move.statSelf && hp > 0) {
                val (changed, stageLines) = GameRules.applyStages(damaged, move.statChanges, target)
                damaged = changed
                lines += stageLines
            }
        }

        // Peur (Morsure 3 fois sur 10…) : sans effet si la cible a déjà attaqué ce tour-ci
        if (hp > 0 && dealt > 0 && move.flinchChance > 0 && random.nextInt(100) < move.flinchChance) damaged = damaged.copy(flinched = true)

        // Effet secondaire (Flammèche brûle 1 fois sur 10, Rafale Psy rend confus…)
        val ailment = move.ailment
        if (hp > 0 && dealt > 0 && ailment != null && move.ailmentChance > 0 &&
            GameRules.canReceive(damaged, ailment) && random.nextInt(100) < move.ailmentChance
        ) damaged = inflict(damaged, ailment, target, lines)
        return user to damaged
    }

    private fun inflict(f: Fighter, status: String, who: String, lines: MutableList<String>): Fighter {
        if (status == GameRules.CONFUSION) {
            lines += "$who devient confus !"
            return f.copy(confusedTurns = random.nextInt(2, 6))
        }
        lines += "$who est ${GameRules.STATUS_FR[status]} !"
        return f.copy(status = status, sleepTurns = if (status == "sleep") random.nextInt(1, 4) else 0)
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
                state = state.copy(battle = battle.copy(enemyIndex = battle.enemyIndex + 1, participants = setOf(battle.activeUid)))
                // Lanceur K.O. par le contrecoup en même temps que l'adversaire
                if (state.team.first { it.uid == battle.activeUid }.fainted) {
                    log += "${player.displayName} est K.O. !"
                    if (state.team.any { !it.fainted }) return state.copy(battle = state.battle!!.copy(mustSwitch = true))
                    return defeat(evolveTeam(state, log), log)
                }
                return state
            }
            return victory(state, log)
        }
        if (player.fainted) {
            log += "${player.displayName} est K.O. !"
            if (state.team.any { !it.fainted }) return state.copy(battle = b.copy(mustSwitch = true))
            return defeat(evolveTeam(state, log), log)
        }
        return state
    }

    /** Expérience pour les Pokémon qui ont combattu ; une montée de niveau fait apprendre aussitôt les nouvelles attaques. */
    private suspend fun shareXp(s: GameState, enemy: Fighter, log: MutableList<String>): GameState {
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
            if (after.level == f.level) return@map after
            log += "${f.displayName} monte au niveau ${after.level} !"
            learnNewMoves(after, sinceLevel = f.level, s.settings, log)
        }
        return s.copy(team = team)
    }

    private suspend fun victory(s: GameState, log: MutableList<String>): GameState {
        val b = s.battle!!
        var state = s
        if (b.kind == BattleKind.WILD) {
            val enemy = b.enemy
            state = state.copy(wins = state.wins + (b.step to (state.wins[b.step] ?: 0) + 1))
            GameRules.drop(enemy.types, gym = false, random)?.let { item ->
                log += "${enemy.displayName} laisse tomber : ${item.nameFr} ! Vous la ramassez."
                state = state.withItem(item)
            }
            val alreadyCaught = enemy.speciesId in state.caught
            if (alreadyCaught && state.settings.recruitOnlyNew) {
                // Réglage « seulement les nouveaux Pokémon » : pas de proposition pour une espèce déjà obtenue.
                log += "${enemy.displayName} s'éloigne (déjà dans votre Pokédex)."
            } else if (random.nextDouble() < GameRules.joinChance(enemy.captureRate)) {
                val recruit = enemy.copy(uid = "p${state.uidCounter + 1}", xp = GameRules.xpForLevel(enemy.level)).healed()
                log += "${enemy.displayName} veut vous rejoindre !" + if (enemy.speciesId in state.caught) " (vous en avez déjà un)" else " (nouveau !)"
                state = state.copy(battle = state.battle!!.copy(recruit = recruit))
            } else {
                log += "${enemy.displayName} s'éloigne."
            }
        } else {
            val type = state.gymTypes[b.step]
            val newCap = GameRules.levelCap(state.badges.size + 1)
            val reward = GameRules.drop(emptyList(), gym = true, random)!!
            log += "Vous avez gagné ! Vous obtenez le Badge ${TYPES_FR[type] ?: "d'arène"}. Niveau maximum : $newCap."
            log += "Le champion vous remet : ${reward.nameFr} !"
            state = state.copy(badges = state.badges + b.step).withItem(reward)
        }
        state = evolveTeam(state, log)
        return state.copy(team = state.team.map { it.leftBattle() }, battle = state.battle!!.copy(outcome = BattleOutcome.WON))
    }

    private fun defeat(s: GameState, log: MutableList<String>): GameState {
        log += "Votre équipe est K.O. ! Vous retournez au dernier Centre Pokémon visité et vos Pokémon sont soignés."
        return s.copy(
            team = s.team.map { it.healed().leftBattle() },
            stepIndex = s.lastCenter,
            battle = s.battle!!.copy(outcome = BattleOutcome.LOST),
        )
    }

    /**
     * À la fin d'un combat (gagné ou perdu) : évolutions par niveau, comme dans les jeux.
     */
    private suspend fun evolveTeam(s: GameState, log: MutableList<String>): GameState {
        var caught = s.caught
        val team = s.team.map { f ->
            val target = levelEvolution(f) ?: return@map f
            val evolved = evolve(f, target)
            log += "Quoi ? ${f.displayName} évolue en ${evolved.displayName} !"
            caught = caught + evolved.speciesId
            learnNewMoves(evolved, sinceLevel = f.level - 1, s.settings, log, evolved = true)
        }
        return s.copy(team = team, caught = caught)
    }

    /**
     * Ajoute aux attaques connues celles que le Pokémon peut maintenant utiliser. Les attaques des niveaux
     * au-delà de [sinceLevel] sont annoncées : équipées s'il reste une place, sinon elles remplacent la plus
     * ancienne (réglage [GameSettings.autoReplaceMoves]) ou attendent que le joueur les équipe. Les autres
     * (attaques d'une nouvelle forme après évolution) rejoignent les attaques connues sans être équipées.
     * Après une évolution ([evolved]), les attaques « à l'évolution » (niveau 0) sont apprises aussi.
     */
    private suspend fun learnNewMoves(
        f: Fighter, sinceLevel: Int, settings: GameSettings, log: MutableList<String>, evolved: Boolean = false,
    ): Fighter {
        val known = f.allMoves
        val fresh = learnableMoves(f.pokemonId, f.level).filter { m -> known.none { it.name == m.name } }
        if (fresh.isEmpty()) return f
        var moves = f.moves
        fresh.filter { (it.level ?: 0) > sinceLevel || (evolved && it.level == 0) }.sortedBy { it.level ?: 0 }.forEach { m ->
            val name = m.nameFr ?: m.name
            if (moves.size < 4) {
                moves = moves + m
                log += "${f.displayName} apprend $name !"
                return@forEach
            }
            // La plus ancienne attaque dont le remplacement laisse au moins une attaque offensive.
            val replaced = if (!settings.autoReplaceMoves) null else moves.indices.sortedBy { replaceOrder(moves[it]) }.firstOrNull { i ->
                moves.filterIndexed { j, _ -> j != i }.plus(m).any { it.damageClass != "status" }
            }
            if (replaced == null) {
                log += "${f.displayName} peut apprendre $name : équipez-la depuis « Gérer l'équipe »."
            } else {
                log += "${f.displayName} oublie ${moves[replaced].nameFr ?: moves[replaced].name} et apprend $name !"
                moves = moves.mapIndexed { i, x -> if (i == replaced) m else x }
            }
        }
        return f.copy(moves = moves, knownMoves = known + fresh)
    }

    /** Ordre de remplacement automatique : les plus anciennes d'abord, les attaques d'évolution en dernier. */
    private fun replaceOrder(m: FighterMove): Int = when (val level = m.level) {
        null -> -1
        0 -> Int.MAX_VALUE
        else -> level
    }

    /** Le Pokémon évolue : même niveau, expérience, statut et proportion de PV. */
    private suspend fun evolve(f: Fighter, targetId: Int): Fighter {
        val evolved = buildFighter(targetId.toString(), f.level, f.uid)
        return evolved.copy(
            xp = f.xp,
            hp = (evolved.maxHp * f.hp.toDouble() / f.maxHp).roundToInt().coerceIn(if (f.fainted) 0 else 1, evolved.maxHp),
            status = f.status,
            sleepTurns = f.sleepTurns,
            // Les attaques choisies sont conservées ; celles de la nouvelle forme s'y ajoutent (learnNewMoves).
            moves = f.moves,
            knownMoves = f.allMoves,
        )
    }

    /** Objets qui font évoluer ce Pokémon : objet -> id du Pokémon obtenu. */
    private suspend fun itemEvolutions(f: Fighter): Map<String, Int> {
        val chain = evolutionService.evolutions(f.pokemonId.toString()) ?: return emptyMap()
        fun find(nodes: List<EvolutionNode>): EvolutionNode? =
            nodes.firstNotNullOfOrNull { n -> if (n.pokemon?.id == f.pokemonId) n else find(n.evolvesTo) }
        val children = find(chain.roots)?.evolvesTo.orEmpty().map { (it.pokemon?.id ?: it.speciesId) to it.conditions }
        return GameRules.ITEMS.mapNotNull { item -> GameRules.itemEvolution(children, item)?.let { item.item to it } }.toMap()
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
            captureRate = captureRate, moves = emptyList(), defenses = defenses,
        )
        val learnable = learnableMoves(p.id, level)
        return fighter.copy(hp = fighter.maxHp, moves = defaultMoves(learnable), knownMoves = learnable)
    }

    /**
     * Attaques apprises par niveau jusqu'à [level] et utilisables en combat, de la plus récente à la plus
     * ancienne : offensives, de statut infligeant un statut géré (Cage Éclair, Poudre Dodo…) ou de soin.
     */
    private suspend fun learnableMoves(pokemonId: Int, level: Int): List<FighterMove> =
        moveService.moves(pokemonId.toString())?.moves.orEmpty()
            .filter { it.method == "level-up" && (it.level ?: 0) <= level }
            .sortedByDescending { it.level ?: 0 }
            .distinctBy { it.name }
            .filter {
                ((it.power ?: 0) > 0 && it.damageClass in setOf("physical", "special")) ||
                    (it.damageClass == "status" && (it.ailment in GameRules.AILMENTS || it.healing > 0 || it.name == FighterMove.REST ||
                        it.statChanges.keys.any { stat -> stat in GameRules.STAT_FR }))
            }
            .map(::toFighterMove)

    private fun toFighterMove(m: LearnedMove) = FighterMove(
        m.name, m.nameFr, m.type ?: "normal", m.damageClass!!, m.power ?: 0, m.accuracy, m.priority, m.ailment, m.ailmentChance,
        m.level, drain = m.drain, healing = m.healing, minHits = m.minHits, maxHits = m.maxHits,
        statChanges = m.statChanges.filterKeys { it in GameRules.STAT_FR }, statSelf = m.statSelf, statChance = m.statChance,
        flinchChance = m.flinchChance,
    )

    /** Attaques équipées par défaut : les 4 plus récentes (2 de statut au plus), dont au moins une offensive (Charge à défaut). */
    private fun defaultMoves(learnable: List<FighterMove>): List<FighterMove> {
        val damaging = learnable.filter { it.damageClass != "status" }
        val statusMoves = learnable.filter { it.damageClass == "status" }.take(2).toSet()
        val chosen = learnable.filter { it in damaging || it in statusMoves }.take(4)
        val moves = if (chosen.none { it in damaging }) chosen.take(3) + listOfNotNull(damaging.firstOrNull()) else chosen
        return if (moves.none { it.damageClass != "status" }) moves.take(3) + TACKLE else moves
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

    private suspend fun toView(userId: Int, s0: GameState): GameView {
        val unlocked = unlockedStep(s0)
        // Le type d'une arène est révélé dès qu'elle est accessible.
        var s = s0
        GameRules.STEPS.forEachIndexed { i, step -> if (step.gym && i <= unlocked) s = withGymType(s, i) }
        if (s != s0) save(userId, s)

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
                canContinue = b.kind == BattleKind.WILD && b.outcome in setOf(BattleOutcome.WON, BattleOutcome.FLED) &&
                    s.settings.nextEncounterButton && s.team.any { !it.fainted } && pool(s.stepIndex, s.badges.size).isNotEmpty(),
            )
        }
        // Objets possédés, avec les Pokémon sur lesquels ils ont un effet.
        val owned = GameRules.ITEMS.filter { (s.items[it.item] ?: 0) > 0 }
        val usable = if (owned.isEmpty() || s.battle != null) emptyMap()
        else (s.team + s.box).associate { f -> f.uid to itemEvolutions(f).keys }
        val items = owned.map { item ->
            ItemView(
                item = item.item, name = item.nameFr, count = s.items[item.item] ?: 0,
                usableOn = usable.filterValues { item.item in it }.keys.toList(),
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
            items = items,
            lastEvent = s.lastEvent,
            settings = s.settings,
        )
    }

    private fun Fighter.toView(): FighterView {
        val start = GameRules.xpForLevel(level)
        return FighterView(
            uid, pokemonId, speciesId, displayName, spriteUrl, artworkUrl, types, level, hp, maxHp,
            xpInLevel = xp - start, xpToNext = GameRules.xpForLevel(level + 1) - start, moves = moves,
            knownMoves = allMoves.filter { m -> moves.none { it.name == m.name } }, fainted = fainted,
            status = status,
            stages = stages.filterValues { it != 0 },
            confused = confusedTurns > 0,
        )
    }

    private suspend fun act(userId: Int, change: suspend (GameState) -> GameState): GameView = lock.withLock {
        val state = loadMigrated(userId) ?: throw NotFoundException("Aucune partie en cours")
        // Le message d'évènement ne vaut que pour l'action qui l'a produit.
        val updated = change(state.copy(lastEvent = null))
        save(userId, updated)
        toView(userId, updated)
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

    private fun GameState.withItem(item: EvolutionItem): GameState = copy(items = items + (item.item to (items[item.item] ?: 0) + 1))

    private fun Fighter.healed(): Fighter = copy(hp = maxHp, status = null, sleepTurns = 0)

    private fun List<Fighter>.replaceAt(index: Int, f: Fighter) = mapIndexed { i, x -> if (i == index) f else x }
    private fun List<Fighter>.replaceUid(f: Fighter) = map { if (it.uid == f.uid) f else it }

    /**
     * Partie sauvegardée, complétée pour les versions précédentes : les Pokémon sans liste d'attaques connues
     * reçoivent celles de leur niveau (les attaques équipées ne changent pas).
     */
    private suspend fun loadMigrated(userId: Int): GameState? {
        val s = load(userId) ?: return null
        if (s.movesVersion >= GAME_MOVES_VERSION && (s.team + s.box).all { it.knownMoves.isNotEmpty() }) return s
        // Attaques complétées avec leurs effets (drain, soin, coups multiples) ; les attaques de soin
        // désormais utilisables rejoignent les attaques connues.
        val details = moveService.details(
            (s.team + s.box + s.battle?.enemies.orEmpty() + listOfNotNull(s.battle?.recruit)).flatMap { f -> f.allMoves.map { it.name } },
        )
        suspend fun migrate(f: Fighter): Fighter {
            fun refresh(m: FighterMove) = details[m.name]?.let {
                m.copy(
                    drain = it.drain, healing = it.healing, minHits = it.minHits, maxHits = it.maxHits,
                    statChanges = it.statChanges.filterKeys { stat -> stat in GameRules.STAT_FR }, statSelf = it.statSelf, statChance = it.statChance,
                    flinchChance = it.flinchChance,
                )
            } ?: m
            val moves = f.moves.map(::refresh)
            return f.copy(moves = moves, knownMoves = (f.allMoves.map(::refresh) + learnableMoves(f.pokemonId, f.level)).distinctBy { it.name })
        }
        val battle = s.battle?.let { b -> b.copy(enemies = b.enemies.map { migrate(it) }, recruit = b.recruit?.let { migrate(it) }) }
        return s.copy(team = s.team.map { migrate(it) }, box = s.box.map { migrate(it) }, battle = battle, movesVersion = GAME_MOVES_VERSION)
            .also { save(userId, it) }
    }

    private suspend fun load(userId: Int): GameState? = newSuspendedTransaction(Dispatchers.IO, database) {
        GameSaveTable.selectAll().where { GameSaveTable.id eq userId }.singleOrNull()
            ?.let { pokeApiJson.decodeFromString(GameState.serializer(), it[GameSaveTable.data]) }
    }

    private suspend fun save(userId: Int, state: GameState): Unit = newSuspendedTransaction(Dispatchers.IO, database) {
        GameSaveTable.upsert {
            it[id] = userId
            it[data] = pokeApiJson.encodeToString(GameState.serializer(), state)
            it[updatedAt] = Instant.now()
        }
    }
}
