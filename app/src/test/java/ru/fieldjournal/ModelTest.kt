package ru.fieldjournal

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ModelTest {
    private val rectangle = listOf(
        Coordinate(59.0, 33.0), Coordinate(59.0, 33.01), Coordinate(59.01, 33.01), Coordinate(59.01, 33.0)
    )

    @Test fun geoJsonRoundTripRetainsGeometryAndCard() {
        val hole = listOf(Coordinate(59.002, 33.002), Coordinate(59.002, 33.004), Coordinate(59.004, 33.004))
        val site = Site(title = "Урочище", type = "tract", vertices = rectangle, holes = listOf(hole),
            sources = "Архив", accuracy = 4.3, attachments = listOf(Attachment(newId(), "План.jpg", "image/jpeg")))
        assertEquals(site, Site.read(JSONObject(site.feature().toString()), true))
        val ring = site.feature().getJSONObject("geometry").getJSONArray("coordinates").getJSONArray(0)
        assertEquals(ring.getJSONArray(0).toString(), ring.getJSONArray(ring.length() - 1).toString())
        assertEquals(33.0, ring.getJSONArray(0).getDouble(0), 0.0)
        assertEquals(59.0, ring.getJSONArray(0).getDouble(1), 0.0)
        assertTrue(Site.read(site.feature(), false).attachments.isEmpty())
    }

    @Test fun rejectsCrossedBoundaryAndAcceptsConcaveBoundary() {
        assertFalse(validPolygon(listOf(rectangle[0], rectangle[2], rectangle[1], rectangle[3])))
        assertTrue(validPolygon(rectangle + Coordinate(59.005, 33.003)))
        assertTrue(areaSquareMeters(rectangle) in 630_000.0..650_000.0)
    }

    @Test fun smallBoundaryIsValidAndDegenerateBoundariesHaveDistinctErrors() {
        val a = Coordinate(59.0, 33.0)
        val b = Coordinate(59.0, 33.0000001)
        val c = Coordinate(59.0000001, 33.0000001)
        val d = Coordinate(59.0000001, 33.0)
        assertNull(polygonError(listOf(a, b, c, d)))
        assertEquals(BoundaryError.TOO_FEW, polygonError(listOf(a, b)))
        assertEquals(BoundaryError.REPEATED, polygonError(listOf(a, b, a)))
        assertEquals(BoundaryError.NO_AREA, polygonError(listOf(a, b, Coordinate(59.0, 33.0000002))))
        assertEquals(BoundaryError.CROSSED, polygonError(listOf(a, c, b, d)))
        assertEquals(BoundaryError.CROSSED, polygonError(listOf(a, b, Coordinate(59.0, 33.00000005), d)))
    }

    @Test fun smallDatelinePolygonStaysSmall() {
        val points = listOf(Coordinate(59.0, 179.999), Coordinate(59.0, -179.999),
            Coordinate(59.001, -179.999), Coordinate(59.001, 179.999))
        assertTrue(validPolygon(points))
        assertTrue(areaSquareMeters(points) in 12_000.0..13_000.0)
        assertEquals(180.0, kotlin.math.abs(polygonCenter(points).longitude), 1e-6)
    }

    @Test fun missingIdIsStableOnReimportAndNoteSurvivesRoundTrip() {
        val point = Site(vertices = listOf(Coordinate(59.0, 33.0))).feature().apply { remove("id") }
        assertEquals(Site.read(point, false).id, Site.read(point, false).id)
        val note = Note(title = "Выезд", weather = "Облачно", siteId = "archive-1", created = 1000)
        assertEquals(note, Note.read(JSONObject(note.json().toString())))
    }
    @Test fun polygonValidationRejectsExteriorAndOverlappingHoles() {
        fun square(south: Double, west: Double, size: Double) = listOf(
            Coordinate(south, west), Coordinate(south, west + size),
            Coordinate(south + size, west + size), Coordinate(south + size, west)
        )
        val outer = square(58.0, 30.0, 0.5)
        val hole = square(58.1, 30.1, 0.1)
        val another = square(58.3, 30.3, 0.1)
        val site = Site(title = "Тестовый участок", vertices = outer, holes = listOf(hole, another))
        assertEquals(site, Site.read(site.feature(), withAttachments = false))
        for (invalid in listOf(
            listOf(square(59.0, 31.0, 0.1)),
            listOf(square(58.0, 30.1, 0.1)),
            listOf(hole, square(58.15, 30.15, 0.1)),
            listOf(hole, square(58.12, 30.12, 0.02))
        )) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                Site.read(site.copy(holes = invalid).feature(), withAttachments = false)
            }
            assertTrue(error.message.orEmpty().contains(site.title))
        }
    }

}
