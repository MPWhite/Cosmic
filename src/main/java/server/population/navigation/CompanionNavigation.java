package server.population.navigation;

import provider.Data;
import provider.DataProvider;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Immutable platform navigation shared by companions visiting the same public map. */
public final class CompanionNavigation {
    // Browser physics uses an 8 ms step, gravity .14 px/step², and an unbuffed
    // jump impulse 4.5 px/step. Keep a small margin below that 72 px jump apex.
    public static final double GRAVITY = 2187.5;
    public static final double JUMP_SPEED = 550;
    public static final double MAX_HORIZONTAL_SPEED = 150;
    private static final double WALK_SPEED = 140;
    private static final double CLIMB_SPEED = 110;
    private static final int MAX_DROP = 590;
    private static final int MAX_NODES = 4000;
    private static final int MAX_EXPANSIONS = 6000;
    private static final int BUCKET_WIDTH = 80;
    private static final Map<Integer, CompanionNavigation> CACHE = new LinkedHashMap<>(16, .75f, true);

    public enum Kind { WALK, JUMP, DROP, CLIMB }

    public record Surface(int id, int x1, int y1, int x2, int y2, boolean dropThrough, int previous, int next) {
        public Surface(int id, int x1, int y1, int x2, int y2) {
            this(id, x1, y1, x2, y2, true, -1, -1);
        }

        public Surface(int id, int x1, int y1, int x2, int y2, boolean dropThrough) {
            this(id, x1, y1, x2, y2, dropThrough, -1, -1);
        }

        public Surface {
            if (id <= 0) {
                throw new IllegalArgumentException("Foothold IDs must be positive");
            }
        }

        public boolean wall() { return x1 == x2; }
        int left() { return Math.min(x1, x2); }
        int right() { return Math.max(x1, x2); }
        double height(double x) { return y1 + (x - x1) * (y2 - y1) / (double) (x2 - x1); }
        boolean contains(double x) { return !wall() && x >= left() && x <= right(); }
    }

    public record Climb(int id, int x, int top, int bottom, boolean rope) {
        public Climb {
            if (bottom <= top) {
                throw new IllegalArgumentException("A ladder or rope needs positive height");
            }
        }
    }

    public record Ground(Point position, int footholdId) {
        public Ground { position = new Point(position); }
        @Override public Point position() { return new Point(position); }
    }

    public record Leg(Kind kind, Point from, Point to, int fromFoothold, int toFoothold,
                      int durationMillis, boolean rope, double verticalVelocity, double gravity) {
        public Leg {
            Objects.requireNonNull(kind);
            from = new Point(from);
            to = new Point(to);
            if (durationMillis < 1) {
                throw new IllegalArgumentException("Navigation legs need a positive duration");
            }
        }

        @Override public Point from() { return new Point(from); }
        @Override public Point to() { return new Point(to); }

        public Point pointAt(long elapsedMillis) {
            if (elapsedMillis <= 0) {
                return from();
            }
            if (elapsedMillis >= durationMillis) {
                return to();
            }
            double fraction = elapsedMillis / (double) durationMillis;
            double x = from.x + fraction * (to.x - from.x);
            double seconds = elapsedMillis / 1000.0;
            double y = kind == Kind.JUMP || kind == Kind.DROP
                    ? from.y + verticalVelocity * seconds + .5 * gravity * seconds * seconds
                    : from.y + fraction * (to.y - from.y);
            return new Point((int) Math.round(x), (int) Math.round(y));
        }
    }

    private record Node(Point position, int foothold, int climb) {
        Node { position = new Point(position); }
    }
    private record Edge(int to, Leg leg) {}
    private record QueueEntry(int node, double cost) {}
    private record NodeKey(int x, int y, int foothold, int climb) {}

    private final List<Surface> surfaces;
    private final List<Climb> climbs;
    private final Map<Integer, Surface> byId;
    private final Map<Integer, List<Surface>> geometryBuckets;
    private final List<Node> nodes;
    private final List<List<Edge>> edges;
    private final Map<Integer, List<Integer>> surfaceNodes;

    private static final class ProviderHolder {
        static final DataProvider MAPS = DataProviderFactory.getDataProvider(WZFiles.MAP);
    }

    public static CompanionNavigation forMap(MapleMap map) {
        return forMapId(Objects.requireNonNull(map).getId());
    }

    public static CompanionNavigation forMapId(int mapId) {
        synchronized (CACHE) {
            CompanionNavigation existing = CACHE.get(mapId);
            if (existing != null) {
                return existing;
            }
            CompanionNavigation navigation = load(mapId);
            CACHE.put(mapId, navigation);
            if (CACHE.size() > 64) {
                CACHE.remove(CACHE.keySet().iterator().next());
            }
            return navigation;
        }
    }

    public static CompanionNavigation of(List<Surface> surfaces, List<Climb> climbs) {
        return new CompanionNavigation(surfaces, climbs);
    }

    private CompanionNavigation(List<Surface> surfaces, List<Climb> climbs) {
        if (surfaces.size() > 3000 || climbs.size() > 256) {
            throw new IllegalArgumentException("Map geometry exceeds companion navigation limits");
        }
        this.surfaces = List.copyOf(surfaces);
        this.climbs = List.copyOf(climbs);
        Map<Integer, Surface> lookup = new HashMap<>();
        Map<Integer, List<Surface>> buckets = new HashMap<>();
        for (Surface surface : surfaces) {
            if (lookup.put(surface.id, surface) != null) {
                throw new IllegalArgumentException("Duplicate foothold " + surface.id);
            }
            for (int bucket = bucket(surface.left()); bucket <= bucket(surface.right()); bucket++) {
                buckets.computeIfAbsent(bucket, ignored -> new ArrayList<>()).add(surface);
            }
        }
        byId = Map.copyOf(lookup);
        buckets.replaceAll((key, value) -> List.copyOf(value));
        geometryBuckets = Map.copyOf(buckets);
        Builder builder = new Builder();
        builder.build();
        nodes = List.copyOf(builder.nodes);
        edges = builder.edges.stream().map(List::copyOf).toList();
        Map<Integer, List<Integer>> groups = new HashMap<>();
        for (int index = 0; index < nodes.size(); index++) {
            if (nodes.get(index).foothold != 0) {
                groups.computeIfAbsent(nodes.get(index).foothold, ignored -> new ArrayList<>()).add(index);
            }
        }
        groups.replaceAll((key, value) -> List.copyOf(value));
        surfaceNodes = Map.copyOf(groups);
    }

    /** Find supporting ground directly below a point; never change its x coordinate. */
    public Optional<Ground> groundBelow(Point point, int maxDrop) {
        Objects.requireNonNull(point);
        Surface best = null;
        double distance = Double.POSITIVE_INFINITY;
        for (Surface surface : geometryBuckets.getOrDefault(bucket(point.x), List.of())) {
            if (!surface.contains(point.x)) {
                continue;
            }
            double delta = surface.height(point.x) - point.y;
            if (delta >= -.75 && delta <= maxDrop && delta < distance) {
                best = surface;
                distance = delta;
            }
        }
        return best == null ? Optional.empty() : Optional.of(ground(best, point.x));
    }

    /** Closest ground at the same x, with a strict vertical search radius. */
    public Optional<Ground> project(Point point, int maxVerticalDistance) {
        Objects.requireNonNull(point);
        Surface best = null;
        double distance = Double.POSITIVE_INFINITY;
        for (Surface surface : geometryBuckets.getOrDefault(bucket(point.x), List.of())) {
            if (!surface.contains(point.x)) {
                continue;
            }
            double delta = Math.abs(surface.height(point.x) - point.y);
            if (delta <= maxVerticalDistance && delta < distance) {
                best = surface;
                distance = delta;
            }
        }
        return best == null ? Optional.empty() : Optional.of(ground(best, point.x));
    }

    /** No path is distinct from already being there. Planning never moves an actor. */
    public Optional<List<Leg>> plan(Point start, Point target) {
        Optional<Ground> startGround = project(start, 12);
        Optional<Ground> targetGround = project(target, 160);
        if (startGround.isEmpty() || targetGround.isEmpty()) {
            return Optional.empty();
        }
        Ground from = startGround.get();
        Ground to = targetGround.get();
        if (from.position.equals(to.position)) {
            return Optional.of(List.of());
        }
        if (from.footholdId == to.footholdId && clearWalk(from.position, to.position)) {
            return Optional.of(List.of(linear(Kind.WALK, from.position, to.position,
                    from.footholdId, to.footholdId, false)));
        }
        double[] distance = new double[nodes.size()];
        Arrays.fill(distance, Double.POSITIVE_INFINITY);
        int[] previous = new int[nodes.size()];
        Arrays.fill(previous, -1);
        Leg[] incoming = new Leg[nodes.size()];
        PriorityQueue<QueueEntry> queue = new PriorityQueue<>(Comparator.comparingDouble(QueueEntry::cost));
        for (int index : surfaceNodes.getOrDefault(from.footholdId, List.of())) {
            Node node = nodes.get(index);
            if (clearWalk(from.position, node.position)) {
                Leg leg = linear(Kind.WALK, from.position, node.position, from.footholdId, node.foothold, false);
                distance[index] = leg.durationMillis;
                incoming[index] = leg;
                queue.add(new QueueEntry(index, distance[index]));
            }
        }
        int bestEnd = -1;
        Leg lastLeg = null;
        double bestCost = Double.POSITIVE_INFINITY;
        int expanded = 0;
        while (!queue.isEmpty() && expanded < MAX_EXPANSIONS) {
            QueueEntry current = queue.poll();
            if (current.cost != distance[current.node]) {
                continue;
            }
            if (current.cost >= bestCost) {
                break;
            }
            expanded++;
            Node node = nodes.get(current.node);
            if (node.foothold == to.footholdId && clearWalk(node.position, to.position)) {
                Leg finish = linear(Kind.WALK, node.position, to.position, node.foothold, to.footholdId, false);
                if (current.cost + finish.durationMillis < bestCost) {
                    bestCost = current.cost + finish.durationMillis;
                    bestEnd = current.node;
                    lastLeg = finish;
                }
            }
            for (Edge edge : edges.get(current.node)) {
                double candidate = current.cost + cost(edge.leg);
                if (candidate < distance[edge.to]) {
                    distance[edge.to] = candidate;
                    previous[edge.to] = current.node;
                    incoming[edge.to] = edge.leg;
                    queue.add(new QueueEntry(edge.to, candidate));
                }
            }
        }
        if (bestEnd < 0) {
            return Optional.empty();
        }
        List<Leg> reversed = new ArrayList<>();
        for (int index = bestEnd; index >= 0; index = previous[index]) {
            reversed.add(incoming[index]);
        }
        Collections.reverse(reversed);
        reversed.add(lastLeg);
        return Optional.of(compact(reversed));
    }

    public int nodeCount() { return nodes.size(); }
    public List<Surface> surfaces() { return surfaces; }
    public List<Climb> climbs() { return climbs; }

    private final class Builder {
        final List<Node> nodes = new ArrayList<>();
        final List<List<Edge>> edges = new ArrayList<>();
        final Map<NodeKey, Integer> indexes = new HashMap<>();
        final Map<Integer, TreeMap<Integer, Integer>> grounded = new HashMap<>();

        void build() {
            for (Surface surface : surfaces) {
                if (surface.wall()) {
                    continue;
                }
                TreeSet<Integer> xs = new TreeSet<>();
                xs.add(surface.left());
                xs.add(surface.right());
                if (surface.right() - surface.left() > 4) {
                    xs.add(surface.left() + 2);
                    xs.add(surface.right() - 2);
                }
                for (int x = surface.left() + 35; x < surface.right(); x += 35) {
                    xs.add(x);
                }
                for (Climb climb : climbs) {
                    if (surface.contains(climb.x)) {
                        xs.add(climb.x);
                    }
                }
                for (int x : xs) {
                    groundNode(surface, x);
                }
            }
            for (Climb climb : climbs) {
                addClimb(climb);
            }
            for (TreeMap<Integer, Integer> points : grounded.values()) {
                Integer previous = null;
                for (int index : points.values()) {
                    if (previous != null) {
                        connectWalk(previous, index);
                    }
                    previous = index;
                }
            }
            // Foothold fragments meet at shared vertices. Merely being nearby
            // is insufficient: gaps and vertical steps require an airborne leg.
            Map<Point, List<Integer>> coincident = new HashMap<>();
            for (int i = 0; i < nodes.size(); i++) {
                if (nodes.get(i).foothold != 0) {
                    coincident.computeIfAbsent(nodes.get(i).position, ignored -> new ArrayList<>()).add(i);
                }
            }
            for (List<Integer> group : coincident.values()) {
                for (int a : group) {
                    for (int b : group) {
                        if (a != b) {
                            connect(a, b, linear(Kind.WALK, nodes.get(a).position, nodes.get(b).position,
                                    nodes.get(a).foothold, nodes.get(b).foothold, false));
                        }
                    }
                }
            }
            int[] components = walkComponents();
            List<Integer> sorted = new ArrayList<>();
            for (int i = 0; i < nodes.size(); i++) {
                sorted.add(i);
            }
            sorted.sort(Comparator.comparingInt(i -> nodes.get(i).position.x));
            for (int source : sorted) {
                Node from = nodes.get(source);
                for (int destination : sorted) {
                    Node to = nodes.get(destination);
                    int dx = to.position.x - from.position.x;
                    if (dx < -160) {
                        continue;
                    }
                    if (dx > 160) {
                        break;
                    }
                    if (source == destination || (from.foothold != 0 && components[source] == components[destination])) {
                        continue;
                    }
                    int dy = to.position.y - from.position.y;
                    if (dy < -70 || dy > MAX_DROP || (dy == 0 && dx == 0)) {
                        continue;
                    }
                    if (dy > 5 && (from.foothold == 0 || byId.get(from.foothold).dropThrough)) {
                        airborne(Kind.DROP, source, destination);
                    }
                    airborne(Kind.JUMP, source, destination);
                }
            }
        }

        int groundNode(Surface surface, int x) {
            Ground point = ground(surface, x);
            int index = node(new Node(point.position, surface.id, 0));
            grounded.computeIfAbsent(surface.id, ignored -> new TreeMap<>()).put(x, index);
            return index;
        }

        int node(Node node) {
            NodeKey key = new NodeKey(node.position.x, node.position.y, node.foothold, node.climb);
            Integer found = indexes.get(key);
            if (found != null) {
                return found;
            }
            if (nodes.size() >= MAX_NODES) {
                throw new IllegalArgumentException("Map exceeds companion navigation node limit");
            }
            int index = nodes.size();
            indexes.put(key, index);
            nodes.add(node);
            edges.add(new ArrayList<>());
            return index;
        }

        void addClimb(Climb climb) {
            TreeMap<Integer, Integer> stops = new TreeMap<>();
            for (Surface surface : surfaces) {
                if (!surface.contains(climb.x)) {
                    continue;
                }
                int y = (int) Math.round(surface.height(climb.x));
                if (y >= climb.top - 6 && y <= climb.bottom + 4) {
                    stops.put(y, groundNode(surface, climb.x));
                }
            }
            if (stops.isEmpty() || stops.firstKey() > climb.top + 6) {
                stops.put(climb.top, node(new Node(new Point(climb.x, climb.top), 0, climb.id)));
            }
            if (stops.lastKey() < climb.bottom - 4) {
                stops.put(climb.bottom, node(new Node(new Point(climb.x, climb.bottom), 0, climb.id)));
            }
            Integer previous = null;
            for (int stop : stops.values()) {
                if (previous != null) {
                    Node a = nodes.get(previous);
                    Node b = nodes.get(stop);
                    connect(previous, stop, linear(Kind.CLIMB, a.position, b.position, a.foothold, b.foothold, climb.rope));
                    connect(stop, previous, linear(Kind.CLIMB, b.position, a.position, b.foothold, a.foothold, climb.rope));
                }
                previous = stop;
            }
        }

        void connectWalk(int a, int b) {
            Node from = nodes.get(a);
            Node to = nodes.get(b);
            if (clearWalk(from.position, to.position)) {
                connect(a, b, linear(Kind.WALK, from.position, to.position, from.foothold, to.foothold, false));
                connect(b, a, linear(Kind.WALK, to.position, from.position, to.foothold, from.foothold, false));
            }
        }

        int[] walkComponents() {
            int[] components = new int[nodes.size()];
            Arrays.fill(components, -1);
            for (int index = 0; index < nodes.size(); index++) {
                if (components[index] >= 0) {
                    continue;
                }
                List<Integer> pending = new ArrayList<>();
                pending.add(index);
                components[index] = index;
                for (int position = 0; position < pending.size(); position++) {
                    int current = pending.get(position);
                    for (Edge edge : edges.get(current)) {
                        if (edge.leg.kind == Kind.WALK && components[edge.to] < 0) {
                            components[edge.to] = index;
                            pending.add(edge.to);
                        }
                    }
                }
            }
            return components;
        }

        void airborne(Kind kind, int a, int b) {
            Node from = nodes.get(a);
            Node to = nodes.get(b);
            double dy = to.position.y - from.position.y;
            // Jumping from a rope has a smaller impulse in the actual client.
            double impulse = from.foothold == 0 ? JUMP_SPEED / 1.5 : JUMP_SPEED;
            double discriminant = impulse * impulse + 2 * GRAVITY * dy;
            if (kind == Kind.JUMP && discriminant < 0) {
                return;
            }
            double seconds = kind == Kind.DROP ? Math.sqrt(2 * dy / GRAVITY)
                    : (impulse + Math.sqrt(discriminant)) / GRAVITY;
            if (!Double.isFinite(seconds) || seconds <= .01
                    || Math.abs(to.position.x - from.position.x) / seconds > MAX_HORIZONTAL_SPEED) {
                return;
            }
            int duration = (int) Math.ceil(seconds * 1000);
            seconds = duration / 1000.0;
            double gravity = kind == Kind.DROP ? 2 * dy / (seconds * seconds) : GRAVITY;
            double velocity = kind == Kind.DROP ? 0 : (dy - .5 * gravity * seconds * seconds) / seconds;
            Leg leg = new Leg(kind, from.position, to.position, from.foothold, to.foothold,
                    duration, false, velocity, gravity);
            if (clearArc(leg)) {
                connect(a, b, leg);
            }
        }

        void connect(int from, int to, Leg leg) {
            edges.get(from).add(new Edge(to, leg));
        }
    }

    private boolean clearWalk(Point from, Point to) {
        if (from.equals(to)) {
            return true;
        }
        for (Surface surface : geometryBetween(from.x, to.x)) {
            if (surface.wall() && wallBlocks(surface, from.x, from.y, to.x, to.y)
                    && (wallRelevant(surface, from.x, from.y) || wallRelevant(surface, to.x, to.y))) {
                return false;
            }
        }
        return true;
    }

    private boolean clearArc(Leg leg) {
        List<Surface> nearby = geometryBetween(leg.from.x, leg.to.x);
        double previousX = leg.from.x;
        double previousY = leg.from.y;
        // Test swept segments, not just sample points, so thin footholds and
        // walls cannot be skipped between animation updates.
        for (int elapsed = 8; ; elapsed += 8) {
            int clamped = Math.min(elapsed, leg.durationMillis);
            double fraction = clamped / (double) leg.durationMillis;
            double x = leg.from.x + fraction * (leg.to.x - leg.from.x);
            double seconds = clamped / 1000.0;
            double y = clamped == leg.durationMillis ? leg.to.y
                    : leg.from.y + leg.verticalVelocity * seconds + .5 * leg.gravity * seconds * seconds;
            for (Surface surface : nearby) {
                if (surface.wall()) {
                    if (wallBlocks(surface, previousX, previousY, x, y)
                            && (wallRelevant(surface, previousX, previousY) || wallRelevant(surface, x, y))) {
                        return false;
                    }
                    continue;
                }
                if (leg.kind == Kind.DROP && surface.id == leg.fromFoothold) {
                    continue; // deliberate down+jump through the starting platform
                }
                double before = previousY - surface.height(previousX);
                double after = y - surface.height(x);
                if (after <= before || before > .75 || after < -.001) {
                    continue; // platforms are one-way; upward passage is allowed
                }
                double crossing = Math.max(0, Math.min(1, -before / (after - before)));
                double hitX = previousX + crossing * (x - previousX);
                if (surface.contains(hitX)) {
                    double hitY = surface.height(hitX);
                    boolean finalLanding = clamped == leg.durationMillis
                            && Math.hypot(hitX - leg.to.x, hitY - leg.to.y) <= 2;
                    if (!finalLanding) {
                        return false;
                    }
                }
            }
            if (clamped == leg.durationMillis) {
                return true;
            }
            previousX = x;
            previousY = y;
        }
    }

    private static boolean wallBlocks(Surface wall, double x0, double y0, double x1, double y1) {
        if (x0 == x1 || wall.x1 < Math.min(x0, x1) || wall.x1 > Math.max(x0, x1)) {
            return false;
        }
        double fraction = (wall.x1 - x0) / (x1 - x0);
        double feet = y0 + fraction * (y1 - y0);
        // Match the client's 50-pixel vertical wall collision range.
        return feet - 1 > Math.min(wall.y1, wall.y2) && feet - 50 < Math.max(wall.y1, wall.y2);
    }

    private boolean wallRelevant(Surface wall, double x, double feet) {
        if (wall.previous < 0 && wall.next < 0) {
            return true; // standalone synthetic geometry has no WZ linkage metadata
        }
        Surface support = null;
        double nearest = Double.POSITIVE_INFINITY;
        for (Surface candidate : geometryBuckets.getOrDefault(bucket((int) Math.floor(x)), List.of())) {
            if (candidate.contains(x)) {
                double delta = candidate.height(x) - feet;
                if (delta >= -.75 && delta < nearest) {
                    support = candidate;
                    nearest = delta;
                }
            }
        }
        if (support == null) {
            return false;
        }
        // The browser's get_wall checks only the active foothold's neighboring
        // links (at most two hops). Other layers contain decorative walls which
        // overlap a street on screen but do not obstruct its players.
        Surface previous = byId.get(support.previous);
        Surface next = byId.get(support.next);
        return support.previous == wall.id || support.next == wall.id
                || previous != null && previous.previous == wall.id
                || next != null && next.next == wall.id;
    }

    private List<Surface> geometryBetween(int fromX, int toX) {
        Set<Surface> found = new HashSet<>();
        for (int bucket = bucket(Math.min(fromX, toX)); bucket <= bucket(Math.max(fromX, toX)); bucket++) {
            found.addAll(geometryBuckets.getOrDefault(bucket, List.of()));
        }
        return List.copyOf(found);
    }

    private static int bucket(int x) { return Math.floorDiv(x, BUCKET_WIDTH); }
    private static Ground ground(Surface surface, int x) {
        return new Ground(new Point(x, (int) Math.round(surface.height(x))), surface.id);
    }

    private static Leg linear(Kind kind, Point from, Point to, int fromFoothold, int toFoothold, boolean rope) {
        double speed = kind == Kind.CLIMB ? CLIMB_SPEED : WALK_SPEED;
        int duration = Math.max(1, (int) Math.ceil(from.distance(to) * 1000 / speed));
        return new Leg(kind, from, to, fromFoothold, toFoothold, duration, rope, 0, 0);
    }

    private static double cost(Leg leg) {
        return leg.durationMillis + (leg.kind == Kind.JUMP || leg.kind == Kind.DROP ? 100 : 0);
    }

    private static List<Leg> compact(List<Leg> legs) {
        List<Leg> result = new ArrayList<>();
        for (Leg leg : legs) {
            if (leg.from.equals(leg.to)) {
                continue;
            }
            if (!result.isEmpty()) {
                Leg before = result.getLast();
                if (before.kind == Kind.WALK && leg.kind == Kind.WALK && before.to.equals(leg.from)
                        && before.fromFoothold == leg.fromFoothold && leg.fromFoothold == leg.toFoothold) {
                    result.set(result.size() - 1, linear(Kind.WALK, before.from, leg.to,
                            before.fromFoothold, leg.toFoothold, false));
                    continue;
                }
            }
            result.add(leg);
        }
        return List.copyOf(result);
    }

    private static CompanionNavigation load(int mapId) {
        Set<Integer> links = new HashSet<>();
        Data data;
        int current = mapId;
        while (true) {
            if (!links.add(current) || links.size() > 12) {
                throw new IllegalArgumentException("Cyclic or excessive map geometry links for " + mapId);
            }
            data = ProviderHolder.MAPS.getData(String.format("Map/Map%d/%09d.img", current / 100000000, current));
            if (data == null) {
                throw new IllegalArgumentException("No map geometry for " + current);
            }
            String link = DataTool.getString("info/link", data, "");
            if (link.isBlank()) {
                break;
            }
            current = Integer.parseInt(link);
        }
        List<Surface> surfaces = new ArrayList<>();
        Data footholds = data.getChildByPath("foothold");
        if (footholds != null) {
            for (Data layer : footholds) {
                for (Data group : layer) {
                    for (Data foothold : group) {
                        surfaces.add(new Surface(Integer.parseInt(foothold.getName()),
                                DataTool.getInt("x1", foothold), DataTool.getInt("y1", foothold),
                                DataTool.getInt("x2", foothold), DataTool.getInt("y2", foothold),
                                DataTool.getInt("forbidFallDown", foothold, 0) == 0,
                                DataTool.getInt("prev", foothold, 0), DataTool.getInt("next", foothold, 0)));
                    }
                }
            }
        }
        List<Climb> climbs = new ArrayList<>();
        Data ladders = data.getChildByPath("ladderRope");
        if (ladders != null) {
            for (Data climb : ladders) {
                int top = DataTool.getInt("y1", climb);
                int bottom = DataTool.getInt("y2", climb);
                if (bottom > top) {
                    climbs.add(new Climb(Integer.parseInt(climb.getName()), DataTool.getInt("x", climb),
                            top, bottom, DataTool.getInt("l", climb, 1) == 0));
                }
            }
        }
        return of(surfaces, climbs);
    }
}
