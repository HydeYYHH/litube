package com.hhst.youtubelite.player.engine.datasource;

import androidx.annotation.NonNull;

import com.google.common.collect.ForwardingMap;
import com.google.common.collect.ForwardingSet;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

public final class NullFilteringHeadersMap extends ForwardingMap<String, List<String>> {
	private final Map<String, List<String>> headers;

	public NullFilteringHeadersMap(final Map<String, List<String>> headers) {
		this.headers = headers;
	}

	@NonNull
	@Override
	protected Map<String, List<String>> delegate() {
		return headers;
	}

	@Override
	public boolean containsKey(final Object key) {
		return key != null && super.containsKey(key);
	}

	@Override
	public List<String> get(final Object key) {
		return key == null ? null : super.get(key);
	}

	@NonNull
	@Override
	public Set<String> keySet() {
		return new NullFilteringSet(super.keySet());
	}

	@NonNull
	@Override
	public Set<Entry<String, List<String>>> entrySet() {
		return new NullFilteringEntrySet(super.entrySet());
	}

	private static final class NullFilteringSet extends ForwardingSet<String> {
		private final Set<String> delegate;

		NullFilteringSet(final Set<String> delegate) {
			this.delegate = delegate;
		}

		@NonNull
		@Override
		protected Set<String> delegate() {
			return delegate;
		}

		@NonNull
		@Override
		public Iterator<String> iterator() {
			return new NullFilteringIterator<>(super.iterator());
		}

		@NonNull
		@Override
		public Object[] toArray() {
			return standardToArray();
		}

		@NonNull
		@Override
		public <T> T[] toArray(@NonNull final T[] array) {
			return standardToArray(array);
		}
	}

	private static final class NullFilteringEntrySet extends ForwardingSet<Entry<String, List<String>>> {
		private final Set<Entry<String, List<String>>> delegate;

		NullFilteringEntrySet(final Set<Entry<String, List<String>>> delegate) {
			this.delegate = delegate;
		}

		@NonNull
		@Override
		protected Set<Entry<String, List<String>>> delegate() {
			return delegate;
		}

		@NonNull
		@Override
		public Iterator<Entry<String, List<String>>> iterator() {
			return new NullFilteringEntryIterator(super.iterator());
		}

		@NonNull
		@Override
		public Object[] toArray() {
			return standardToArray();
		}

		@NonNull
		@Override
		public <T> T[] toArray(@NonNull final T[] array) {
			return standardToArray(array);
		}
	}

	private static final class NullFilteringIterator<T> implements Iterator<T> {
		private final Iterator<T> delegate;
		private T next;

		NullFilteringIterator(final Iterator<T> delegate) {
			this.delegate = delegate;
			advance();
		}

		private void advance() {
			while (delegate.hasNext()) {
				next = delegate.next();
				if (next != null) return;
			}
			next = null;
		}

		@Override
		public boolean hasNext() {
			return next != null;
		}

		@Override
		public T next() {
			if (next == null) throw new NoSuchElementException();
			final T result = next;
			advance();
			return result;
		}

		@Override
		public void remove() {
			throw new UnsupportedOperationException();
		}
	}

	private static final class NullFilteringEntryIterator implements Iterator<Entry<String, List<String>>> {
		private final Iterator<Entry<String, List<String>>> delegate;
		private Entry<String, List<String>> next;

		NullFilteringEntryIterator(final Iterator<Entry<String, List<String>>> delegate) {
			this.delegate = delegate;
			advance();
		}

		private void advance() {
			while (delegate.hasNext()) {
				next = delegate.next();
				if (next != null && next.getKey() != null) return;
			}
			next = null;
		}

		@Override
		public boolean hasNext() {
			return next != null;
		}

		@Override
		public Entry<String, List<String>> next() {
			if (next == null) throw new NoSuchElementException();

			final Entry<String, List<String>> result = next;
			advance();
			return result;
		}

		@Override
		public void remove() {
			throw new UnsupportedOperationException();
		}
	}
}
