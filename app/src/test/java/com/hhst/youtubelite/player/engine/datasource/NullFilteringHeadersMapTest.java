package com.hhst.youtubelite.player.engine.datasource;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

public class NullFilteringHeadersMapTest {

	@Test
	public void containsKeyAndGet_ignoreNullKeys() {
		final NullFilteringHeadersMap headers = new NullFilteringHeadersMap(headersWithNullEntries());

		assertFalse(headers.containsKey(null));
		assertNull(headers.get(null));
		assertEquals(List.of("application/json"), headers.get("Accept"));
	}

	@Test
	public void keySet_filtersNullEntriesFromIterationAndArrays() {
		final NullFilteringHeadersMap headers = new NullFilteringHeadersMap(headersWithNullEntries());

		assertEquals(List.of("Accept", "Cookie"), new ArrayList<>(headers.keySet()));
		assertArrayEquals(new String[]{"Accept", "Cookie"}, headers.keySet().toArray(new String[0]));
	}

	@Test
	public void entrySet_filtersEntriesWithNullKeys() {
		final NullFilteringHeadersMap headers = new NullFilteringHeadersMap(headersWithNullEntries());
		final List<Map.Entry<String, List<String>>> entries = new ArrayList<>(headers.entrySet());

		assertEquals(2, entries.size());
		assertEquals("Accept", entries.get(0).getKey());
		assertEquals(List.of("application/json"), entries.get(0).getValue());
		assertEquals("Cookie", entries.get(1).getKey());
		assertEquals(List.of("SID=abc"), entries.get(1).getValue());
		assertArrayEquals(
						new Object[]{entries.get(0), entries.get(1)},
						headers.entrySet().toArray());
	}

	@Test
	public void iterators_areExhaustiveAndNonRemovable() {
		final NullFilteringHeadersMap headers = new NullFilteringHeadersMap(headersWithNullEntries());
		final Iterator<String> keys = headers.keySet().iterator();

		assertEquals("Accept", keys.next());
		assertEquals("Cookie", keys.next());
		assertThrows(NoSuchElementException.class, keys::next);
		assertThrows(UnsupportedOperationException.class, keys::remove);
	}

	@Test
	public void entrySet_iteratorAndTypedArray_filterNullKeysConsistently() {
		final NullFilteringHeadersMap headers = new NullFilteringHeadersMap(headersWithNullEntries());
		final Iterator<Map.Entry<String, List<String>>> entries = headers.entrySet().iterator();

		assertEquals("Accept", entries.next().getKey());
		assertEquals("Cookie", entries.next().getKey());
		assertThrows(NoSuchElementException.class, entries::next);
		assertThrows(UnsupportedOperationException.class, entries::remove);

		final Map.Entry<String, List<String>>[] typedEntries = headers.entrySet().toArray(new Map.Entry[0]);
		assertEquals(2, typedEntries.length);
		assertEquals("Accept", typedEntries[0].getKey());
		assertEquals("Cookie", typedEntries[1].getKey());
	}

	private static Map<String, List<String>> headersWithNullEntries() {
		final Map<String, List<String>> headers = new LinkedHashMap<>();
		headers.put("Accept", List.of("application/json"));
		headers.put(null, List.of("ignored"));
		headers.put("Cookie", List.of("SID=abc"));
		return headers;
	}
}
