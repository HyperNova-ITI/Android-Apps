package com.hypernova.navigation.domain.model

data class ResolvedDemoDestinations(
    val home: Place,
    val work: Place
)

object DemoDestinations {
    /*
     * Verified through Nominatim on 2026-07-23.
     * OSM node 330194927.
     */
    val HOME =
        Place(
            displayName = "Sheikh Zayed, Giza, 12588, Egypt",
            latitude = 30.0483470,
            longitude = 30.9832235,
            category = "Place",
            type = "city",
            provider = PlaceProvider.NOMINATIM,
            providerId = "nominatim:node:330194927",
            osmType = "node",
            osmId = 330194927L,
            primaryName = "Sheikh Zayed",
            formattedAddress = "Giza, 12588, Egypt"
        )

    /*
     * Verified through OSM way 648005400 and Nominatim lookup on
     * 2026-07-23. OSM tags identify the building as name=Valeo,
     * name:en=F22.
     */
    val WORK =
        Place(
            displayName =
                "Valeo, F22, Side Cairo, Alexandria Desert Road, " +
                    "Sheikh Zayed, Giza, 15311, Egypt",
            latitude = 30.0787385,
            longitude = 31.0179107,
            category = "Work",
            type = "company",
            provider = PlaceProvider.VERIFIED_OSM,
            providerId = "osm:way:648005400",
            osmType = "way",
            osmId = 648005400L,
            primaryName = "Valeo",
            formattedAddress =
                "F22, Side Cairo, Alexandria Desert Road, " +
                    "Sheikh Zayed, Giza, 15311, Egypt"
        )

    fun resolve(
        savedHome: Place?,
        savedWork: Place?
    ): ResolvedDemoDestinations =
        ResolvedDemoDestinations(
            home = savedHome ?: HOME,
            work = savedWork ?: WORK
        )
}
