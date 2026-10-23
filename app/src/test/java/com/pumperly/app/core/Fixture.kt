package com.pumperly.app.core

/** The JSON fixtures shared with the iOS tests, copied byte for byte into src/test/resources. */
object Fixture {
    fun text(name: String): String {
        val url = Fixture::class.java.classLoader!!.getResource("$name.json") ?: error("missing fixture $name.json")
        return url.readText(Charsets.UTF_8)
    }

    fun stations(name: String): List<Station> = StationsApi.decode(text(name))
}
