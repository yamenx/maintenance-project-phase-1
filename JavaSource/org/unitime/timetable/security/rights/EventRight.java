package org.unitime.timetable.security.rights;

public enum EventRight {
    EventAddSpecial(Right.EventAddSpecial),
    EventAddCourseRelated(Right.EventAddCourseRelated),
    EventEdit(Right.EventEdit),
    EventDelete(Right.EventDelete);

    private final Right right;

    EventRight(Right right) {
        this.right = right;
    }

    public Right toRight() {
        return right;
    }
}
