package server.population;

import server.population.navigation.CompanionNavigation;

import java.awt.Point;
import java.util.Comparator;
import java.util.List;

/** Deterministic ground positions around real entry points, without constructing hidden maps. */
final class ResidentSpawns {
    private ResidentSpawns() { }

    static Point choose(CompanionNavigation navigation, List<Point> anchors, int identity) {
        List<CompanionNavigation.Surface> ground = navigation.surfaces().stream()
                .filter(s -> !s.wall() && Math.abs(s.x2() - s.x1()) >= 40).toList();
        if (ground.isEmpty()) throw new IllegalArgumentException("No supported resident ground");
        var fallback = ground.get(Math.floorMod(identity, ground.size()));
        Point anchor = anchors.isEmpty() ? new Point((fallback.x1() + fallback.x2()) / 2,
                (fallback.y1() + fallback.y2()) / 2) : anchors.get(Math.floorMod(identity, anchors.size()));
        Point desired = new Point(anchor.x + Math.floorMod(identity * 104729, 1101) - 550, anchor.y);
        var projected = navigation.project(desired, 160);
        if (projected.isPresent()) return projected.get().position();
        var surface = ground.stream().min(Comparator.comparingDouble(s -> {
            double x = Math.max(Math.min(s.x1(), s.x2()) + 16,
                    Math.min(Math.max(s.x1(), s.x2()) - 16, desired.x));
            double y = s.y1() + (x - s.x1()) * (s.y2() - s.y1()) / (double) (s.x2() - s.x1());
            return Math.abs(x - desired.x) + Math.abs(y - desired.y) * 3;
        })).orElseThrow();
        int left = Math.min(surface.x1(), surface.x2()) + 12;
        int right = Math.max(surface.x1(), surface.x2()) - 12;
        int x = Math.max(left, Math.min(right, desired.x));
        int y = (int) Math.round(surface.y1() + (x - surface.x1())
                * (surface.y2() - surface.y1()) / (double) (surface.x2() - surface.x1()));
        return navigation.project(new Point(x, y), 8).orElseThrow().position();
    }
}
