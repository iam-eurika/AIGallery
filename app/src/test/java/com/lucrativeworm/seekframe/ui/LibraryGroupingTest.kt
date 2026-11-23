package com.lucrativeworm.seekframe.ui

import com.lucrativeworm.seekframe.data.Photo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.Locale

class LibraryGroupingTest {

    private lateinit var savedLocale: Locale

    @Before fun setLocale() { savedLocale = Locale.getDefault(); Locale.setDefault(Locale.US) }
    @After fun restoreLocale() = Locale.setDefault(savedLocale)

    private fun at(y: Int, m: Int, d: Int, id: Long, video: Boolean = false) = Photo(
        id = id, isVideo = video,
        dateTaken = LocalDate.of(y, m, d).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
    )

    // Newest first, as the database returns them.
    private val library = listOf(
        at(2026, 11, 18, 1),
        at(2026, 11, 2, 2, video = true),
        at(2026, 10, 30, 3),
        at(2026, 3, 1, 4, video = true),
        at(2024, 7, 4, 5),
    )

    @Test
    fun `all newest keeps database order`() {
        assertEquals(listOf(1L, 2, 3, 4, 5), applyFilter(library, LibraryFilter()).map { it.id })
    }

    @Test
    fun `photos and videos filters`() {
        assertEquals(listOf(1L, 3, 5), applyFilter(library, LibraryFilter(MediaKind.PHOTOS)).map { it.id })
        assertEquals(listOf(2L, 4), applyFilter(library, LibraryFilter(MediaKind.VIDEOS)).map { it.id })
    }

    @Test
    fun `oldest first reverses`() {
        assertEquals(
            listOf(4L, 2),
            applyFilter(library, LibraryFilter(MediaKind.VIDEOS, SortOrder.OLDEST)).map { it.id },
        )
    }

    @Test
    fun `default filter detection`() {
        assertTrue(LibraryFilter().isDefault)
        assertFalse(LibraryFilter(sort = SortOrder.OLDEST).isDefault)
    }

    @Test
    fun `months get relative, short and full labels`() {
        val sections = groupByMonth(library, ZoneOffset.UTC, now = YearMonth.of(2026, 11))
        assertEquals(listOf("2026-11", "2026-10", "2026-03", "2024-07"), sections.map { it.key })
        assertEquals(listOf("This month", "Last month", "March", "July 2024"), sections.map { it.label })
        assertEquals(listOf(1L, 2), sections[0].photos.map { it.id })
    }

    @Test
    fun `last month works across a year boundary`() {
        val sections = groupByMonth(listOf(at(2025, 12, 31, 9)), ZoneOffset.UTC, now = YearMonth.of(2026, 1))
        assertEquals("Last month", sections.single().label)
    }

    @Test
    fun `empty library has no sections`() {
        assertTrue(groupByMonth(emptyList(), ZoneOffset.UTC, YearMonth.of(2026, 1)).isEmpty())
    }

    @Test
    fun `search query emptiness`() {
        assertTrue(SearchInput.Query().isEmpty)
        assertTrue(SearchInput.Query(text = "   ").isEmpty)
        assertFalse(SearchInput.Query(text = "dog").isEmpty)
        assertFalse(SearchInput.Query(photos = listOf(SearchPhoto.Library(Photo(id = 3)))).isEmpty)
        assertEquals("photo:3", SearchPhoto.Library(Photo(id = 3)).key)
    }
}
