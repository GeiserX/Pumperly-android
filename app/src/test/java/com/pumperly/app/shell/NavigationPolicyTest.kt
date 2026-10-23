package com.pumperly.app.shell

import com.pumperly.app.shell.NavigationPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from the iOS NavigationPolicyTests, case for case. */
class NavigationPolicyTest {
    private fun decide(url: String, mainFrame: Boolean = true) = NavigationPolicy.decide(url, mainFrame)

    @Test
    fun pumperlyHttpsLoadsInApp() {
        assertEquals(Decision.LOAD_IN_APP, decide("https://pumperly.com/es?station=ES:4508"))
        assertEquals(Decision.LOAD_IN_APP, decide("https://www.pumperly.com/"))
        assertEquals(Decision.LOAD_IN_APP, decide("https://PUMPERLY.com/en"))
    }

    @Test
    fun otherSitesOpenOutsideTheApp() {
        assertEquals(Decision.OPEN_EXTERNALLY, decide("https://github.com/GeiserX/Pumperly"))
        assertEquals(Decision.OPEN_EXTERNALLY, decide("https://www.openstreetmap.org/copyright"))
    }

    @Test
    fun lookalikeHostsAreNotTrusted() {
        assertEquals(Decision.OPEN_EXTERNALLY, decide("https://pumperly.com.evil.example/"))
        assertEquals(Decision.OPEN_EXTERNALLY, decide("https://evilpumperly.com/"))
        assertEquals(Decision.OPEN_EXTERNALLY, decide("https://api.pumperly.com/"))
        assertEquals(Decision.OPEN_EXTERNALLY, decide("https://pumperly.com@evil.example/"))
        assertFalse(NavigationPolicy.isAllowed("https://pumperly.com@evil.example/"))
    }

    @Test
    fun nonHttpsPumperlyLeavesTheApp() {
        assertEquals(Decision.OPEN_EXTERNALLY, decide("http://pumperly.com/"))
        assertFalse(NavigationPolicy.isAllowed("http://pumperly.com/"))
        assertFalse(NavigationPolicy.isAllowed(null))
    }

    @Test
    fun systemSchemesGoToTheirApps() {
        assertEquals(Decision.OPEN_EXTERNALLY, decide("mailto:hello@example.com"))
        assertEquals(Decision.OPEN_EXTERNALLY, decide("tel:+34600000000"))
        assertEquals(Decision.OPEN_EXTERNALLY, decide("geo:0,0?q=40.4,-3.7(Repsol)"))
        assertEquals(Decision.OPEN_EXTERNALLY, decide("maps://?daddr=40.4,-3.7"))
    }

    @Test
    fun dangerousSchemesAreCancelled() {
        assertEquals(Decision.CANCEL, decide("javascript:alert(1)"))
        assertEquals(Decision.CANCEL, decide("JavaScript:alert(1)"))
        assertEquals(Decision.CANCEL, decide("file:///etc/passwd"))
        assertEquals(Decision.CANCEL, decide("data:text/html,hi"))
        assertEquals(Decision.CANCEL, decide("blob:https://pumperly.com/3f1c"))
        assertEquals(Decision.CANCEL, decide("javascript:alert(1)", mainFrame = false))
        assertEquals(Decision.CANCEL, NavigationPolicy.decide(null, isMainFrame = true))
        assertEquals(Decision.CANCEL, decide("no scheme at all"))
    }

    @Test
    fun aboutBlankLoads() {
        assertEquals(Decision.LOAD_IN_APP, decide("about:blank"))
    }

    @Test
    fun subframesStayInPlaceAndNeverLeaveTheApp() {
        assertEquals(Decision.LOAD_IN_APP, decide("https://www.youtube.com/embed/x", mainFrame = false))
        assertEquals(Decision.CANCEL, decide("http://example.com/", mainFrame = false))
        assertEquals(Decision.CANCEL, decide("tel:+34600000000", mainFrame = false))
    }

    @Test
    fun locationOnlyForTopLevelPumperly() {
        assertTrue(NavigationPolicy.mayShareLocation(isMainFrame = true, scheme = "https", host = "pumperly.com"))
        assertTrue(NavigationPolicy.mayShareLocation(isMainFrame = true, scheme = "HTTPS", host = "WWW.pumperly.com"))
        assertFalse(NavigationPolicy.mayShareLocation(isMainFrame = false, scheme = "https", host = "pumperly.com"))
        assertFalse(NavigationPolicy.mayShareLocation(isMainFrame = true, scheme = "http", host = "pumperly.com"))
        assertFalse(NavigationPolicy.mayShareLocation(isMainFrame = true, scheme = "https", host = "example.com"))
    }
}
