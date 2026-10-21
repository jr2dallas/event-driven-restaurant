package fr.jr2dallas.domains;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;

/**
 * Floor geometry shared with the front (same restaurant-layout.json):
 * chairs, the table each chair belongs to, where a waiter stands to serve a chair,
 * the kitchen pass and the waiter waiting posts.
 *
 * The backend never animates anything — it only uses these positions to estimate
 * walking distances when choosing which waiter takes a job.
 */
public class FloorLayout {

    // Same offset as the front (resolveWaiterServicePosition): waiter stands behind the chair
    private static final double SERVICE_OFFSET = 40;

    private final List<String>        seatIds = new ArrayList<>();
    private final Map<String, Point>  chairs  = new HashMap<>();
    private final Map<String, String> seatTable = new HashMap<>();
    private final Map<String, Point>  tables  = new LinkedHashMap<>();
    private final Map<String, Point>  servicePositions = new HashMap<>();
    private final Map<String, Integer> tableSector = new HashMap<>();
    private final Point       pass;
    private final List<Point> posts;

    public FloorLayout(JsonNode layout, Point pass, List<Point> posts) {
        this.pass  = Objects.requireNonNull(pass, "kitchen pass position is required");
        this.posts = posts == null || posts.isEmpty() ? List.of(pass) : List.copyOf(posts);

        JsonNode chairNodes = layout.get("chair");
        if (chairNodes == null || !chairNodes.isArray())
            throw new IllegalArgumentException("'chair' key not found in restaurant-layout.json");

        JsonNode tableNodes = layout.get("table");
        if (tableNodes != null && tableNodes.isArray()) {
            for (JsonNode t : tableNodes)
                tables.put(t.get("id").asText(), new Point(t.get("x").asDouble(), t.get("y").asDouble()));
        }

        for (JsonNode c : chairNodes) {
            String id = c.get("id").asText();
            Point chair = new Point(c.get("x").asDouble(), c.get("y").asDouble());
            seatIds.add(id);
            chairs.put(id, chair);

            String tableId = nearestTable(chair);
            // A chair with no table becomes its own "table" so batching still works
            seatTable.put(id, tableId != null ? tableId : id);
            if (tableId == null) tables.putIfAbsent(id, chair);
            servicePositions.put(id, servicePosition(chair, tableId == null ? null : tables.get(tableId)));
        }

        for (Map.Entry<String, Point> t : tables.entrySet())
            tableSector.put(t.getKey(), nearestPostIndex(t.getValue()));
    }

    // ── Queries ───────────────────────────────────────────────

    public List<String> seatIds()                 { return Collections.unmodifiableList(seatIds); }
    public Point        pass()                    { return pass; }
    public List<Point>  posts()                   { return posts; }
    public String       tableOf(String seatId)    { return seatTable.get(seatId); }

    /** Where a waiter stands to serve this seat (falls back to the pass for unknown seats). */
    public Point servicePosition(String seatId) {
        return servicePositions.getOrDefault(seatId, pass);
    }

    /** Index of the waiter post closest to this table — the table's "sector". */
    public int sectorOf(String tableId) {
        return tableSector.getOrDefault(tableId, 0);
    }

    public Point post(int index) {
        return posts.get(Math.floorMod(index, posts.size()));
    }

    // ── Helpers ───────────────────────────────────────────────

    private String nearestTable(Point chair) {
        String best = null;
        double bestDist = Double.MAX_VALUE;
        for (Map.Entry<String, Point> t : tables.entrySet()) {
            double d = chair.distanceTo(t.getValue());
            if (d < bestDist) { bestDist = d; best = t.getKey(); }
        }
        return best;
    }

    private int nearestPostIndex(Point p) {
        int best = 0;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < posts.size(); i++) {
            double d = p.distanceTo(posts.get(i));
            if (d < bestDist) { bestDist = d; best = i; }
        }
        return best;
    }

    private static Point servicePosition(Point chair, Point table) {
        if (table == null) return chair;
        double dx = chair.x() - table.x();
        double dy = chair.y() - table.y();
        double len = Math.hypot(dx, dy);
        if (len == 0) return chair;
        return new Point(chair.x() + dx / len * SERVICE_OFFSET, chair.y() + dy / len * SERVICE_OFFSET);
    }
}
