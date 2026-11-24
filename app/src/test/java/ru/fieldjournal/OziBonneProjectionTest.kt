package ru.fieldjournal

import org.junit.Assert.assertEquals
import org.junit.Test
import org.locationtech.proj4j.ProjCoordinate
import org.locationtech.proj4j.datum.Ellipsoid

class OziBonneProjectionTest {
    @Test fun projectsAndInvertsBonneWithTheParallelFromTheMap() {
        // Reference coordinates from PROJ with the same ellipsoid and projection parameters.
        verifyProjection(OziBonneProjection(Ellipsoid.WGS84, 52.0, 30.327309, 0.0, 0.0),
            33.327309, 59.5, 169872.22837807, 838496.28563904)
        verifyProjection(OziBonneProjection(Ellipsoid.WGS84, -35.0, 144.0, 250000.0, 170000.0),
            147.0, -42.0, 498515.50998429, -610746.57971100)
    }

    @Test fun readsBonneParallelFromEitherOziSetupField() {
        for (setup in listOf("52,30.327309,1,0,0", "0,30.327309,1,0,0,52")) {
            val map = """
                OziExplorer Map Data File Version 2.2
                Bonne test
                fixture.ozf2
                1 ,Map Code,
                WGS 84
                Map Projection,Bonne,PolyCal,No
                Projection Setup,$setup
                Point01,xy,0,0,in,deg,,,,,,,grid,,168872.22837807,839496.28563904,N
                Point02,xy,100,100,in,deg,,,,,,,grid,,170872.22837807,837496.28563904,N
            """.trimIndent().toByteArray()
            val center = OziCalibration(map, 100, 100).coordinate(50.0, 50.0)
            assertEquals(59.5, center.latitude, 1e-7)
            assertEquals(33.327309, center.longitude, 1e-7)
        }
    }

    private fun verifyProjection(projection: OziBonneProjection, longitude: Double, latitude: Double, east: Double, north: Double) {
        val result = projection.project(ProjCoordinate(longitude, latitude), ProjCoordinate())
        assertEquals(east, result.x, 0.01)
        assertEquals(north, result.y, 0.01)
        val inverse = projection.inverseProject(result, ProjCoordinate())
        assertEquals(longitude, inverse.x, 1e-7)
        assertEquals(latitude, inverse.y, 1e-7)
    }
}
