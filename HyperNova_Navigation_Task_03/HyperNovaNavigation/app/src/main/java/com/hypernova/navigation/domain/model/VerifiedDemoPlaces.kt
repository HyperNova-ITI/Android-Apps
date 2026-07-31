package com.hypernova.navigation.domain.model

/**
 * Real OpenStreetMap-backed places used only as a deterministic
 * demo fallback when the public nearby-search provider is unavailable.
 *
 * These entries are NOT fake POIs.
 *
 * Verified against current OpenStreetMap-backed records on 2026-07-31.
 *
 * Normal operation must continue to prefer live provider results.
 */
object VerifiedDemoPlaces {

    /*
     * OSM way 287108580
     * amenity=parking
     *
     * Mall of Arabia surface parking.
     */
    private val MALL_OF_ARABIA_PARKING =
        Place(
            displayName =
                "Mall of Arabia Parking, 6th of October, Giza, Egypt",
            latitude = 30.00755,
            longitude = 30.97215,
            category = "amenity",
            type = "parking",
            provider = PlaceProvider.VERIFIED_OSM,
            providerId = "osm:way:287108580",
            osmType = "way",
            osmId = 287108580L,
            primaryName = "Mall of Arabia Parking",
            formattedAddress =
                "Mall of Arabia, 6th of October, Giza, Egypt",
            subcategory = "parking"
        )

    /*
     * OSM way 291685353
     * amenity=fuel
     *
     * Approximately 1 km from the ITI Smart Village demo origin.
     */
    private val MOBIL_DANDY =
        Place(
            displayName =
                "Mobil, Cairo-Alexandria Desert Road, Giza, Egypt",
            latitude = 30.06387,
            longitude = 31.02587,
            category = "amenity",
            type = "fuel",
            provider = PlaceProvider.VERIFIED_OSM,
            providerId = "osm:way:291685353",
            osmType = "way",
            osmId = 291685353L,
            primaryName = "Mobil",
            formattedAddress =
                "Near Dandy Mall, Cairo-Alexandria Desert Road, Giza, Egypt",
            operator = "Mobil",
            subcategory = "fuel"
        )

    /*
     * OSM way 44534221
     * amenity=fuel
     */
    private val EMARAT_MISR_DANDY =
        Place(
            displayName =
                "Emarat Misr, Cairo-Alexandria Desert Road, Giza, Egypt",
            latitude = 30.06163,
            longitude = 31.02991,
            category = "amenity",
            type = "fuel",
            provider = PlaceProvider.VERIFIED_OSM,
            providerId = "osm:way:44534221",
            osmType = "way",
            osmId = 44534221L,
            primaryName = "Emarat Misr",
            formattedAddress =
                "Near Dandy Mall, Cairo-Alexandria Desert Road, Giza, Egypt",
            operator = "Emarat",
            subcategory = "fuel",
            openingHours = "24/7"
        )

    /*
     * OSM node 6515473816
     * amenity=fast_food
     *
     * Located inside Smart Village.
     */
    private val SMART_DELI =
        Place(
            displayName =
                "Smart Deli, Smart Village, Giza, Egypt",
            latitude = 30.07700,
            longitude = 31.02209,
            category = "amenity",
            type = "fast_food",
            provider = PlaceProvider.VERIFIED_OSM,
            providerId = "osm:node:6515473816",
            osmType = "node",
            osmId = 6515473816L,
            primaryName = "Smart Deli",
            formattedAddress =
                "Smart Village, Cairo-Alexandria Desert Road, Giza, Egypt",
            subcategory = "fast_food"
        )

    /*
     * OSM way 771243782
     * amenity=hospital
     */
    private val SHEIKH_ZAYED_SPECIALIZED_HOSPITAL =
        Place(
            displayName =
                "Sheikh Zayed Specialized Hospital, Sheikh Zayed, Giza, Egypt",
            latitude = 30.03214,
            longitude = 31.00341,
            category = "amenity",
            type = "hospital",
            provider = PlaceProvider.VERIFIED_OSM,
            providerId = "osm:way:771243782",
            osmType = "way",
            osmId = 771243782L,
            primaryName =
                "Sheikh Zayed Specialized Hospital",
            formattedAddress =
                "Sheikh Zayed City, Giza, Egypt",
            subcategory = "hospital"
        )

    /*
     * OSM node 5769888882
     * amenity=hospital
     * healthcare=hospital
     */
    private val SENIORS_DENTAL_CLINIC =
        Place(
            displayName =
                "Seniors Dental Clinic, Sheikh Zayed, Giza, Egypt",
            latitude = 30.03025,
            longitude = 31.00088,
            category = "amenity",
            type = "hospital",
            provider = PlaceProvider.VERIFIED_OSM,
            providerId = "osm:node:5769888882",
            osmType = "node",
            osmId = 5769888882L,
            primaryName = "Seniors Dental Clinic",
            formattedAddress =
                "Sheikh Zayed City, Giza, Egypt",
            subcategory = "hospital"
        )

    /*
     * OSM way 291683289
     * shop=mall
     *
     * Approximately 1 km from the ITI Smart Village demo origin.
     */
    private val DANDY_MALL =
        Place(
            displayName =
                "Dandy Mall, Cairo-Alexandria Desert Road, Giza, Egypt",
            latitude = 30.06327,
            longitude = 31.02803,
            category = "shop",
            type = "mall",
            provider = PlaceProvider.VERIFIED_OSM,
            providerId = "osm:way:291683289",
            osmType = "way",
            osmId = 291683289L,
            primaryName = "Dandy Mall",
            formattedAddress =
                "Cairo-Alexandria Desert Road, Giza, Egypt",
            subcategory = "mall"
        )

    /*
     * OSM way 1456875677
     * shop=mall
     */
    private val PACE_MALL =
        Place(
            displayName =
                "Pace Mall, Sheikh Zayed, Giza, Egypt",
            latitude = 30.03205,
            longitude = 31.00099,
            category = "shop",
            type = "mall",
            provider = PlaceProvider.VERIFIED_OSM,
            providerId = "osm:way:1456875677",
            osmType = "way",
            osmId = 1456875677L,
            primaryName = "Pace Mall",
            formattedAddress =
                "Sheikh Zayed City, Giza, Egypt",
            subcategory = "mall"
        )

    /*
     * OSM way 1456875678
     * shop=mall
     */
    private val ATRIUM_MALL =
        Place(
            displayName =
                "Atrium Mall, Sheikh Zayed, Giza, Egypt",
            latitude = 30.03183,
            longitude = 30.99996,
            category = "shop",
            type = "mall",
            provider = PlaceProvider.VERIFIED_OSM,
            providerId = "osm:way:1456875678",
            osmType = "way",
            osmId = 1456875678L,
            primaryName = "Atrium Mall",
            formattedAddress =
                "Sheikh Zayed City, Giza, Egypt",
            subcategory = "mall"
        )

    fun forCategory(
        category: NearbyCategory
    ): List<Place> =
        when (category) {
            NearbyCategory.PARKING ->
                listOf(
                    MALL_OF_ARABIA_PARKING
                )

            NearbyCategory.FUEL ->
                listOf(
                    MOBIL_DANDY,
                    EMARAT_MISR_DANDY
                )

            NearbyCategory.FOOD ->
                listOf(
                    SMART_DELI
                )

            NearbyCategory.HOSPITAL ->
                listOf(
                    SHEIKH_ZAYED_SPECIALIZED_HOSPITAL,
                    SENIORS_DENTAL_CLINIC
                )

            NearbyCategory.SHOPPING ->
                listOf(
                    DANDY_MALL,
                    PACE_MALL,
                    ATRIUM_MALL
                )
        }
}
