package ru.fieldjournal

internal class BoundaryHistory {
    data class Revision(val vertices: List<Coordinate>, val measurements: Map<Coordinate, LocationFix>)

    private val revisions = ArrayDeque<Revision>()
    val canUndo get() = revisions.isNotEmpty()

    fun remember(vertices: List<Coordinate>, measurements: Map<Coordinate, LocationFix>) {
        if (revisions.size == 100) revisions.removeFirst()
        revisions.addLast(Revision(vertices.toList(), measurements.toMap()))
    }

    fun undo(): Revision? = revisions.removeLastOrNull()

    fun clear() = revisions.clear()
}
