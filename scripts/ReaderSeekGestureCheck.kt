import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.ReaderSeekAction
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.ReaderSeekGesture
import kotlin.math.abs

fun checkReaderSeekGesture() {
    fun gesture(start: Float = .5f, origin: Float? = .9f, x: Float = 124f, y: Float = 0f,
                temporary: Float? = start) =
        ReaderSeekGesture(start, origin, temporary, x, y, 24f, 200f, 6f)
    fun ReaderSeekGesture.at(progress: Float) =
        check(preview != null && abs(preview!! - progress) < .000001f) { "Expected $progress, got $preview" }

    // Grabbing either visible edge or the thumb's extension keeps the initial offset.
    for (offset in listOf(-20f, -11f, 11f, 20f)) {
        val drag = gesture(x = 124f + offset)
        check(drag.preview == null && drag.action == ReaderSeekAction.Cancel)
        check(!drag.update(129f + offset, 0f) && drag.preview == null)
        check(!drag.update(131f + offset, 0f))
        drag.at(.535f)
        check(drag.action == ReaderSeekAction.Commit)
    }

    // The return target must not steal a grab on the visible thumb when the circles are near.
    val near = gesture(origin = .54f, x = 113f)
    check(near.update(120f, 0f))
    near.at(.54f)
    check(near.action == ReaderSeekAction.Return)
    check(!near.update(140f, 0f)) // Final pointer-up coordinates leave the snap.
    near.at(.635f)
    check(near.action == ReaderSeekAction.Commit)
    check(near.update(120f, 0f)) // A final position can also enter it again.
    check(near.action == ReaderSeekAction.Return)

    // A formal marker 5 dp away is ready to snap, but DOWN alone never gives feedback.
    for (direction in listOf(-1f, 1f)) {
        val closeOrigin = .5f + direction * .025f
        val close = gesture(origin = closeOrigin)
        check(!close.update(124f, 0f) && close.preview == null && !close.snapped)
        check(close.update(124f + direction * 7f, 0f))
        close.at(closeOrigin)
        check(close.action == ReaderSeekAction.Return)
    }

    val temporary = gesture(origin = null)
    check(temporary.origin == .5f && !temporary.update(124f, 0f))
    check(!temporary.snapped && temporary.action == ReaderSeekAction.Cancel)
    check(!temporary.update(137f, 0f))
    check(temporary.update(127f, 0f))
    temporary.at(.5f)
    check(temporary.action == ReaderSeekAction.Cancel)
    check(!temporary.update(129f, 0f) && temporary.snapped) // 5 dp retains an existing snap.
    check(!temporary.update(131f, 0f) && !temporary.snapped) // More than 6 dp releases it.
    check(!temporary.update(129f, 0f) && !temporary.snapped) // 5 dp cannot enter anew.
    check(temporary.update(127f, 0f))
    check(!temporary.update(126f, 0f)) // Feedback only on entry.

    // The thumb can display a pending B while the only confirmed return position is A.
    // DOWN receives A explicitly; neither the marker nor its cancel snap may be invented at B.
    val loading = gesture(start = .8f, origin = null, temporary = .3f, x = 184f)
    check(loading.origin == .3f && loading.preview == null)
    check(loading.update(84f, 0f)) // The first movement can already reach the distant confirmed origin.
    loading.at(.3f)
    check(loading.action == ReaderSeekAction.Cancel)
    check(!loading.update(184f, 0f))
    loading.at(.8f)
    check(!loading.snapped && loading.action == ReaderSeekAction.Commit)

    // A frozen null remains absent even if the thumb has a valid pending progress value.
    for (missing in listOf(null, Float.NaN, Float.POSITIVE_INFINITY)) {
        val noOrigin = gesture(start = .8f, origin = null, temporary = missing, x = 184f)
        check(noOrigin.origin == null)
        check(!noOrigin.update(164f, 0f))
        check(!noOrigin.update(184f, 0f))
        noOrigin.at(.8f)
        check(!noOrigin.snapped && noOrigin.action == ReaderSeekAction.Commit)
    }

    // The formal point wins over a distinct temporary capture and stays a Return for this gesture,
    // including when a previous return finishes and the UI clears its live formal marker mid-drag.
    val frozenFormal = gesture(origin = .3f, temporary = .7f)
    check(frozenFormal.origin == .3f && frozenFormal.update(84f, 0f))
    check(frozenFormal.action == ReaderSeekAction.Return)
    val temporaryOnly = gesture(origin = null, temporary = .3f)
    check(temporaryOnly.update(84f, 0f) && temporaryOnly.action == ReaderSeekAction.Cancel)

    // Nearby temporary points retain the leave-and-return arming rule of a normal thumb grab.
    val closeTemporary = gesture(origin = null, temporary = .52f)
    check(!closeTemporary.update(124f, 0f) && !closeTemporary.snapped)
    check(!closeTemporary.update(140f, 0f))
    check(closeTemporary.update(129f, 0f) && closeTemporary.action == ReaderSeekAction.Cancel)

    val formal = gesture(origin = .3f)
    check(formal.update(87f, 0f))
    formal.at(.3f)
    check(formal.action == ReaderSeekAction.Return)
    check(!formal.update(70f, 0f) && !formal.snapped)
    check(formal.action == ReaderSeekAction.Commit) // Passing through does not commit a return.

    // Exposed small-circle pixels return, and dragging their target uses absolute coordinates.
    val exposed = gesture(origin = .54f, x = 138f)
    check(exposed.action == ReaderSeekAction.Return && exposed.preview == null)
    check(!exposed.update(158f, 0f))
    exposed.at(.67f)
    check(exposed.action == ReaderSeekAction.Commit)

    // Transparent overlapping targets go to the closer center, except the visible thumb wins.
    check(gesture(origin = .66f, x = 140f).action == ReaderSeekAction.Cancel)
    check(gesture(origin = .66f, x = 143f).action == ReaderSeekAction.Return)
    check(gesture(origin = .54f, x = 131f).action == ReaderSeekAction.Cancel)
    check(gesture(origin = .66f, x = 156f, y = 24f).action == ReaderSeekAction.Return)
    val outsideTarget = gesture(origin = .66f, x = 156f, y = 25f)
    check(outsideTarget.action == ReaderSeekAction.Commit && outsideTarget.preview != null)

    // A fully covered formal marker can be tapped, but a drag still preserves the thumb grab.
    val covered = gesture(origin = .52f, x = 113f)
    check(covered.action == ReaderSeekAction.Return && covered.preview == null)
    check(!covered.update(113f, 0f) && !covered.snapped)
    check(covered.update(120f, 0f))
    covered.at(.52f)
    check(covered.snapped)
    check(!covered.update(130f, 0f) && !covered.snapped)
    check(covered.update(121f, 0f))
    covered.at(.52f)
    check(covered.action == ReaderSeekAction.Return)

    val track = gesture(x = 60f)
    track.at(.18f)
    check(track.action == ReaderSeekAction.Commit)
    check(!track.update(62f, 0f))
    track.at(.19f) // Track presses adjust immediately, without the grab slop.

    val startEdge = gesture(start = 0f, origin = .5f, x = 13f)
    check(!startEdge.update(-20f, 0f))
    startEdge.at(0f)
    val endEdge = gesture(start = 1f, origin = .5f, x = 235f)
    check(!endEdge.update(260f, 0f))
    endEdge.at(1f)
    val snapAtStart = gesture(start = .5f, origin = 0f)
    check(snapAtStart.update(-20f, 0f))
    snapAtStart.at(0f)
    check(snapAtStart.action == ReaderSeekAction.Return)
    val snapAtEnd = gesture(start = .5f, origin = 1f)
    check(snapAtEnd.update(260f, 0f))
    snapAtEnd.at(1f)

    println("Reader seek gesture checks passed")
}
