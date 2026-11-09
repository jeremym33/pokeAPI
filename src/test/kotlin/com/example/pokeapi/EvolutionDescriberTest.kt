package com.example.pokeapi

import com.example.pokeapi.client.NamedApiResource
import com.example.pokeapi.client.PokeApiConditionExpression
import com.example.pokeapi.client.PokeApiEvolutionDetail
import com.example.pokeapi.service.TranslationKind
import com.example.pokeapi.service.Translator
import com.example.pokeapi.service.describeEvolution
import com.example.pokeapi.service.describeEvolutions
import kotlin.test.Test
import kotlin.test.assertEquals

class EvolutionDescriberTest {

    private val names = mapOf(
        "thunder-stone" to "Pierre Foudre", "kings-rock" to "Roche Royale", "rage-fist" to "Poing de Colère",
        "psyshield-bash" to "Sprint Bouclier", "shelmet" to "Escargaume", "remoraid" to "Rémoraid",
    )
    private val t = Translator { _: TranslationKind, slug -> names[slug] ?: slug }

    private fun r(name: String) = NamedApiResource(name, "")
    private fun describe(d: PokeApiEvolutionDetail) = describeEvolution(d, t)

    @Test
    fun `niveau simple`() =
        assertEquals("Niveau 16", describe(PokeApiEvolutionDetail(trigger = r("level-up"), minLevel = 16)))

    @Test
    fun `objet`() =
        assertEquals("Utiliser Pierre Foudre", describe(PokeApiEvolutionDetail(trigger = r("use-item"), item = r("thunder-stone"))))

    @Test
    fun `bonheur et moment de la journee (Mentali)`() = assertEquals(
        "Gagner un niveau, avec un bonheur élevé, de jour",
        describe(PokeApiEvolutionDetail(trigger = r("level-up"), minHappiness = 160, timeOfDay = "day")),
    )

    @Test
    fun `echange en tenant un objet (Roigada)`() = assertEquals(
        "Échange, en tenant Roche Royale",
        describe(PokeApiEvolutionDetail(trigger = r("trade"), heldItem = r("kings-rock"))),
    )

    @Test
    fun `echange contre une espece (Carabing)`() = assertEquals(
        "Échange, contre Escargaume",
        describe(PokeApiEvolutionDetail(trigger = r("trade"), tradeSpecies = r("shelmet"))),
    )

    @Test
    fun `capacite utilisee plusieurs fois (Courrousinge)`() = assertEquals(
        "Utiliser Poing de Colère 20 fois",
        describe(PokeApiEvolutionDetail(trigger = r("use-move"), usedMove = r("rage-fist"), minMoveCount = 20)),
    )

    @Test
    fun `style rapide (Cerbyllin)`() = assertEquals(
        "Utiliser Sprint Bouclier 20 fois en Style Rapide",
        describe(PokeApiEvolutionDetail(trigger = r("agile-style-move"), usedMove = r("psyshield-bash"), minMoveCount = 20)),
    )

    @Test
    fun `pas et pourcentage de chance`() {
        assertEquals(
            "Niveau 18, après 1000 pas hors de sa Poké Ball",
            describe(PokeApiEvolutionDetail(trigger = r("level-up"), minLevel = 18, minSteps = 1000)),
        )
        assertEquals(
            "Niveau 7, (50 % de chance)",
            describe(PokeApiEvolutionDetail(trigger = r("level-up"), minLevel = 7, conditionExpression = PokeApiConditionExpression(50.0))),
        )
    }

    @Test
    fun `stats relatives et genre (Debugant, Gallame)`() {
        assertEquals(
            "Niveau 20, si Attaque > Défense",
            describe(PokeApiEvolutionDetail(trigger = r("level-up"), minLevel = 20, relativePhysicalStats = 1)),
        )
        assertEquals(
            "Utiliser Pierre Foudre, mâle uniquement",
            describe(PokeApiEvolutionDetail(trigger = r("use-item"), item = r("thunder-stone"), gender = 2)),
        )
    }

    @Test
    fun `variante regionale (Raichu d'Alola)`() = assertEquals(
        "Utiliser Pierre Foudre, dans la région Alola",
        describe(PokeApiEvolutionDetail(trigger = r("use-item"), item = r("thunder-stone"), region = r("alola"))),
    )

    @Test
    fun `equipe, pluie, console a l'envers`() {
        assertEquals(
            "Gagner un niveau, avec Rémoraid dans l'équipe",
            describe(PokeApiEvolutionDetail(trigger = r("level-up"), partySpecies = r("remoraid"))),
        )
        assertEquals(
            "Niveau 50, sous la pluie",
            describe(PokeApiEvolutionDetail(trigger = r("level-up"), minLevel = 50, needsOverworldRain = true)),
        )
        assertEquals(
            "Niveau 30, console à l'envers",
            describe(PokeApiEvolutionDetail(trigger = r("level-up"), minLevel = 30, turnUpsideDown = true)),
        )
    }

    @Test
    fun `seule la methode actuelle est gardee, sans doublons`() {
        val details = listOf(
            PokeApiEvolutionDetail(trigger = r("level-up"), location = r("eterna-forest"), nearSpecialRock = true),
            PokeApiEvolutionDetail(trigger = r("use-item"), item = r("thunder-stone"), isDefault = true),
            PokeApiEvolutionDetail(trigger = r("use-item"), item = r("thunder-stone"), isDefault = true),
        )
        assertEquals(listOf("Utiliser Pierre Foudre"), describeEvolutions(details, t))
    }

    @Test
    fun `sans is_default (anciennes donnees), toutes les methodes sont gardees`() {
        val details = listOf(
            PokeApiEvolutionDetail(trigger = r("level-up"), minLevel = 37),
            PokeApiEvolutionDetail(trigger = r("use-item"), item = r("thunder-stone")),
        )
        assertEquals(listOf("Niveau 37", "Utiliser Pierre Foudre"), describeEvolutions(details, t))
    }
}
