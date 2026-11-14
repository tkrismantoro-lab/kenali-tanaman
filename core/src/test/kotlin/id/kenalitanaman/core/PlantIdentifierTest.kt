package id.kenalitanaman.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlantIdentifierTest {
    private val sample = """
        {"is_plant":true,"common_name":"Lidah Mertua","other_names":["Snake Plant"," "],
         "scientific_name":"Dracaena trifasciata","family":"Asparagaceae","confidence":"tinggi",
         "description":"Sukulen berdaun tegak.",
         "care":{"watering":"2 minggu sekali","light":"Teduh hingga terang","soil":"Porous",
                 "temperature_humidity":"18-32 C","fertilizer":"Sebulan sekali","pruning":"Jarang",
                 "propagation":"Stek daun"},
         "difficulty":"mudah","toxicity":"Beracun ringan bagi kucing dan anjing",
         "health_assessment":"Sehat","common_problems":["Busuk akar"],"uses":"Tanaman hias",
         "fun_facts":["Tahan kekeringan"]}
    """.trimIndent()

    @Test
    fun parsesModelJson() {
        val info = PlantInfo.fromJson(sample)
        assertTrue(info.isPlant)
        assertEquals("Lidah Mertua", info.commonName)
        assertEquals(listOf("Snake Plant"), info.otherNames)
        assertEquals("Stek daun", info.care.propagation)
        assertEquals(listOf("Busuk akar"), info.commonProblems)
    }

    @Test
    fun toleratesMissingFields() {
        val info = PlantInfo.fromJson("""{"is_plant":false,"description":"Sebuah kursi"}""")
        assertFalse(info.isPlant)
        assertEquals("", info.commonName)
        assertTrue(info.funFacts.isEmpty())
    }

    @Test
    fun buildsRequestWithImageAndSchema() {
        val params = PlantIdentifier(apiKey = "test").buildParams("QUJD")
        val body = com.anthropic.core.jsonMapper().writeValueAsString(params._body())
        val tree = com.fasterxml.jackson.databind.ObjectMapper().readTree(body)

        assertEquals("claude-opus-5-5", tree["model"].asText())
        val content = tree["messages"][0]["content"]
        assertEquals("image", content[0]["type"].asText())
        assertEquals("image/jpeg", content[0]["source"]["media_type"].asText())
        assertEquals("QUJD", content[0]["source"]["data"].asText())
        assertEquals("text", content[1]["type"].asText())

        val format = tree["output_config"]["format"]
        assertEquals("json_schema", format["type"].asText())
        assertEquals(false, format["schema"]["additionalProperties"].asBoolean())
        assertEquals(14, format["schema"]["required"].size())
        assertEquals(7, format["schema"]["properties"]["care"]["required"].size())
    }
}
