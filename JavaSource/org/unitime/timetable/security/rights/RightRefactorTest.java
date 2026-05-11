package org.unitime.timetable.security.rights;

public class RightRefactorTest {

    public static void main(String[] args) {

        System.out.println("Running Refactoring Validation Test...");

        // Validate CourseRight
        if (CourseRight.InstructionalOfferings == null) {
            throw new RuntimeException("CourseRight test failed");
        }

        // Validate EventRight
        if (EventRight.EventAdd == null) {
            throw new RuntimeException("EventRight test failed");
        }

        // Validate RoomRight
        if (RoomRight.RoomEdit == null) {
            throw new RuntimeException("RoomRight test failed");
        }

        System.out.println(CourseRight.InstructionalOfferings);
        System.out.println(EventRight.EventAddCourseRelated);
        System.out.println(RoomRight.RoomEdit);

        System.out.println("All refactoring tests passed successfully.");
    }
}
