package com.theveloper.pixelplay.presentation.components

import com.theveloper.pixelplay.presentation.navigation.Screen
import org.junit.Assert.assertEquals
import org.junit.Test

class BottomNavTapActionTest {

    @Test
    fun tappingAnotherTab_navigates() {
        assertEquals(
            BottomNavTapAction.Navigate,
            resolveBottomNavTapAction(
                tappedRoute = Screen.Search.route,
                currentRoute = Screen.Home.route
            )
        )
    }

    @Test
    fun tappingSearchWhileOnSearch_focusesTheSearchInput() {
        assertEquals(
            BottomNavTapAction.FocusSearchInput,
            resolveBottomNavTapAction(
                tappedRoute = Screen.Search.route,
                currentRoute = Screen.Search.route
            )
        )
    }

    @Test
    fun tappingSelectedNonSearchTab_doesNothing() {
        assertEquals(
            BottomNavTapAction.None,
            resolveBottomNavTapAction(
                tappedRoute = Screen.Home.route,
                currentRoute = Screen.Home.route
            )
        )
    }

    @Test
    fun tappingBeforeTheCurrentRouteIsKnown_doesNothing() {
        assertEquals(
            BottomNavTapAction.None,
            resolveBottomNavTapAction(
                tappedRoute = Screen.Search.route,
                currentRoute = null
            )
        )
    }
}
