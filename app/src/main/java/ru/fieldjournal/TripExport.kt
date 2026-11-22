package ru.fieldjournal

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.math.BigDecimal
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal fun writeTripGpx(contents: TripContents, out: OutputStream) {
    val writer = out.writer(Charsets.UTF_8)
    val xml = android.util.Xml.newSerializer()
    val namespace = "http://www.topografix.com/GPX/1/1"
    xml.setOutput(writer)
    xml.startDocument("UTF-8", true)
    xml.setPrefix("", namespace)
    val accuracyNamespace = "https://github.com/go-0dboy/field_journal/gpx/1"
    xml.setPrefix("fj", accuracyNamespace)
    xml.startTag(namespace, "gpx").attribute(null, "version", "1.1").attribute(null, "creator", "Field Journal")
    xml.startTag(namespace, "trk")
    xml.startTag(namespace, "name").text(contents.trip.title).endTag(namespace, "name")
    for (segment in contents.points.groupBy(TrackPoint::segment).values) {
        xml.startTag(namespace, "trkseg")
        for (point in segment) {
            xml.startTag(namespace, "trkpt")
                .attribute(null, "lat", decimal(point.point.latitude))
                .attribute(null, "lon", decimal(point.point.longitude))
            point.altitude?.let { xml.startTag(namespace, "ele").text(decimal(it)).endTag(namespace, "ele") }
            xml.startTag(namespace, "time").text(Instant.ofEpochMilli(point.time).toString()).endTag(namespace, "time")
            xml.startTag(namespace, "extensions")
            xml.startTag(accuracyNamespace, "accuracy_m").text(decimal(point.accuracy)).endTag(accuracyNamespace, "accuracy_m")
            xml.endTag(namespace, "extensions")
            xml.endTag(namespace, "trkpt")
        }
        xml.endTag(namespace, "trkseg")
    }
    xml.endTag(namespace, "trk").endTag(namespace, "gpx").endDocument()
    writer.flush()
}

private fun decimal(value: Double) = BigDecimal.valueOf(value).toPlainString()

private data class PhotoLink(val siteId: String?, val noteId: String?, val attachment: Attachment) {
    val path: String get() {
        val extension = attachment.name.substringAfterLast('.', "").lowercase()
            .takeIf { it.matches(Regex("[a-z0-9]{1,10}")) }
        return "media/${attachment.id}" + (extension?.let { ".$it" } ?: "")
    }
}

internal fun writeTripKit(
    contents: TripContents, out: OutputStream, fileFor: (String) -> File,
    edition: TripEdition? = null, project: JSONObject = JSONObject()
) {
    val links = contents.sites.flatMap { site -> site.attachments.map { PhotoLink(site.id, null, it) } } +
        contents.notes.flatMap { note -> note.attachments.map { PhotoLink(note.siteId, note.id, it) } }
    val visitLinks = contents.visits.flatMap { visit -> visit.sites.flatMap { site ->
        site.attachments.map { PhotoLink(site.id, null, it) }
    } }
    val attachments = (links + visitLinks).distinctBy { it.attachment.id }.associateWith { link ->
        fileFor(link.attachment.id).also { require(it.isFile) { "Вложение недоступно: ${link.attachment.name}" } }
    }
    val paths = attachments.keys.associate { it.attachment.id to it.path }
    ZipOutputStream(out).use { zip ->
        fun entry(name: String, write: () -> Unit) {
            zip.putNextEntry(ZipEntry(name).apply { time = edition?.time ?: contents.trip.startedAt })
            write()
            zip.closeEntry()
        }
        fun text(name: String, value: String) = entry(name) { zip.write(value.toByteArray(Charsets.UTF_8)) }
        val features = contents.sites.map { site ->
            site.copy(attachments = emptyList()).feature(normalizeWinding = true).also { feature ->
                val properties = feature.getJSONObject("properties").put("site_id", site.id)
                    .put("trip_id", contents.trip.id)
                    .put("attachments", JSONArray(links.filter { it.siteId == site.id && it.noteId == null }
                        .mapNotNull { paths[it.attachment.id] }))
                contents.visitTimes[site.id]?.let { properties.put("visited_at", Instant.ofEpochMilli(it).toString()) }
            }
        }
        text("objects.geojson", JSONObject().put("type", "FeatureCollection").put("features", JSONArray(features)).toString())
        if (contents.points.isNotEmpty()) entry("track.gpx") { writeTripGpx(contents, zip) }
        text("observations.csv", buildString {
            appendLine("note_id,site_id,trip_id,title,observed_at,author,weather,description,links,research")
            for (note in contents.notes) {
                appendLine(csv(note.id, note.siteId, contents.trip.id, note.title, Instant.ofEpochMilli(note.created).toString(),
                    note.author, note.weather, note.body, note.links.takeIf { it.isNotEmpty() }?.linksJson()?.toString(), note.research.json().toString()))
            }
        })
        text("attachments.csv", buildString {
            appendLine("attachment_id,site_id,note_id,name,mime,path")
            for (link in links) {
                appendLine(csv(link.attachment.id, link.siteId, link.noteId, link.attachment.name,
                    link.attachment.mime, paths[link.attachment.id]))
            }
        })
        text("visits.csv", buildString {
            appendLine("visit_id,site_id,trip_id,visited_at")
            for (visit in contents.visits) appendLine(csv(visit.id, visit.siteId, contents.trip.id,
                Instant.ofEpochMilli(visit.time).toString()))
        })
        text("visit_snapshots.json", JSONArray(contents.visits.map { visit ->
            visit.json().apply {
                put("media", JSONArray(visit.sites.flatMap(Site::attachments).mapNotNull { paths[it.id] }.distinct()))
            }
        }).toString())
        text("track_quality.csv", buildString {
            appendLine("segment,measured_at,longitude,latitude,altitude_m,accuracy_m")
            for (point in contents.points) appendLine(csv(point.segment.toString(), Instant.ofEpochMilli(point.time).toString(),
                decimal(point.point.longitude), decimal(point.point.latitude), point.altitude?.let(::decimal), decimal(point.accuracy)))
        })
        text("trip.json", contents.trip.json().apply {
            edition?.let { put("edition", it.number).put("issued_at", Instant.ofEpochMilli(it.time).toString()) }
            if (project.length() > 0) put("project", project)
        }.toString(2))
        text("README.txt", """
            Полевой дневник. Комплект для QGIS

            Выезд: ${contents.trip.title}
            Распакуйте архив, сохранив структуру папок.
            Добавьте objects.geojson как векторный слой объектов (WGS 84).
            Если есть track.gpx, добавьте GPX и выберите слой tracks.
            Добавьте observations.csv и attachments.csv как таблицы UTF-8.
            Таблица visits.csv содержит отдельные осмотры. Геометрии на даты осмотров
            находятся в visit_snapshots.json. В track_quality.csv сохранены оценки
            точности Android в метрах. trip.json хранит редакцию и сведения проекта.
            Связь объекта и наблюдения задаётся по site_id. Файлы привязаны
            по site_id и note_id. Колонка path содержит путь к файлу от корня комплекта.
            Поле links содержит адреса и названия интернет-ресурсов в формате JSON.
        """.trimIndent())
        for ((link, file) in attachments) entry(link.path) { file.inputStream().use { it.copyTo(zip) } }
    }
}

private fun csv(vararg values: String?): String =
    values.joinToString(",") { value -> "\"${value.orEmpty().replace("\"", "\"\"")}\"" }
