package ru.fieldjournal

import org.locationtech.proj4j.ProjCoordinate
import org.locationtech.proj4j.ProjectionException
import org.locationtech.proj4j.datum.Ellipsoid
import org.locationtech.proj4j.proj.Projection
import org.locationtech.proj4j.util.ProjectionMath
import kotlin.math.*

/** Proj4J 1.4.2 fixes the Bonne parallel at 90°. Ozi calibration needs an arbitrary parallel. */
internal class OziBonneProjection(
    ellipsoid: Ellipsoid, standardParallel: Double, centralMeridian: Double, easting: Double, northing: Double
) : Projection() {
    private val coefficients: DoubleArray
    private val meridianAtParallel: Double
    private val radiusAtParallel: Double
    private val hemisphere = sign(standardParallel)

    init {
        require(abs(standardParallel) in 0.000001..90.0) {
            "Некорректная стандартная параллель проекции Бонна (Bonne)"
        }
        setEllipsoid(ellipsoid)
        setProjectionLongitudeDegrees(centralMeridian)
        falseEasting = easting
        falseNorthing = northing
        initialize()
        coefficients = ProjectionMath.enfn(es)
        val latitude = Math.toRadians(standardParallel)
        meridianAtParallel = meridianDistance(latitude)
        radiusAtParallel = parallelRadius(latitude) / sin(latitude)
    }

    override fun project(longitude: Double, latitude: Double, result: ProjCoordinate): ProjCoordinate {
        val radius = radiusAtParallel + meridianAtParallel - meridianDistance(latitude)
        val angle = if (abs(radius) < EPS10) 0.0 else longitude * parallelRadius(latitude) / radius
        result.x = radius * sin(angle)
        result.y = radiusAtParallel - radius * cos(angle)
        return result
    }

    override fun projectInverse(x: Double, y: Double, result: ProjCoordinate): ProjCoordinate {
        val southward = radiusAtParallel - y
        val radius = hemisphere * hypot(x, southward)
        val latitude = ProjectionMath.inv_mlfn(radiusAtParallel + meridianAtParallel - radius, es, coefficients)
        if (abs(latitude) > PI / 2 + EPS10) throw ProjectionException("Точка за пределами проекции Бонна")
        result.y = latitude.coerceIn(-PI / 2, PI / 2)
        result.x = if (abs(latitude) >= PI / 2 - EPS10) 0.0
            else radius * atan2(hemisphere * x, hemisphere * southward) / parallelRadius(latitude)
        return result
    }

    override fun hasInverse() = true

    private fun meridianDistance(latitude: Double) =
        ProjectionMath.mlfn(latitude, sin(latitude), cos(latitude), coefficients)

    private fun parallelRadius(latitude: Double): Double {
        val sine = sin(latitude)
        return cos(latitude) / sqrt(1 - es * sine * sine)
    }
}
