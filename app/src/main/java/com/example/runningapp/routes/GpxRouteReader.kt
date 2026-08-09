package com.example.runningapp.routes

import org.xml.sax.Attributes
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.IOException
import java.io.InputStream
import javax.xml.parsers.SAXParserFactory

/**
 * One point of a Route: where, and how high the file said the ground was there.
 *
 * No time and no accuracy, unlike the [com.example.runningapp.data.TrackPoint] a Run records. A
 * Route is a line to follow rather than a recording of one, so when it was covered — if it ever was
 * — says nothing about where it goes.
 */
data class RoutePoint(
    val latitude: Double,
    val longitude: Double,
    val elevationMeters: Double?,
)

/** Why a GPX file could not become a Route. Each is shown to the runner by [gpxRefusalMessage]. */
enum class GpxRefusal {
    /** Well-formed XML, but not a GPX file — a KML export, an HTML error page saved by mistake. */
    NOT_GPX,

    /** A real GPX carrying no course: waypoints only, or empty segments. */
    NO_POINTS,

    /** More points than any route needs — see [MOST_POINTS_A_ROUTE_MAY_HAVE]. */
    TOO_LARGE,

    /** Not XML this can read at all: truncated, damaged, or a point with no position on the earth. */
    UNREADABLE,
}

/** What [GpxRouteReader] made of a file. */
sealed interface GpxReadOutcome {
    /** A course, and the name the file suggested for it — null when the file suggested none. */
    data class Read(val name: String?, val points: List<RoutePoint>) : GpxReadOutcome

    data class Refused(val reason: GpxRefusal) : GpxReadOutcome
}

/**
 * Reads a GPX file as a Route (#54).
 *
 * A whole file is one Route. GPX can hold several tracks, each of several segments, and a track's
 * segments are the breaks in whatever recorded it — a pause on the run it was exported from. A Route
 * is the line the runner means to follow, so those breaks are joined rather than kept: a Break is a
 * thing no line may cross, and a Route has none, having no recording behind it
 * ([ADR 0014](../../../../../../../docs/adr/0014-a-route-is-a-plan-not-a-recording.md), which is
 * where that is argued against
 * [ADR 0010](../../../../../../../docs/adr/0010-the-track-is-the-record-of-a-break.md)).
 *
 * `<trk>` beats `<rte>` outright when a file carries both: they describe the same outing, one as
 * recorded and one as planned, and the recorded one is the one with the detail. They are never run
 * together, which would double the course back on itself.
 *
 * Deliberately free of Android, so every rule above is pinned by a unit test rather than found on a
 * phone — the same bargain [com.example.runningapp.export.GpxWriter] makes on the way out.
 */
object GpxRouteReader {

    fun read(source: InputStream): GpxReadOutcome {
        val handler = RouteHandler()
        return try {
            newParser().parse(source, handler)
            handler.outcome()
        } catch (notGpx: NotGpxException) {
            GpxReadOutcome.Refused(GpxRefusal.NOT_GPX)
        } catch (tooLarge: TooLargeException) {
            GpxReadOutcome.Refused(GpxRefusal.TOO_LARGE)
        } catch (malformed: SAXException) {
            GpxReadOutcome.Refused(GpxRefusal.UNREADABLE)
        } catch (unreadable: IOException) {
            GpxReadOutcome.Refused(GpxRefusal.UNREADABLE)
        }
    }

    /**
     * A parser that will not fetch anything the file asks it to.
     *
     * An imported GPX is a file from somewhere else — an email attachment, a download — and an XML
     * entity declaration in one is a request for this app to read a path on the phone and hand the
     * contents back inside the parsed document. Nothing legitimate in GPX needs entities, so they
     * are refused outright and the file reads as damaged.
     *
     * Each feature is set on its own and its absence tolerated: the JVM's parser and Android's are
     * different implementations, and one refusing to admit it knows a feature name must not stop the
     * others being applied.
     */
    private fun newParser() = SAXParserFactory.newInstance().apply {
        isNamespaceAware = true
        setFeatureIfKnown("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeatureIfKnown("http://xml.org/sax/features/external-general-entities", false)
        setFeatureIfKnown("http://xml.org/sax/features/external-parameter-entities", false)
        isXIncludeAware = false
    }.newSAXParser()

    private fun SAXParserFactory.setFeatureIfKnown(name: String, value: Boolean) {
        try {
            setFeature(name, value)
        } catch (unsupported: Exception) {
            // This parser does not know the name. The others still applied.
        }
    }
}

/** Thrown the moment the root element turns out not to be `<gpx>`, so no more of the file is read. */
private class NotGpxException : SAXException()

/**
 * How many points a file may put in one Route before it is refused.
 *
 * A recorded track is about one point a second, so this is a route of some fifty-five hours — well
 * past anything a runner will follow, and short of the size where a damaged or hostile file could
 * exhaust the phone's memory while it is being read. The reader stops the moment it is passed rather
 * than at the end, so nothing outsized is ever fully in hand.
 */
private const val MOST_POINTS_A_ROUTE_MAY_HAVE = 200_000

/** Thrown the moment a file passes [MOST_POINTS_A_ROUTE_MAY_HAVE], so the rest of it is not read. */
private class TooLargeException : SAXException()

/** The two elements that carry a position, and the three whose `<name>` may title a Route. */
private val POINT_ELEMENTS = setOf("trkpt", "rtept")
private val NAMED_ELEMENTS = setOf("metadata", "trk", "rte")

private class RouteHandler : DefaultHandler() {

    /** Where in the document we are, by local element name — `[gpx, trk, trkseg, trkpt]`. */
    private val path = ArrayDeque<String>()

    private val trackPoints = mutableListOf<RoutePoint>()
    private val routePoints = mutableListOf<RoutePoint>()

    private var metadataName: String? = null
    private var trackName: String? = null
    private var routeName: String? = null

    /** The text of the element being read, or null when the current element's text is of no interest. */
    private var text: StringBuilder? = null

    /** The point being built, held open until its `</trkpt>` so its `<ele>` can land on it. */
    private var latitude: Double? = null
    private var longitude: Double? = null
    private var elevation: Double? = null

    override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
        val name = elementName(localName, qName)
        if (path.isEmpty() && name != "gpx") throw NotGpxException()
        val parent = path.lastOrNull()
        path.addLast(name)

        when {
            name == "trkpt" || name == "rtept" -> {
                latitude = attributes.coordinate("lat", limit = 90.0)
                longitude = attributes.coordinate("lon", limit = 180.0)
                elevation = null
            }
            // Only the text this reader has a use for is gathered, and only where GPX puts it. An
            // `<extensions>` block can hold an element called anything at all, including `name`.
            name == "ele" && parent in POINT_ELEMENTS -> text = StringBuilder()
            name == "name" && parent in NAMED_ELEMENTS -> text = StringBuilder()
        }
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        text?.append(ch, start, length)
    }

    override fun endElement(uri: String?, localName: String?, qName: String?) {
        val name = elementName(localName, qName)
        val gathered = text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        text = null
        path.removeLastOrNull()

        when (name) {
            "trkpt", "rtept" -> {
                // A point that does not say where it is leaves the whole file unreadable rather
                // than being dropped: a course with a hole in it is a different course, and quietly
                // importing one would have the runner following a line the file never drew.
                val point = RoutePoint(
                    latitude = latitude ?: throw SAXException("A point with no position"),
                    longitude = longitude ?: throw SAXException("A point with no position"),
                    elevationMeters = elevation,
                )
                if (name == "trkpt") trackPoints += point else routePoints += point
                if (trackPoints.size + routePoints.size > MOST_POINTS_A_ROUTE_MAY_HAVE) {
                    throw TooLargeException()
                }
                latitude = null
                longitude = null
                elevation = null
            }
            // A height that makes no sense is a missing height, not a broken file — the line is
            // still followable, and elevation gain is allowed to be absent.
            "ele" -> elevation = gathered?.toDoubleOrNull()?.takeIf { it.isFinite() }
            "name" -> when (path.lastOrNull()) {
                "metadata" -> metadataName = metadataName ?: gathered
                "trk" -> trackName = trackName ?: gathered
                "rte" -> routeName = routeName ?: gathered
            }
        }
    }

    fun outcome(): GpxReadOutcome {
        val points = trackPoints.ifEmpty { routePoints }
        if (points.isEmpty()) return GpxReadOutcome.Refused(GpxRefusal.NO_POINTS)
        return GpxReadOutcome.Read(
            // The file's own title for the whole thing first, then whichever part the course came
            // from. A track's name is usually the exporting app's ("Afternoon Run"), so it is the
            // weaker of the two.
            name = metadataName ?: trackName ?: routeName,
            points = points,
        )
    }

    /** A namespace-aware parse fills `localName`; a fallback for parsers that only give `qName`. */
    private fun elementName(localName: String?, qName: String?): String =
        localName?.takeIf { it.isNotEmpty() } ?: qName.orEmpty().substringAfterLast(':')

    private fun Attributes.coordinate(name: String, limit: Double): Double {
        val stated = getValue(name) ?: throw SAXException("A point with no $name")
        val value = stated.trim().toDoubleOrNull() ?: throw SAXException("A $name that is not a number")
        if (!value.isFinite() || value < -limit || value > limit) {
            throw SAXException("A $name off the earth")
        }
        return value
    }
}
