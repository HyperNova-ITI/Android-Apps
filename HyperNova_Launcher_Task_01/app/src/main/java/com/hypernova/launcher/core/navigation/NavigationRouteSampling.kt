package com.hypernova.launcher.core.navigation

/**
 * Reduce a route to at most [limit] points while still spanning the WHOLE route.
 *
 * This replaces `List.take(limit)`, which kept the first `limit` points and silently dropped the
 * rest. That truncation was invisible in code review and pathological on screen: the Static Maps
 * viewport auto-fits whatever geometry it is handed, so a truncated prefix renders as a perfectly
 * framed short route with the destination simply missing. Long journeys looked "cropped" while
 * every request still returned HTTP 200.
 *
 * Sampling keeps the identical point budget -- same URL length, same request cost, same single
 * non-interactive image -- but walks the entire polyline instead of its opening stretch. The first
 * and last points are always retained so the origin and destination markers stay anchored to the
 * real endpoints.
 */
internal fun List<NavigationPreviewPoint>.sampleForPreview(
    limit: Int,
): List<NavigationPreviewPoint> {
    if (limit < 2 || size <= limit) return this

    val lastIndex = size - 1
    val steps = limit - 1
    val sampled = ArrayList<NavigationPreviewPoint>(limit)
    var previousIndex = -1

    for (step in 0 until steps) {
        // Long arithmetic: step * lastIndex overflows Int for large routes on a big budget.
        val index = (step.toLong() * lastIndex / steps).toInt()
        if (index != previousIndex) {
            sampled.add(this[index])
            previousIndex = index
        }
    }
    sampled.add(this[lastIndex])

    return sampled
}
