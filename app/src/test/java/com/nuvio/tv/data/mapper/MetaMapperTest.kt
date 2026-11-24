package com.nuvio.tv.data.mapper

import com.nuvio.tv.data.remote.dto.MetaPreviewDto
import com.nuvio.tv.data.remote.dto.MetaResponseDto
import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Test

class MetaMapperTest {

    private val adapter = Moshi.Builder().build().adapter(MetaResponseDto::class.java)

    @Test
    fun `localized addon certification takes priority`() {
        val result = parse(
            """
            {
              "meta": {
                "id": "show",
                "type": "series",
                "name": "Show",
                "app_extras": {
                  "certificationLocal": " 16 ",
                  "certification": "TV-MA"
                }
              }
            }
            """.trimIndent()
        )

        assertEquals("16", result.ageRating)
    }

    @Test
    fun `default addon certification is used when localized value is blank`() {
        val result = parse(
            """
            {
              "meta": {
                "id": "movie",
                "type": "movie",
                "name": "Movie",
                "app_extras": {
                  "certificationLocal": " ",
                  "certification": " PG-13 "
                }
              }
            }
            """.trimIndent()
        )

        assertEquals("PG-13", result.ageRating)
    }

    @Test
    fun `catalog and detail map provider popularity without losing malformed titles`() {
        val preview = Moshi.Builder().build().adapter(MetaPreviewDto::class.java)
        for ((wire, expected) in listOf("3.25" to 3.25, "\"0\"" to 0.0, "-1" to null, "{}" to null)) {
            val item = """{"id":"tt1","name":"Title","type":"movie","popularity":$wire}"""
            val poster = requireNotNull(preview.fromJson(item)?.toDomainOrNull("movie"))
            assertEquals(expected, poster.popularity)
            assertEquals(expected, parse("""{"meta":$item}""").popularity)
        }
    }

    private fun parse(json: String) = requireNotNull(adapter.fromJson(json)?.meta).toDomain()
}
