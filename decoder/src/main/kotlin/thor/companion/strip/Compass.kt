package thor.companion.strip

import kotlin.math.atan2
import kotlin.math.hypot

/** Which way and how far a quest is from you, for the quest arrow. */
object Compass {
    /** Direction clockwise from north (radians), distance in yards (null when the zone's size is unknown). */
    data class Heading(val place: MapPlace, val bearing: Double, val yards: Int?)

    /** Whether a map place's label (maybe cut short with "…") belongs to the quest [title]. */
    fun matches(label: String, title: String) = label.isNotEmpty() && title.startsWith(label.removeSuffix("…"))

    /**
     * The nearest place of quest [title] on [zone] from [x], [y]: where to turn it in
     * when it is ready (Q), else its objective areas (q) or where it starts (a). With
     * [waypoint] (where the game's own arrow points), that place is used instead when
     * it is in this zone, since it also knows turn-ins the map doesn't show.
     */
    fun toQuest(zone: ZoneMap, title: String, x: Double, y: Double, waypoint: Waypoint? = null): Heading? {
        val here = waypoint?.takeIf { it.mapId == zone.mapId }
        val mine = if (here != null) listOf(MapPlace('Q', here.x, here.y, title))
            else zone.places.filter { it.kind in "qQa" && matches(it.label, title) }
        val pick = mine.filter { it.kind == 'Q' }.ifEmpty { mine }
        // Without the size, the zone maps are about 3:2.
        val w = if (zone.width > 0) zone.width.toDouble() else 1.5
        val h = if (zone.height > 0) zone.height.toDouble() else 1.0
        val near = pick.minByOrNull { hypot((it.x - x) * w, (it.y - y) * h) } ?: return null
        val dx = (near.x - x) * w
        val dy = (near.y - y) * h
        val yards = if (zone.width > 0 && zone.height > 0) hypot(dx, dy).toInt() else null
        return Heading(near, atan2(dx, -dy), yards)
    }
}
