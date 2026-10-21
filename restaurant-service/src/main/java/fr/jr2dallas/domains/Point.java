package fr.jr2dallas.domains;

/** A position on the floor plan, in the same pixel space as restaurant-layout.json. */
public record Point(double x, double y) {

    public double distanceTo(Point other) {
        return Math.hypot(x - other.x, y - other.y);
    }
}
