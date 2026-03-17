package com.hhst.youtubelite.extractor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

public class ExtractionSessionTest {

	@Test
	public void cancel_cancelsRegisteredRequestsAndFutureRegistrationsImmediately() {
		final ExtractionSession session = new ExtractionSession();
		final AtomicInteger cancellations = new AtomicInteger();

		session.register(cancellations::incrementAndGet);
		session.register(cancellations::incrementAndGet);

		session.cancel();
		session.register(cancellations::incrementAndGet);

		assertTrue(session.isCancelled());
		assertEquals(3, cancellations.get());
	}
}
