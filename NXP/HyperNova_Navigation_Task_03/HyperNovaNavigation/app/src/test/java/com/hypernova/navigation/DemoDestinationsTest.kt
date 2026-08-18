package com.hypernova.navigation

import com.hypernova.navigation.domain.model.DemoDestinations
import com.hypernova.navigation.domain.model.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class DemoDestinationsTest {

    @Test
    fun defaultsApplyOnlyWhenNoSavedDestinationExists() {
        val resolved =
            DemoDestinations.resolve(
                savedHome = null,
                savedWork = null
            )

        assertEquals(
            DemoDestinations.HOME,
            resolved.home
        )
        assertEquals(
            DemoDestinations.WORK,
            resolved.work
        )
    }

    @Test
    fun userModifiedHomeAndWorkAreNotOverwritten() {
        val savedHome =
            Place("Saved Home", 30.1, 31.1)
        val savedWork =
            Place("Saved Work", 30.2, 31.2)
        val resolved =
            DemoDestinations.resolve(
                savedHome = savedHome,
                savedWork = savedWork
            )

        assertSame(savedHome, resolved.home)
        assertSame(savedWork, resolved.work)
    }
}
