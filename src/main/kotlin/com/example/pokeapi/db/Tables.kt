package com.example.pokeapi.db

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/** Pokémon mis en cache depuis pokeapi.co. L'id est celui de PokéAPI. */
object PokemonTable : Table("pokemon") {
    val id = integer("id")
    val name = varchar("name", 100).uniqueIndex()
    /** Nom français (issu de pokemon-species), null si indisponible. */
    val nameFr = varchar("name_fr", 100).nullable()
    /** Numéro de génération (1 = Kanto … 9 = Paldea), null si l'espèce n'a pas encore été récupérée. */
    val generation = integer("generation").nullable().index()
    val region = varchar("region", 50).nullable().index()
    val height = integer("height")
    val weight = integer("weight")
    val baseExperience = integer("base_experience").nullable()
    val spriteUrl = varchar("sprite_url", 500).nullable()
    val artworkUrl = varchar("artwork_url", 500).nullable()
    /** Espèce (numéro du Pokédex national) ; null pour les lignes importées avant les formes régionales (= id). */
    val speciesId = integer("species_id").nullable()
    val isDefault = bool("is_default").default(true)
    /** Région de la forme régionale (alola, galar, hisui, paldea), null pour les autres Pokémon. */
    val regionalForm = varchar("regional_form", 20).nullable()
    /** Formes régionales de l'espèce déjà importées (Pokémon par défaut uniquement). */
    val formsChecked = bool("forms_checked").default(false)
    /** Chaîne d'évolution PokéAPI de l'espèce (null tant que l'espèce n'a pas été récupérée). */
    val evolutionChainId = integer("evolution_chain_id").nullable()
    /** Facilité de capture de l'espèce (3 à 255) ; null pour les lignes importées avant le jeu d'aventure. */
    val captureRate = integer("capture_rate").nullable()
    /** Somme des statistiques de base, stockée pour pouvoir trier dessus. */
    val statTotal = integer("stat_total").default(0)
    val fetchedAt = timestamp("fetched_at")

    override val primaryKey = PrimaryKey(id)
}

object PokemonTypeTable : Table("pokemon_type") {
    val pokemonId = integer("pokemon_id").references(PokemonTable.id, onDelete = ReferenceOption.CASCADE)
    val slot = integer("slot")
    val typeName = varchar("type_name", 50).index()

    override val primaryKey = PrimaryKey(pokemonId, slot)
}

object PokemonStatTable : Table("pokemon_stat") {
    val pokemonId = integer("pokemon_id").references(PokemonTable.id, onDelete = ReferenceOption.CASCADE)
    val statName = varchar("stat_name", 50)
    val baseStat = integer("base_stat")

    override val primaryKey = PrimaryKey(pokemonId, statName)
}

object PokemonAbilityTable : Table("pokemon_ability") {
    val pokemonId = integer("pokemon_id").references(PokemonTable.id, onDelete = ReferenceOption.CASCADE)
    val slot = integer("slot")
    val abilityName = varchar("ability_name", 100)
    val isHidden = bool("is_hidden")

    override val primaryKey = PrimaryKey(pokemonId, slot)
}

/** Équipes créées par les utilisateurs de l'API (6 Pokémon max). */
object TeamTable : IntIdTable("team") {
    val name = varchar("name", 100)
    val createdAt = timestamp("created_at")
}

object TeamMemberTable : Table("team_member") {
    val teamId = reference("team_id", TeamTable, onDelete = ReferenceOption.CASCADE)
    val position = integer("position")
    val pokemonId = integer("pokemon_id").references(PokemonTable.id, onDelete = ReferenceOption.CASCADE)

    override val primaryKey = PrimaryKey(teamId, position)
}

/** Chaînes d'évolution, stockées telles que renvoyées par PokéAPI (JSON). */
object EvolutionChainTable : Table("evolution_chain") {
    val id = integer("id")
    val data = text("data")
    /** Version du format stocké : les chaînes d'un format plus ancien sont récupérées à nouveau. */
    val format = integer("format").default(1)
    val fetchedAt = timestamp("fetched_at")

    override val primaryKey = PrimaryKey(id)
}

/** Cache des noms français (objets, capacités, lieux, espèces) utilisés dans les conditions d'évolution. */
object TranslationTable : Table("translation") {
    val kind = varchar("kind", 30)
    val slug = varchar("slug", 100)
    /** null si PokéAPI n'a pas de nom français : on ne redemande pas. */
    val nameFr = varchar("name_fr", 100).nullable()

    override val primaryKey = PrimaryKey(kind, slug)
}

/** Attaques (partagées par tous les Pokémon), récupérées à la demande. */
object MoveTable : Table("move") {
    val name = varchar("name", 100)
    val nameFr = varchar("name_fr", 100).nullable()
    val type = varchar("type", 30).nullable()
    /** physical, special ou status */
    val damageClass = varchar("damage_class", 20).nullable()
    val power = integer("power").nullable()
    val accuracy = integer("accuracy").nullable()
    val pp = integer("pp").nullable()
    val priority = integer("priority").default(0)
    val descriptionFr = text("description_fr").nullable()
    val fetchedAt = timestamp("fetched_at")

    override val primaryKey = PrimaryKey(name)
}

/** Attaques apprises par un Pokémon dans chaque version de jeu (JSON compact, voir StoredLearnset). */
object LearnsetTable : Table("pokemon_learnset") {
    val pokemonId = integer("pokemon_id").references(PokemonTable.id, onDelete = ReferenceOption.CASCADE)
    val data = text("data")

    override val primaryKey = PrimaryKey(pokemonId)
}

/** Talents (partagés par tous les Pokémon), récupérés à la demande. */
object AbilityTable : Table("ability") {
    val name = varchar("name", 100)
    val nameFr = varchar("name_fr", 100).nullable()
    /** Texte du jeu le plus récent, en français. */
    val descriptionFr = text("description_fr").nullable()
    /** Effet détaillé (français si disponible, sinon anglais : voir effectLanguage). */
    val effect = text("effect").nullable()
    val effectLanguage = varchar("effect_language", 5).nullable()
    val generation = integer("generation").nullable()
    /** Pokémon ayant ce talent (JSON : liste de {id, name, hidden}). */
    val pokemon = text("pokemon")
    val fetchedAt = timestamp("fetched_at")

    override val primaryKey = PrimaryKey(name)
}

/** Relations de dégâts reçus par type (côté défense), noms de types séparés par des virgules. */
object TypeRelationTable : Table("type_relation") {
    val type = varchar("type", 30)
    val doubleFrom = text("double_from")
    val halfFrom = text("half_from")
    val noFrom = text("no_from")
    val fetchedAt = timestamp("fetched_at")

    override val primaryKey = PrimaryKey(type)
}

/** Régions dont la liste des lieux est en cache (nombre de lieux attendu). */
object RegionCacheTable : Table("region_cache") {
    val name = varchar("name", 30)
    val nameFr = varchar("name_fr", 50).nullable()
    val locationCount = integer("location_count")
    val fetchedAt = timestamp("fetched_at")

    override val primaryKey = PrimaryKey(name)
}

/** Lieux des régions (villes, routes, grottes…) et, une fois consultées, leurs rencontres. */
object LocationTable : Table("location") {
    val name = varchar("name", 100)
    val region = varchar("region", 30).index()
    /** Position dans la liste PokéAPI de la région. */
    val position = integer("position")
    val nameFr = varchar("name_fr", 100).nullable()
    /** Zones du lieu, séparées par des virgules (vide = pas de données de rencontre). */
    val areas = text("areas")
    /** Rencontres regroupées (JSON, voir StoredLocationEncounters), null tant que le lieu n'a pas été consulté. */
    val encounters = text("encounters").nullable()
    val fetchedAt = timestamp("fetched_at")

    override val primaryKey = PrimaryKey(name)
}

/** Versions de jeu (Rouge, Bleu…) : nom français et groupe de versions (pour l'ordre chronologique). */
object VersionTable : Table("version_info") {
    val name = varchar("name", 50)
    val nameFr = varchar("name_fr", 100).nullable()
    val versionGroup = varchar("version_group", 50).nullable()

    override val primaryKey = PrimaryKey(name)
}

/** Sauvegarde du jeu d'aventure (une seule partie, id = 1). */
object GameSaveTable : Table("game_save") {
    val id = integer("id")
    val data = text("data")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}
