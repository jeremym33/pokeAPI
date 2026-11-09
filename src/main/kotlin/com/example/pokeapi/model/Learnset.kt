package com.example.pokeapi.model

import com.example.pokeapi.client.PokeApiMoveSlot
import kotlinx.serialization.Serializable

/** Format de stockage compact : version de jeu -> attaques (m = attaque, k = méthode, l = niveau). */
@Serializable
data class StoredLearnset(val versionGroups: Map<String, List<StoredLearn>> = emptyMap())

@Serializable
data class StoredLearn(val m: String, val k: String, val l: Int = 0)

fun learnsetOf(moves: List<PokeApiMoveSlot>): StoredLearnset {
    val byVersion = mutableMapOf<String, MutableList<StoredLearn>>()
    for (slot in moves) {
        for (d in slot.versionGroupDetails) {
            byVersion.getOrPut(d.versionGroup.name) { mutableListOf() }
                .add(StoredLearn(slot.move.name, d.moveLearnMethod.name, d.levelLearnedAt))
        }
    }
    return StoredLearnset(byVersion.mapValues { (_, v) -> v.distinct() })
}

/** Versions de jeu (groupes de versions PokéAPI) dans l'ordre chronologique, avec leur nom français. */
enum class VersionGroupInfo(val apiName: String, val label: String, val spinOff: Boolean = false) {
    RED_GREEN_JAPAN("red-green-japan", "Rouge / Vert (Japon)", spinOff = true),
    RED_BLUE("red-blue", "Rouge / Bleu"),
    BLUE_JAPAN("blue-japan", "Bleu (Japon)", spinOff = true),
    YELLOW("yellow", "Jaune"),
    GOLD_SILVER("gold-silver", "Or / Argent"),
    CRYSTAL("crystal", "Cristal"),
    RUBY_SAPPHIRE("ruby-sapphire", "Rubis / Saphir"),
    EMERALD("emerald", "Émeraude"),
    COLOSSEUM("colosseum", "Colosseum", spinOff = true),
    FIRERED_LEAFGREEN("firered-leafgreen", "Rouge Feu / Vert Feuille"),
    XD("xd", "XD : Le Souffle des Ténèbres", spinOff = true),
    DIAMOND_PEARL("diamond-pearl", "Diamant / Perle"),
    PLATINUM("platinum", "Platine"),
    HEARTGOLD_SOULSILVER("heartgold-soulsilver", "Or HeartGold / Argent SoulSilver"),
    BLACK_WHITE("black-white", "Noir / Blanc"),
    BLACK_2_WHITE_2("black-2-white-2", "Noir 2 / Blanc 2"),
    X_Y("x-y", "X / Y"),
    OMEGA_RUBY_ALPHA_SAPPHIRE("omega-ruby-alpha-sapphire", "Rubis Oméga / Saphir Alpha"),
    SUN_MOON("sun-moon", "Soleil / Lune"),
    ULTRA_SUN_ULTRA_MOON("ultra-sun-ultra-moon", "Ultra-Soleil / Ultra-Lune"),
    LETS_GO("lets-go-pikachu-lets-go-eevee", "Let's Go Pikachu / Évoli"),
    SWORD_SHIELD("sword-shield", "Épée / Bouclier"),
    ISLE_OF_ARMOR("the-isle-of-armor", "L'Île Solitaire de l'Armure"),
    CROWN_TUNDRA("the-crown-tundra", "Les Terres Enneigées de la Couronne"),
    BRILLIANT_DIAMOND_SHINING_PEARL("brilliant-diamond-shining-pearl", "Diamant Étincelant / Perle Scintillante"),
    LEGENDS_ARCEUS("legends-arceus", "Légendes Pokémon : Arceus"),
    SCARLET_VIOLET("scarlet-violet", "Écarlate / Violet"),
    TEAL_MASK("the-teal-mask", "Le Masque Turquoise"),
    INDIGO_DISK("the-indigo-disk", "Le Disque Indigo"),
    LEGENDS_ZA("legends-za", "Légendes Pokémon : Z-A"),
    MEGA_DIMENSION("mega-dimension", "Méga-Dimension"),
    CHAMPIONS("champions", "Pokémon Champions", spinOff = true);

    companion object {
        private val byName = entries.associateBy { it.apiName }
        fun of(name: String): VersionGroupInfo? = byName[name]
        /** Versions inconnues (ajoutées à PokéAPI plus tard) : à la fin, par nom. */
        fun order(name: String): Int = byName[name]?.ordinal ?: (entries.size + 1)
        fun label(name: String): String = byName[name]?.label ?: name.split('-').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
    }
}

/** Méthodes d'apprentissage dans l'ordre d'affichage. */
val LEARN_METHOD_LABELS = linkedMapOf(
    "level-up" to "Par niveau",
    "machine" to "CT / CS",
    "egg" to "Œufs",
    "tutor" to "Tuteur",
    "train" to "Entraînement",
    "form-change" to "Changement de forme",
    "light-ball-egg" to "Œuf (Balle Lumière)",
    "zygarde-cube" to "Cube Zygarde",
    "stadium-surfing-pikachu" to "Pokémon Stadium (Pikachu Surfeur)",
    "colosseum-purification" to "Purification (Colosseum)",
    "xd-purification" to "Purification (XD)",
    "xd-shadow" to "Capacité Obscure (XD)",
)

fun learnMethodLabel(method: String): String =
    LEARN_METHOD_LABELS[method] ?: method.split('-').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
