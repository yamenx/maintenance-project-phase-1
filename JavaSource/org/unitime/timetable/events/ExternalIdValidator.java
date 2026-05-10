package org.unitime.timetable.events;

import java.util.Objects;

public class ExternalIdValidator {

    public static boolean compareExternalIds(String userId, String instructorId) {
        return Objects.equals(userId, instructorId);
    }

}