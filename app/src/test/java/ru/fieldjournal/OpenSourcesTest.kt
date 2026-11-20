package ru.fieldjournal

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenSourcesTest {
    @Test fun retriesAnOverloadedServerWithASecondSource() {
        val urls = mutableListOf<String>()
        val response = OpenSources.nearby("[out:json];node(1);out;") { url ->
            urls.add(url)
            if (urls.size == 1) throw java.io.IOException("HTTP 502")
            JSONObject("""{"elements":[]}""")
        }
        assertTrue(urls[0].startsWith("https://overpass-api.de/"))
        assertTrue(urls[1].startsWith("https://overpass.private.coffee/"))
        assertEquals(0, response.getJSONArray("elements").length())
        assertThrows(java.io.IOException::class.java) {
            OpenSources.nearby("query") { throw java.io.IOException("Offline") }
        }
    }

    @Test fun prefersTheNamedBuildingAndKeepsOnlyValidSourceLinks() {
        val data = JSONObject("""
            {"elements":[
                {"type":"way","id":1,"bounds":{"minlat":58.999,"maxlat":59.001,"minlon":32.999,"maxlon":33.001},
                 "tags":{"name":"Дорога","highway":"primary"}},
                {"type":"node","id":2,"lat":59.00015,"lon":33,"tags":{"name":"Другая точка"}},
                {"type":"way","id":3,"bounds":{"minlat":58.9999,"maxlat":59.0001,"minlon":32.9999,"maxlon":33.0001},
                 "tags":{"name":"Tower","name:ru":"Звонница","description:ru":"Башня монастыря",
                         "start_date":"1820","ref:ru:okrn":"123","wikidata":"Q135","wikipedia":"ru:Старая звонница",
                         "website":"javascript:alert(1)"}}
            ]}
        """.trimIndent())
        val place = requireNotNull(OpenSources.parse(data, Coordinate(59.0, 33.0)))
        assertEquals("Звонница", place.name)
        assertEquals("Башня монастыря", place.description)
        assertEquals("1820", place.period)
        assertEquals("123", place.inventory)
        assertEquals(3, place.links.size)
        assertEquals("https://www.openstreetmap.org/way/3", place.links.first())
        assertEquals("https://www.wikidata.org/wiki/Q135", place.links[1])
        assertTrue(place.links[2].startsWith("https://ru.wikipedia.org/wiki/"))
        assertNull(OpenSources.parse(data, Coordinate(60.0, 33.0)))
    }
}
