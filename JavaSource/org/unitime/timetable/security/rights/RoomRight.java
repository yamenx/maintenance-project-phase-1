package org.unitime.timetable.security.rights;

public enum RoomRight {
    Rooms(Right.Rooms),
    RoomDetail(Right.RoomDetail),
    RoomEdit(Right.RoomEdit);

    private final Right right;

    RoomRight(Right right) {
        this.right = right;
    }

    public Right toRight() {
        return right;
    }
}
