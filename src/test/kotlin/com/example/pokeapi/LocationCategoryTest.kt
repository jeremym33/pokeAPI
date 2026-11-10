package com.example.pokeapi

import com.example.pokeapi.model.LocationCategory.CITY
import com.example.pokeapi.model.LocationCategory.NATURE
import com.example.pokeapi.model.LocationCategory.OTHER
import com.example.pokeapi.model.LocationCategory.ROUTE
import com.example.pokeapi.service.categorize
import kotlin.test.Test
import kotlin.test.assertEquals

class LocationCategoryTest {
    @Test
    fun `routes avec leur numero`() {
        assertEquals(ROUTE to 1, categorize("kanto-route-1"))
        assertEquals(ROUTE to 1, categorize("alola-route-1--hauoli-outskirts"))
        assertEquals(ROUTE to 19, categorize("kanto-sea-route-19"))
    }

    @Test
    fun villes() {
        assertEquals(CITY to null, categorize("pallet-town"))
        assertEquals(CITY to null, categorize("celadon-city"))
        assertEquals(CITY to null, categorize("iki-town"))
        assertEquals(CITY to null, categorize("wedgehurst")) // Galar : nom en un seul mot
    }

    @Test
    fun `grottes, forets, mers et autres lieux`() {
        assertEquals(NATURE to null, categorize("mt-moon"))
        assertEquals(NATURE to null, categorize("viridian-forest"))
        assertEquals(NATURE to null, categorize("digletts-cave"))
        assertEquals(NATURE to null, categorize("melemele-sea"))
        assertEquals(OTHER to null, categorize("pokemon-tower"))
        assertEquals(OTHER to null, categorize("safari-zone"))
    }
}
