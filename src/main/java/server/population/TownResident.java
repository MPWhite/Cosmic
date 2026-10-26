package server.population;

import client.Client;
import server.maps.AbstractMapObject;
import server.maps.Foothold;
import server.maps.MapObjectType;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.Objects;
import java.util.Random;

/** A lightweight town actor. It never logs in or occupies the real player list. */
public final class TownResident extends AbstractMapObject {
    private final int id;
    private final ResidentProfiles.Profile profile;
    private final MapleMap map;
    private final Point home;
    private final Random random;
    private final int walkingSpeed;
    private int footholdId;
    private int stance = 4;
    private int destinationX;
    private double preciseX;
    private long lastTick;
    private long nextDecision;
    private long pauseUntil;
    private boolean walking;
    private Point following;

    public TownResident(int id, ResidentProfiles.Profile profile, MapleMap map, Point spawn) {
        if (id <= 0) {
            throw new IllegalArgumentException("Resident network IDs must be positive");
        }
        this.id = id;
        this.profile = Objects.requireNonNull(profile);
        this.map = Objects.requireNonNull(map);
        random = new Random(id);
        walkingSpeed = 55 + random.nextInt(31);
        Point ground = new Point(Objects.requireNonNull(spawn));
        Foothold foothold = map.getFootholds().findBelow(new Point(ground.x, ground.y - 8));
        if (foothold == null || foothold.isWall()) {
            throw new IllegalArgumentException("Resident spawn must have ground below it: " + spawn);
        }
        ground.y = footingAt(foothold, ground.x);
        footholdId = foothold.getId();
        home = new Point(ground);
        preciseX = ground.x;
        destinationX = ground.x;
        setPosition(ground);
    }

    public int getId() {
        return id;
    }

    public String getName() {
        return profile.name();
    }

    public ResidentProfiles.Profile getProfile() {
        return profile;
    }

    public synchronized int getStance() {
        return stance;
    }

    public synchronized int getFootholdId() {
        return footholdId;
    }

    @Override
    public synchronized Point getPosition() {
        return super.getPosition();
    }

    @Override
    public MapObjectType getType() {
        return MapObjectType.TOWN_RESIDENT;
    }

    @Override
    public synchronized void sendSpawnData(Client client) {
        client.sendPacket(ResidentPackets.spawn(this));
    }

    @Override
    public void sendDestroyData(Client client) {
        client.sendPacket(ResidentPackets.remove(id));
    }

    /** Update the companion target without granting it player or combat semantics. */
    public synchronized void setFollowTarget(Point target) {
        following = new Point(Objects.requireNonNull(target));
    }

    public synchronized void clearFollowTarget() {
        following = null;
        nextDecision = 0;
        stop(100);
    }

    public synchronized void faceToward(Point target) {
        int direction = target.x < getPosition().x ? 1 : 0;
        stance = 4 | direction;
        walking = false;
        pauseUntil = System.currentTimeMillis() + 1800;
        broadcastMovement(new Point(), 100);
    }

    public synchronized void say(String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        stop(100);
        pauseUntil = System.currentTimeMillis() + 4000;
        map.broadcastMessage(ResidentPackets.chat(id, message.length() > 240 ? message.substring(0, 240) : message));
    }

    /** Called by the shared population scheduler; no task or thread per resident. */
    public synchronized void tick(long nowMillis) {
        if (lastTick == 0) {
            lastTick = nowMillis;
            nextDecision = nowMillis + 1000 + random.nextInt(6000);
            return;
        }
        int elapsed = (int) Math.max(0, Math.min(150, nowMillis - lastTick));
        lastTick = nowMillis;
        if (elapsed == 0 || nowMillis < pauseUntil) {
            return;
        }

        Point current = getPosition();
        if (following != null) {
            // This milestone can walk along connected ground, not jump or climb.
            if (Math.abs(following.y - current.y) > 65 || Math.abs(following.x - current.x) > 600
                    || Math.abs(following.x - current.x) < 70) {
                stop(elapsed);
                return;
            }
            destinationX = following.x + (following.x > current.x ? -55 : 55);
            walking = true;
        } else if (!walking) {
            if (nowMillis < nextDecision) {
                return;
            }
            destinationX = home.x - 200 + random.nextInt(401);
            if (Math.abs(destinationX - current.x) < 25) {
                nextDecision = nowMillis + 2000 + random.nextInt(7000);
                return;
            }
            walking = true;
        }

        int direction = Integer.compare(destinationX, current.x);
        double distance = walkingSpeed * elapsed / 1000.0;
        preciseX += direction * Math.min(distance, Math.abs(destinationX - preciseX));
        int newX = (int) Math.round(preciseX);
        int oldStance = stance;
        stance = direction < 0 ? 3 : 2;
        if (newX == current.x) {
            if (oldStance != stance) {
                broadcastMovement(new Point(), elapsed);
            }
            return;
        }

        // Probe each pixel so short steps cannot skip a gap, wall, or platform edge.
        Point destination = new Point(current);
        boolean blocked = false;
        for (int x = current.x + direction; direction > 0 ? x <= newX : x >= newX; x += direction) {
            Foothold foothold = map.getFootholds().findBelow(new Point(x, destination.y - 5));
            if (foothold == null || foothold.isWall()) {
                blocked = true;
                break;
            }
            int y = footingAt(foothold, x);
            if (Math.abs(y - destination.y) > 5
                    || map.getFootholds().findWall(new Point(Math.min(destination.x, x), destination.y - 3),
                    new Point(Math.max(destination.x, x), destination.y - 3)) != null) {
                blocked = true;
                break;
            }
            destination.setLocation(x, y);
            footholdId = foothold.getId();
        }
        setPosition(destination);
        if (blocked || destination.x == destinationX) {
            preciseX = destination.x;
            walking = false;
            stance = 4 | (direction < 0 ? 1 : 0);
            nextDecision = nowMillis + 2000 + random.nextInt(9000);
            if (following != null && blocked) {
                pauseUntil = nowMillis + 1000;
            }
        }
        Point velocity = new Point((destination.x - current.x) * 1000 / elapsed,
                (destination.y - current.y) * 1000 / elapsed);
        broadcastMovement(velocity, elapsed);
    }

    private void stop(int duration) {
        if (walking || stance < 4) {
            walking = false;
            stance = 4 | (stance & 1);
            preciseX = getPosition().x;
            broadcastMovement(new Point(), duration);
        }
    }

    private void broadcastMovement(Point velocity, int duration) {
        map.broadcastMessage(ResidentPackets.move(id, getPosition(), velocity, footholdId, stance, duration));
    }

    static int footingAt(Foothold foothold, int x) {
        if (foothold.isWall()) {
            throw new IllegalArgumentException("Cannot walk on a wall");
        }
        return foothold.getY1() + (int) Math.round((double) (x - foothold.getX1())
                * (foothold.getY2() - foothold.getY1()) / (foothold.getX2() - foothold.getX1()));
    }
}
