package io.github.nexalloy.revanced.facebook

import com.google.common.collect.ImmutableList
import java.util.LinkedList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class FacebookListResultsTest {
    @Test
    fun `immutable return can be cast by the hook bridge`() {
        val type = ImmutableList::class.java
        val result = assertNotNull(buildEmptyListReturn(type))
        assertTrue(type.cast(result).isEmpty())
        assertIs<ImmutableList<*>>(result)
    }

    @Test
    fun `list interfaces and ArrayList retain compatible empty results`() {
        for (type in listOf(List::class.java, Collection::class.java, Iterable::class.java, ArrayList::class.java)) {
            val result = assertNotNull(buildEmptyListReturn(type))
            assertTrue(type.isInstance(result))
            assertEquals(0, (result as Collection<*>).size)
        }
    }

    @Test
    fun `unsupported concrete collections keep the original call`() {
        assertNull(buildEmptyListReturn(LinkedList::class.java))
        assertNull(buildEmptyListReturn(Set::class.java))
        assertNull(buildEmptyListReturn(String::class.java))
    }

    @Test
    fun `mutable replacements are not shared between invocations`() {
        val first = buildEmptyListReturn(ArrayList::class.java)
        val second = buildEmptyListReturn(ArrayList::class.java)
        assertNotSame(first, second)
        (first as ArrayList<Any?>).add("modified by caller")
        assertTrue((second as List<*>).isEmpty())
    }
}
