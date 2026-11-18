package com.testpay.shield.features;

import java.time.Instant;

/** Product changes from release_notes.md that change what the data means over time. */
public final class ReleaseCalendar {
    private ReleaseCalendar() {}

    /** Completion signature launched December 2025, rolled out team by team, optional per team. */
    public static final Instant COMPLETION_SIGNATURE_LAUNCH = Instant.parse("2025-12-01T00:00:00Z");

    /** Invoice status (open / paid / disputed) added June 2026 and backfilled: never a model input. */
    public static final Instant INVOICE_STATUS_LAUNCH = Instant.parse("2026-06-01T00:00:00Z");
}
