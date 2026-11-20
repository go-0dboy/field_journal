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
    @Test fun searchesByTheEnteredNameAndOrdersArticlesByRelevance() {
        val articles = OpenSources.search("Монастырь & Тихвин") { url ->
            assertTrue(url.startsWith("https://ru.wikipedia.org/w/api.php?"))
            val query = android.net.Uri.parse(url)
            assertEquals("Монастырь & Тихвин", query.getQueryParameter("gsrsearch"))
            assertEquals("search", query.getQueryParameter("generator"))
            assertNull(query.getQueryParameter("ggscoord"))
            JSONObject("""
                {"query":{"pages":[
                    {"pageid":4,"title":"Другой монастырь","index":2},
                    {"pageid":3,"title":"Тихвинский монастырь","index":1,
                     "extract":"Краткое описание","pageprops":{"wikibase_item":"Q4458458"}},
                    {"pageid":5,"title":"Монастыри","index":3,"pageprops":{"disambiguation":""}}
                ]}}
            """.trimIndent())
        }
        assertEquals(listOf("Тихвинский монастырь", "Другой монастырь"), articles.map(SourceArticle::title))
        assertEquals("Краткое описание", articles.first().excerpt)
        assertEquals("https://ru.wikipedia.org/?curid=3", articles.first().url)
        assertEquals("https://www.wikidata.org/wiki/Q4458458", articles.first().wikidataUrl)
        assertTrue(OpenSources.search("неизвестный объект") { JSONObject("""{"batchcomplete":true}""") }.isEmpty())
        assertThrows(java.io.IOException::class.java) {
            OpenSources.search("Монастырь") { JSONObject("""{"error":{"code":"maxlag"}}""") }
        }
    }

    @Test fun readsOnlyUsableYearsAndOfficialRegisterIdentifiers() {
        val facts = OpenSources.facts("Q4458458") { url ->
            assertTrue(url.startsWith("https://www.wikidata.org/w/api.php?"))
            assertEquals("Q4458458", android.net.Uri.parse(url).getQueryParameter("ids"))
            JSONObject("""
                {"entities":{"Q4458458":{"claims":{
                    "P571":[
                        {"rank":"normal","mainsnak":{"datavalue":{"value":{"time":"+1500-01-01T00:00:00Z","precision":7}}}},
                        {"rank":"preferred","mainsnak":{"datavalue":{"value":{"time":"+1560-01-01T00:00:00Z","precision":9}}}},
                        {"rank":"deprecated","mainsnak":{"datavalue":{"value":{"time":"+1900-01-01T00:00:00Z","precision":9}}}}
                    ],
                    "P5381":[{"rank":"normal","mainsnak":{"datavalue":{"value":"471420555330006"}}}]
                }}}}
            """.trimIndent())
        }
        assertEquals(SourceFacts("1560", "471420555330006"), facts)
        assertEquals(SourceFacts(), OpenSources.facts("bad/id") { error("Invalid IDs must not be requested") })
    }
}
