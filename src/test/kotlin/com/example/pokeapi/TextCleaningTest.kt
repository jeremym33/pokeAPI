package com.example.pokeapi

import com.example.pokeapi.client.cleanEffectText
import com.example.pokeapi.client.cleanGameText
import kotlin.test.Test
import kotlin.test.assertEquals

class TextCleaningTest {
    @Test
    fun `retours a la ligne reels et litteraux, saut de page, cesure`() {
        assertEquals("Le Pokémon détourne sur lui les capacités", cleanGameText("Le Pokémon détourne\\nsur lui les\ncapacités"))
        assertEquals("Charge l'ennemi violemment.", cleanGameText("Charge l'ennemi\u000cviolemment."))
        assertEquals("électrique", cleanGameText("élec­\ntrique"))
        assertEquals("a b", cleanGameText("  a   \n\n b "))
    }

    @Test
    fun `effet - paragraphes conserves`() =
        assertEquals("Premier paragraphe.\n\nSecond paragraphe.", cleanEffectText("Premier\nparagraphe.\n\n  Second paragraphe.\\n"))
}
