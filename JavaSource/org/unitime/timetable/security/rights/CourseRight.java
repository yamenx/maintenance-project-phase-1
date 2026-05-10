package org.unitime.timetable.security.rights;

public enum CourseRight {
    InstructionalOfferings(Right.InstructionalOfferings),
    Classes(Right.Classes),
    ClassAssignments(Right.ClassAssignments);

    private final Right right;

    CourseRight(Right right) {
        this.right = right;
    }

    public Right toRight() {
        return right;
    }
}
