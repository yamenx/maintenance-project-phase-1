package org.unitime.timetable.events;

import static org.junit.Assert.*;
import org.junit.Test;

public class ExternalIdValidatorTest {

    @Test
    public void testNullComparison() {
        assertFalse(
                ExternalIdValidator.compareExternalIds(null, "INS123")
        );
    }

    @Test
    public void testEqualIds() {
        assertTrue(
                ExternalIdValidator.compareExternalIds("INS123", "INS123")
        );
    }

    @Test
    public void testDifferentIds() {
        assertFalse(
                ExternalIdValidator.compareExternalIds("INS123", "INS999")
        );
    }
}
