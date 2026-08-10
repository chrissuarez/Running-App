package com.example.runningapp.routes

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.ext.DefaultHandler2
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
            val reader = newParser().xmlReader
            reader.contentHandler = handler
            // The doctype refusal is asked for twice, because neither way holds on both platforms:
            // the factory feature above is the JVM's and Android ignores it, while this one is SAX's
            // own and reports the declaration as it is read. Whichever is honoured, the file is
            // refused before a single entity is expanded.
            ifSupported {
                reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
            }
            reader.parse(InputSource(source))
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
     * Every setting is applied on its own and its absence tolerated, XInclude included: the JVM's
     * parser and Android's are different implementations, and one refusing to admit it knows a name
     * must not stop the others being applied.
     *
     * XInclude is the one that has to be asked for this way rather than simply assigned. Android's
     * parser throws `UnsupportedOperationException` from `setXIncludeAware` — it reports its
     * specification as "Unknown" version "0.0" and refuses the whole family of version-gated
     * setters — so an unguarded assignment reads fine, passes every JVM unit test, and then takes
     * the app down the first time a file is picked on a phone. Tolerating it costs nothing: a
     * parser that will not admit it knows XInclude does not perform XInclude.
     */
    private fun newParser() = SAXParserFactory.newInstance().apply {
        isNamespaceAware = true
        setFeatureIfKnown("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeatureIfKnown("http://xml.org/sax/features/external-general-entities", false)
        setFeatureIfKnown("http://xml.org/sax/features/external-parameter-entities", false)
        ifSupported { isXIncludeAware = false }
    }.newSAXParser()

    private fun SAXParserFactory.setFeatureIfKnown(name: String, value: Boolean) {
        ifSupported { setFeature(name, value) }
    }

    private inline fun ifSupported(apply: () -> Unit) {
        try {
            apply()
        } catch (unsupported: Exception) {
            // This parser does not know the setting. The others still applied.
        }
    }
}

/** Thrown the moment a doctype declaration is read, so no entity in it is ever expanded. */
private class DoctypeException : SAXException()

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

/**
 * How long the text of one element — a `<name>`, a height — may run before the file is refused.
 *
 * Ten thousand characters is a route name some hundred times longer than the longest anyone would
 * write, and a height thousands of times longer than a number needs, so nothing a real exporter
 * writes comes close. It is here for what a damaged or hostile file puts there instead: text is the
 * one part of a GPX that grows without adding a point, so this is the bound that
 * [MOST_POINTS_A_ROUTE_MAY_HAVE] cannot be.
 */
private const val MOST_CHARACTERS_ONE_ELEMENT_MAY_HAVE = 10_000

/**
 * Thrown the moment a file passes [MOST_POINTS_A_ROUTE_MAY_HAVE] or
 * [MOST_CHARACTERS_ONE_ELEMENT_MAY_HAVE], so the rest of it is not read.
 */
private class TooLargeException : SAXException()

/** The two elements that carry a position, and the three whose `<name>` may title a Route. */
private val POINT_ELEMENTS = setOf("trkpt", "rtept")
private val NAMED_ELEMENTS = setOf("metadata", "trk", "rte")

private class RouteHandler : DefaultHandler2() {

    /**
     * A file that declares a doctype is refused here, unread.
     *
     * Nothing legitimate in GPX declares one, and a declaration is where an imported file asks for
     * work to be done on its behalf: an external entity is a request to read a path on the phone and
     * hand the contents back inside the document, and an internal one is a request to expand a short
     * string into an arbitrarily long one — a few hundred bytes of nested declarations become
     * gigabytes in memory, which [MOST_POINTS_A_ROUTE_MAY_HAVE] cannot bound because the growth is
     * inside a name, not in the number of points.
     *
     * Android expands internal entities and does not honour the factory's doctype feature, so on the
     * only platform this app ships to, this callback is the whole of the defence.
     */
    override fun startDTD(name: String?, publicId: String?, systemId: String?) {
        throw DoctypeException()
    }

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
        val gathering = text ?: return
        // A name or a height is one short string, and the file is refused the moment it turns out to
        // be holding something else there. [MOST_POINTS_A_ROUTE_MAY_HAVE] does not bound this: it
        // counts points, while this grows inside a single element, so one enormous `<name>` would
        // exhaust the heap while the point count sat at zero.
        if (gathering.length + length > MOST_CHARACTERS_ONE_ELEMENT_MAY_HAVE) throw TooLargeException()
        gathering.append(ch, start, length)
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
