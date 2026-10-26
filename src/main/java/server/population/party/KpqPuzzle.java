package server.population.party;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A player's combination notebook. It never receives the event's secret answer. */
public final class KpqPuzzle {
    private final int stage;
    private final List<Rectangle> areas;
    private final List<List<Integer>> combinations;
    private final Set<List<Integer>> tried = new HashSet<>();
    private List<Integer> current;

    public KpqPuzzle(int stage) {
        this.stage = stage;
        areas = switch (stage) {
            case 2 -> List.of(new Rectangle(-755, -132, 4, 218), new Rectangle(-721, -340, 4, 166),
                    new Rectangle(-586, -326, 4, 150), new Rectangle(-483, -181, 4, 222));
            case 3 -> List.of(new Rectangle(608, -180, 140, 50), new Rectangle(791, -117, 140, 45),
                    new Rectangle(958, -180, 140, 50), new Rectangle(876, -238, 140, 45),
                    new Rectangle(702, -238, 140, 45));
            case 4 -> List.of(new Rectangle(910, -236, 35, 5), new Rectangle(877, -184, 35, 5),
                    new Rectangle(946, -184, 35, 5), new Rectangle(845, -132, 35, 5),
                    new Rectangle(910, -132, 35, 5), new Rectangle(981, -132, 35, 5));
            default -> throw new IllegalArgumentException("KPQ puzzle stage must be 2–4");
        };
        var all = new ArrayList<List<Integer>>();
        for (int a = 0; a < areas.size(); a++) for (int b = a + 1; b < areas.size(); b++)
            for (int c = b + 1; c < areas.size(); c++) all.add(List.of(a, b, c));
        combinations = List.copyOf(all);
    }

    public int stage() { return stage; }
    public int attempts() { return tried.size(); }
    public int slot(Point position) {
        for (int i = 0; i < areas.size(); i++) if (areas.get(i).contains(position)) return i;
        return -1;
    }

    public Point position(int slot) {
        Rectangle r = areas.get(slot);
        return new Point(r.x + r.width / 2, r.y + r.height / 2);
    }

    /** Preserve an occupied human slot whenever an untried combination includes it. */
    public List<Integer> choose(int humanSlot) {
        if (current != null && !tried.contains(current) && (humanSlot < 0 || current.contains(humanSlot))) return current;
        current = combinations.stream().filter(c -> !tried.contains(c))
                .filter(c -> humanSlot < 0 || c.contains(humanSlot)).findFirst()
                .orElseGet(() -> combinations.stream().filter(c -> !tried.contains(c)).findFirst().orElse(null));
        return current == null ? List.of() : current;
    }

    /** Only a real WRONG check with exactly three distinct occupied slots eliminates a combination. */
    public void observeWrong(List<Point> positions) {
        List<Integer> occupied = positions.stream().map(this::slot).filter(i -> i >= 0).sorted().toList();
        if (occupied.size() == 3 && new HashSet<>(occupied).size() == 3) tried.add(occupied);
    }

    public String label(List<Integer> combo) {
        return combo.stream().map(i -> Integer.toString(i + 1)).reduce("", String::concat);
    }
}
