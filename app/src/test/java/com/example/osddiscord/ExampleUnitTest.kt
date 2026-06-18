package com.example.osddiscord

import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject

class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun testJsonParsing() {
        val jsonString = "{\"alarmState\": 2}"
        val json = JSONObject(jsonString)
        assertEquals(2, json.optInt("alarmState"))
    }
}
