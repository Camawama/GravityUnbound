// Standalone replication of the client-player frame math, kept from the
// 2026-09-22 investigation of the side-face-to-top-face push (see MKWMobility
// docs/BACKLOG.md, round 21). Not part of the mod build.
//
//   JOML=$HOME/.gradle/caches/modules-2/files-2.1/org.joml/joml/1.10.5/*/joml-1.10.5.jar
//   "/c/Program Files/Java/jdk-17.0.1/bin/javac.exe" -cp "$JOML" tools/GuSim.java -d /tmp/gusim
//   java -cp "$JOML;/tmp/gusim" GuSim        # current gate (reproduces the push)
//   java -cp "$JOML;/tmp/gusim" GuSim fix    # capsule-predicate gate (no push)
//
// It transcribes GravityCapabilityImpl / RotationUtil / QuaternionUtil /
// EntityMixin / CapsuleCollider blocks as of that date and will drift from the
// source; treat it as a worked example of the method, not as a test.
import org.joml.Quaternionf;
import org.joml.Vector3f;
import java.util.*;

/**
 * Numerical replication of Gravity Unbound's client-player frame math for a player
 * walking from a side face of a 5x5x5 gravity-core cube onto its top face.
 * Quaternion math uses the real JOML 1.10.5 Quaternionf/Vector3f (float). Vec3 is a
 * double re-implementation of net.minecraft.world.phys.Vec3 (normalize threshold 1e-4,
 * Double.compare equality). Transcribed blocks cite GravityCapabilityImpl.java (GCI),
 * RotationUtil.java (RU), QuaternionUtil.java (QU), EntityMixin.java (EM),
 * CapsuleCollider.java (CC) and vanilla Entity/LivingEntity/LocalPlayer.
 */
public class GuSim {
    // ---------------------------------------------------------------- Vec3
    static final class V {
        final double x, y, z;
        V(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }
        static final V ZERO = new V(0, 0, 0);
        V add(V o) { return new V(x + o.x, y + o.y, z + o.z); }
        V sub(V o) { return new V(x - o.x, y - o.y, z - o.z); }
        V scale(double s) { return new V(x * s, y * s, z * s); }
        double dot(V o) { return x * o.x + y * o.y + z * o.z; }
        V cross(V o) { return new V(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x); }
        double lengthSqr() { return x * x + y * y + z * z; }
        double length() { return Math.sqrt(lengthSqr()); }
        V normalize() { double d = Math.sqrt(x * x + y * y + z * z); return d < 1.0E-4 ? ZERO : new V(x / d, y / d, z / d); }
        Vector3f toVector3f() { return new Vector3f((float) x, (float) y, (float) z); }
        static V of(Vector3f f) { return new V((double) f.x, (double) f.y, (double) f.z); }
        boolean eq(V o) { return Double.compare(x, o.x) == 0 && Double.compare(y, o.y) == 0 && Double.compare(z, o.z) == 0; }
        public String toString() { return String.format(Locale.ROOT, "(%+.7f,%+.7f,%+.7f)", x, y, z); }
    }

    // ---------------------------------------------------------------- Direction
    enum Dir {
        DOWN(0, -1, 0), UP(0, 1, 0), NORTH(0, 0, -1), SOUTH(0, 0, 1), WEST(-1, 0, 0), EAST(1, 0, 0);
        final int nx, ny, nz;
        Dir(int x, int y, int z) { nx = x; ny = y; nz = z; }
        V normal() { return new V(nx, ny, nz); }
        Dir opposite() { switch (this) { case DOWN: return UP; case UP: return DOWN; case NORTH: return SOUTH; case SOUTH: return NORTH; case WEST: return EAST; default: return WEST; } }
        boolean positive() { return this == UP || this == SOUTH || this == EAST; }
        // Direction.getNearest(FFF): start NORTH, f = Float.MIN_VALUE, strictly greater wins (javap verified)
        static Dir getNearest(double x, double y, double z) {
            float fx = (float) x, fy = (float) y, fz = (float) z;
            Dir best = NORTH; float f = Float.MIN_VALUE;
            for (Dir d : values()) { float f1 = fx * d.nx + fy * d.ny + fz * d.nz; if (f1 > f) { f = f1; best = d; } }
            return best;
        }
    }

    // ---------------------------------------------------------------- Mth
    static final float[] SIN = new float[65536];
    static { for (int i = 0; i < SIN.length; i++) SIN[i] = (float) Math.sin((double) i * Math.PI * 2.0D / 65536.0D); }
    static float msin(float v) { return SIN[(int) (v * 10430.378F) & 0xffff]; }
    static float mcos(float v) { return SIN[(int) (v * 10430.378F + 16384.0F) & 0xffff]; }
    static float wrapDegrees(float f) { float g = f % 360.0F; if (g >= 180.0F) g -= 360.0F; if (g < -180.0F) g += 360.0F; return g; }
    static float lerp(float d, float a, float b) { return a + d * (b - a); }
    static float clampF(float v, float lo, float hi) { return v < lo ? lo : Math.min(v, hi); }
    static double clampD(double v, double lo, double hi) { return v < lo ? lo : Math.min(v, hi); }
    static int clampI(int v, int lo, int hi) { return v < lo ? lo : Math.min(v, hi); }
    static boolean mequal(double a, double b) { return Math.abs(b - a) < 9.999999747378752E-6D; }

    // ---------------------------------------------------------------- RotationUtil (RU:451-468; Axis.rotationDegrees = rotation(deg*0.017453292f))
    static final Quaternionf[] WRQ = new Quaternionf[6];
    static Quaternionf rotX(float deg) { return new Quaternionf().rotationX(deg * 0.017453292F); }
    static Quaternionf rotY(float deg) { return new Quaternionf().rotationY(deg * 0.017453292F); }
    static Quaternionf rotZ(float deg) { return new Quaternionf().rotationZ(deg * 0.017453292F); }
    static {
        WRQ[0] = new Quaternionf();
        WRQ[1] = rotZ(-180);
        WRQ[2] = rotX(-90);
        WRQ[3] = rotX(-90); WRQ[3].mul(rotY(-180));
        WRQ[4] = rotX(-90); WRQ[4].mul(rotY(-90));
        WRQ[5] = rotX(-90); WRQ[5].mul(rotY(-270));
    }
    static Quaternionf canonical(Dir d) { return WRQ[d.ordinal()]; }
    static Dir canonicalDirectionOf(Quaternionf f) { for (int i = 0; i < 6; i++) if (WRQ[i] == f || WRQ[i].equals(f)) return Dir.values()[i]; return null; }
    // RU:53-62
    static V w2p(V v, Dir g) {
        double x = v.x, y = v.y, z = v.z;
        switch (g) {
            case DOWN: return new V(x, y, z);
            case UP: return new V(-x, -y, z);
            case NORTH: return new V(x, z, -y);
            case SOUTH: return new V(-x, -z, -y);
            case WEST: return new V(-z, x, -y);
            default: return new V(z, -x, -y);
        }
    }
    // RU:249-258
    static V p2w(V v, Dir g) {
        double x = v.x, y = v.y, z = v.z;
        switch (g) {
            case DOWN: return new V(x, y, z);
            case UP: return new V(-x, -y, z);
            case NORTH: return new V(x, -z, y);
            case SOUTH: return new V(-x, -z, -y);
            case WEST: return new V(y, -z, -x);
            default: return new V(-y, -z, x);
        }
    }
    // QU:100-104
    static V rotate(V v, Quaternionf q) { Vector3f f = v.toVector3f(); f.rotate(q); return V.of(f); }
    // RU:104-126
    static V w2p(V v, Quaternionf frame) { Dir d = canonicalDirectionOf(frame); if (d != null) return w2p(v, d); return rotate(v, frame); }
    static V p2w(V v, Quaternionf frame) { Dir d = canonicalDirectionOf(frame); if (d != null) return p2w(v, d); return rotate(v, new Quaternionf(frame).conjugate()); }
    // RU:421-429
    static V rotToVec(float yaw, float pitch) {
        double radPitch = pitch * 0.017453292;
        double radNegYaw = -yaw * 0.017453292;
        double cosNegYaw = Math.cos(radNegYaw), sinNegYaw = Math.sin(radNegYaw);
        double cosPitch = Math.cos(radPitch), sinPitch = Math.sin(radPitch);
        return new V(sinNegYaw * cosPitch, -sinPitch, cosNegYaw * cosPitch);
    }
    // RU:431-445 -> {yaw, pitch}
    static float[] vecToRot(double x, double y, double z) {
        double sinPitch = clampD(-y, -1.0, 1.0);
        double radPitch = Math.asin(sinPitch);
        double cosPitch = Math.cos(radPitch);
        if (cosPitch < 1.0E-7) return new float[]{0.0F, (float) (radPitch) / 0.017453292F};
        double sinNegYaw = x / cosPitch;
        double cosNegYaw = clampD(z / cosPitch, -1, 1);
        double radNegYaw = Math.acos(cosNegYaw);
        if (sinNegYaw < 0) radNegYaw = Math.PI * 2 - radNegYaw;
        return new float[]{wrapDegrees((float) (-radNegYaw) / 0.017453292F), (float) (radPitch) / 0.017453292F};
    }
    // QU:32-74
    static Quaternionf getRotationBetween(V from, V to, V antiparallelAxis) {
        from = from.normalize(); to = to.normalize();
        double dot = from.dot(to);
        if (dot >= 0.999999) return new Quaternionf();
        if (dot <= -0.999999) {
            V axis = antiparallelAxis;
            if (axis == null || axis.lengthSqr() < 0.000001) {
                axis = from.cross(new V(1, 0, 0));
                if (axis.lengthSqr() < 0.000001) axis = from.cross(new V(0, 1, 0));
            } else {
                axis = axis.sub(from.scale(axis.dot(from)));
                if (axis.lengthSqr() < 0.000001) { axis = from.cross(new V(1, 0, 0)); if (axis.lengthSqr() < 0.000001) axis = from.cross(new V(0, 1, 0)); }
            }
            axis = axis.normalize();
            return new Quaternionf().fromAxisAngleRad(new Vector3f((float) axis.x, (float) axis.y, (float) axis.z), (float) Math.PI);
        }
        V axis = from.cross(to).normalize();
        double angle = Math.acos(clampD(dot, -1.0, 1.0));
        return new Quaternionf().fromAxisAngleRad(new Vector3f((float) axis.x, (float) axis.y, (float) axis.z), (float) angle);
    }
    static double angleBetweenV(V a, V b) { return Math.acos(clampD(a.normalize().dot(b.normalize()), -1.0, 1.0)); }
    // GCI:3311-3314
    static float angleBetweenQ(Quaternionf a, Quaternionf b) {
        float dot = Math.abs(a.x() * b.x() + a.y() * b.y() + a.z() * b.z() + a.w() * b.w());
        return (float) (2.0 * Math.acos(clampF(dot, 0.0f, 1.0f)));
    }
    /** pure local-Y twist of frame vs identity transported onto the frame's up (GCI:2854-2874 construction) */
    static double twistAboutY(Quaternionf frame) {
        V up = p2w(new V(0, 1, 0), frame);
        V cardUp = new V(0, 1, 0);
        double tilt = angleBetweenV(cardUp, up);
        Quaternionf ref = new Quaternionf();
        if (tilt > 1.0E-6) ref.mul(getRotationBetween(cardUp, up, null).conjugate()).normalize();
        Quaternionf diff = new Quaternionf(frame).mul(new Quaternionf(ref).conjugate());
        if (diff.w() < 0) diff.set(-diff.x(), -diff.y(), -diff.z(), -diff.w());
        return Math.toDegrees(2.0 * Math.atan2(diff.y(), diff.w()));
    }

    // ---------------------------------------------------------------- world: solid cube [-2,3)^3, core at (0.5,0.5,0.5)
    static final double CMIN = -2.0, CMAX = 3.0;
    static final V CORE = new V(0.5, 0.5, 0.5);
    static boolean insideSolid(V p) { return p.x >= CMIN && p.x < CMAX && p.y >= CMIN && p.y < CMAX && p.z >= CMIN && p.z < CMAX; }
    /** MC level.clip simplified: start inside a full block -> immediate hit with normal = -nearest(dir) (VoxelShape.clip); else slab entry face. */
    static V probeSurfaceNormal(V from, V dir, double dist) {
        V d = dir.scale(dist);
        V start2 = from.add(d.scale(0.001));
        if (insideSolid(start2)) return Dir.getNearest(d.x, d.y, d.z).opposite().normal();
        double tmin = 0, tmax = 1; int enterAxis = -1; double enterSign = 0;
        double[] o = {from.x, from.y, from.z}, dd = {d.x, d.y, d.z};
        for (int a = 0; a < 3; a++) {
            if (Math.abs(dd[a]) < 1e-12) { if (o[a] < CMIN || o[a] > CMAX) return null; continue; }
            double t1 = (CMIN - o[a]) / dd[a], t2 = (CMAX - o[a]) / dd[a];
            double sign = -1; if (t1 > t2) { double t = t1; t1 = t2; t2 = t; sign = 1; }
            if (t1 > tmin) { tmin = t1; enterAxis = a; enterSign = sign; }
            tmax = Math.min(tmax, t2);
            if (tmin > tmax) return null;
        }
        if (enterAxis < 0) return null;
        double[] n = new double[3]; n[enterAxis] = enterSign;
        return new V(n[0], n[1], n[2]);
    }

    // ---------------------------------------------------------------- CapsuleCollider (CC:120-230, 350-490, 500-535), single obstacle, no ships
    static final double SKIN = 1.0E-4, STEP_HEIGHT = 0.6, GROUND_NORMAL_DOT = 0.55;
    static final class RState { boolean grounded, touched; double bestGroundDot, bestFrameDot; V groundNormal, frameGroundNormal, gravityUp; }
    static final class CResult { V collided; boolean grounded; V groundNormal, frameGroundNormal; boolean stepped; }
    static double capsuleRadius() { return Math.max(0.01, 0.6 / 2.0 * (14.0 / 15.0)); }
    static double capsuleHeight(double r) { return Math.max(1.8, r * 2.0 + 0.02); }
    static double[] sphereOffsets(double height, double radius) {
        double bottom = radius, top = Math.max(height - radius, radius);
        if (top - bottom < radius) return top - bottom < 1.0E-4 ? new double[]{bottom} : new double[]{bottom, top};
        return new double[]{bottom, (bottom + top) / 2.0, top};
    }
    static V rejectFrom(V v, V u) { return v.sub(u.scale(v.dot(u))); }
    static V supportFaceNormal(V c, V up, V gUp) {
        V best = null; double bestDot = 0;
        for (int i = 0; i < 6; i++) {
            double beyond; V n;
            switch (i) {
                case 0: beyond = CMIN - c.x; n = new V(-1, 0, 0); break;
                case 1: beyond = c.x - CMAX; n = new V(1, 0, 0); break;
                case 2: beyond = CMIN - c.y; n = new V(0, -1, 0); break;
                case 3: beyond = c.y - CMAX; n = new V(0, 1, 0); break;
                case 4: beyond = CMIN - c.z; n = new V(0, 0, -1); break;
                default: beyond = c.z - CMAX; n = new V(0, 0, 1); break;
            }
            if (beyond <= 0) continue;
            double dot = Math.max(n.dot(up), n.dot(gUp));
            if (dot > bestDot) { bestDot = dot; best = n; }
        }
        return best;
    }
    static V depenetrate(V pos, V up, double r, double[] offs, RState st) {
        for (int it = 0; it < 12; it++) {
            double worstDepth = 0; V worstNormal = null, worstCenter = null;
            for (double off : offs) {
                V c = pos.add(up.scale(off));
                double cx = clampD(c.x, CMIN, CMAX), cy = clampD(c.y, CMIN, CMAX), cz = clampD(c.z, CMIN, CMAX);
                double dx = c.x - cx, dy = c.y - cy, dz = c.z - cz, d2 = dx * dx + dy * dy + dz * dz;
                if (d2 >= r * r || d2 < 1.0E-12) {
                    if (d2 < 1.0E-12 && insideSolid(c)) {
                        double[] fd = {c.x - CMIN, CMAX - c.x, c.y - CMIN, CMAX - c.y, c.z - CMIN, CMAX - c.z};
                        int b = 0; for (int i = 1; i < 6; i++) if (fd[i] < fd[b]) b = i;
                        V[] ns = {new V(-1,0,0), new V(1,0,0), new V(0,-1,0), new V(0,1,0), new V(0,0,-1), new V(0,0,1)};
                        double depth = r + fd[b];
                        if (depth > worstDepth) { worstDepth = depth; worstNormal = ns[b]; worstCenter = c; }
                    }
                    continue;
                }
                double dist = Math.sqrt(d2), depth = r - dist;
                if (depth > worstDepth) { worstDepth = depth; worstNormal = new V(dx / dist, dy / dist, dz / dist); worstCenter = c; }
            }
            if (worstNormal == null) break;
            st.touched = true;
            V pushDir = worstNormal; double pushLen = worstDepth + SKIN;
            double upDot = Math.max(worstNormal.dot(up), worstNormal.dot(st.gravityUp));
            V support = supportFaceNormal(worstCenter, up, st.gravityUp);
            if (support != null) {
                double sUp = Math.max(support.dot(up), support.dot(st.gravityUp)), align = worstNormal.dot(support);
                if (sUp > GROUND_NORMAL_DOT && align > 0.4) { pushDir = support; pushLen = worstDepth / align + SKIN; upDot = sUp; }
            }
            pos = pos.add(pushDir.scale(pushLen));
            if (upDot > GROUND_NORMAL_DOT) { st.grounded = true; if (upDot > st.bestGroundDot) { st.bestGroundDot = upDot; st.groundNormal = pushDir; } }
            double frameDot = pushDir.dot(up);
            if (frameDot > GROUND_NORMAL_DOT && frameDot > st.bestFrameDot) { st.bestFrameDot = frameDot; st.frameGroundNormal = pushDir; }
        }
        return pos;
    }
    static V sweep(V start, V movement, V up, double r, double[] offs, RState st) {
        V correction = V.ZERO; double length = movement.length();
        int substeps = clampI((int) Math.ceil(length / (r * 0.5)), 1, 256);
        for (int i = 1; i <= substeps; i++) {
            V target = start.add(movement.scale((double) i / substeps)).add(correction);
            V resolved = depenetrate(target, up, r, offs, st);
            correction = correction.add(resolved.sub(target));
        }
        return correction;
    }
    static CResult capsuleCollide(V start, V up, V gravityUp, V movement, boolean wasGrounded) {
        double r = capsuleRadius(), h = capsuleHeight(r); double[] offs = sphereOffsets(h, r);
        RState st = new RState(); st.gravityUp = gravityUp;
        V correction = sweep(start, movement, up, r, offs, st);
        CResult res = new CResult();
        if (!st.touched) { res.collided = movement; return res; }
        V collided = movement.add(correction);
        double bodyScale = clampD(h / 1.8, 0.05, 1.0), stepHeight = STEP_HEIGHT * bodyScale;
        if (wasGrounded && movement.dot(up) < 0.05 * bodyScale) {
            V tI = rejectFrom(movement, up), tA = rejectFrom(collided, up);
            double iLen = tI.length();
            double achievedAlong = iLen > 1.0E-5 ? tA.dot(tI) / iLen : 0.0;
            if (iLen > 1.0E-5 && achievedAlong < iLen * 0.9) {
                RState ss = new RState(); ss.gravityUp = gravityUp;
                V lift = up.scale(stepHeight);
                V lifted = start.add(lift).add(sweep(start, lift, up, r, offs, ss));
                V movedUp = lifted.add(movement).add(sweep(lifted, movement, up, r, offs, ss));
                V drop = up.scale(-(stepHeight + SKIN * 2));
                V settled = movedUp.add(drop).add(sweep(movedUp, drop, up, r, offs, ss));
                V stepMovement = settled.sub(start);
                V stepTangent = rejectFrom(stepMovement, up);
                if (ss.grounded && stepTangent.length() > tA.length() + 0.01 * bodyScale) {
                    collided = stepMovement; res.stepped = true;
                    st.grounded = st.grounded || ss.grounded;
                    if (ss.bestGroundDot > st.bestGroundDot) { st.bestGroundDot = ss.bestGroundDot; st.groundNormal = ss.groundNormal; }
                    if (ss.bestFrameDot > st.bestFrameDot) { st.bestFrameDot = ss.bestFrameDot; st.frameGroundNormal = ss.frameGroundNormal; }
                }
            }
        }
        res.collided = collided; res.grounded = st.grounded; res.groundNormal = st.groundNormal; res.frameGroundNormal = st.frameGroundNormal;
        return res;
    }
    /** vanilla box collide (Y, then larger of X/Z first) of the 0.6x1.8 box against the cube */
    static V boxCollide(V pos, V m) {
        double[] bmin = {pos.x - 0.3, pos.y, pos.z - 0.3}, bmax = {pos.x + 0.3, pos.y + 1.8, pos.z + 0.3};
        double[] mv = {m.x, m.y, m.z};
        int[] order = Math.abs(m.z) > Math.abs(m.x) ? new int[]{1, 2, 0} : new int[]{1, 0, 2};
        for (int a : order) {
            if (mv[a] == 0) continue;
            boolean overlapOthers = true;
            for (int b = 0; b < 3; b++) if (b != a && !(bmax[b] > CMIN && bmin[b] < CMAX)) overlapOthers = false;
            if (overlapOthers) {
                if (mv[a] > 0 && bmax[a] <= CMIN) mv[a] = Math.min(mv[a], CMIN - bmax[a]);
                else if (mv[a] < 0 && bmin[a] >= CMAX) mv[a] = Math.max(mv[a], CMAX - bmin[a]);
            }
            bmin[a] += mv[a]; bmax[a] += mv[a];
        }
        return new V(mv[0], mv[1], mv[2]);
    }

    // ---------------------------------------------------------------- simulated client player + capability
    static final float VISUAL_TURN_PER_TICK = (float) Math.toRadians(15);
    static final float TRANSITION_TURN_PER_TICK = (float) Math.toRadians(30);
    static final float EXACT_ANCHOR_ANGLE = (float) Math.toRadians(0.15);
    static final float IDENTITY_W = 0.9999996f;
    static final int FIELD_GRACE_TICKS = 6, GROUND_NORMAL_GRACE_TICKS = 16, SURFACE_CHANGE_COOLDOWN_TICKS = 8, RECENT_RELEASE_MEMORY_TICKS = 15, FRAME_RELEASE_TICKS = 1;
    static final double SNAP_HYSTERESIS_DOT = 0.10, SUPPORT_KEEP_DOT = 0.15, GROUND_PROBE_DEPTH = 0.6;
    static boolean ADJUST_POSITION_CONFIG = true;
    static boolean FIX_PUSHOUT_GATE = false;
    double maxDv = 0, maxDl = 0, maxDlChase = 0;

    final StringBuilder log = new StringBuilder();
    final List<String> anomalies = new ArrayList<>();
    String tag;
    final Quaternionf visualRotation = new Quaternionf(), prevVisualRotation = new Quaternionf(), visualTarget = new Quaternionf(), lastChaseTarget = new Quaternionf();
    int targetStableTicks = 0;
    Dir currGravityDirection = Dir.DOWN, prevGravityDirection = Dir.DOWN;
    V targetGravityVector = null, lastFieldVector = null;
    int fieldGraceTicks = 0, oppositeStableTicks = 0;
    V lastGroundNormal = null; int groundNormalGraceTicks = 0, surfaceChangeCooldown = 0;
    V recentReleasedNormal = null; int recentReleasedTicks = 0;
    boolean capsuleGrounded = false; V capsuleGroundNormal = null, capsuleFrameGroundNormal = null;
    int contactDisagreeTicks = 0, frameUprightTicks = 0; V lastRotatedUp = null;
    V pos, old; V dm = V.ZERO; float yRot, yRotO; boolean onGround = true; float xxa = 0, zza = 0; boolean input = false;
    double bbMinY, bbMaxY;
    V worldVelAfterLastTravel = null, worldLookAfterLastTravel = null;
    int tick = 0;
    List<String> flags = new ArrayList<>();

    boolean isVisuallyDefault() { return currGravityDirection == Dir.DOWN && Math.abs(visualRotation.w()) >= IDENTITY_W; }
    boolean useCapsuleCollision() { return !isVisuallyDefault(); }
    boolean isGravityDefault() { return currGravityDirection == Dir.DOWN; }
    V getUpVector() { return p2w(new V(0, 1, 0), visualRotation); }
    V cardinalVec() { return currGravityDirection.normal(); }
    V getTargetGravityVector() { return targetGravityVector != null ? targetGravityVector : cardinalVec(); }
    V effectiveTargetUp(V field) { V tu = field.scale(-1); if (lastGroundNormal != null && lastGroundNormal.dot(tu) > SUPPORT_KEEP_DOT) return lastGroundNormal; return tu; }
    V getEffectiveUpVector() { V f = getTargetGravityVector(); V fn = f.lengthSqr() > 1.0E-6 ? f.normalize() : cardinalVec(); return effectiveTargetUp(fn); }
    V worldVel() { return isVisuallyDefault() ? dm : p2w(dm, visualRotation); }
    V worldLook() { return p2w(rotToVec(yRot, 0), visualRotation); }

    void setPos(V p) { pos = p; updateBoundingBox(); }
    void updateBoundingBox() {
        if (isVisuallyDefault()) { bbMinY = pos.y; bbMaxY = pos.y + 1.8; return; }
        double radius = 0.3; double[] offs = sphereOffsets(1.8, radius); V up = getUpVector(); // EM:448-461 -> CC.makeEnvelope
        V low = pos.add(up.scale(offs[0])), high = pos.add(up.scale(offs[offs.length - 1]));
        bbMinY = Math.min(low.y, high.y) - radius; bbMaxY = Math.max(low.y, high.y) + radius;
    }

    // GCI:2968-3010
    void compensateFrameChange(Quaternionf oldFrame, Quaternionf newFrame, String why) {
        V worldLookFlat = p2w(rotToVec(yRot, 0), oldFrame);
        V nl = w2p(worldLookFlat, newFrame);
        float[] newRot = vecToRot(nl.x, nl.y, nl.z);
        float deltaYaw = wrapDegrees(newRot[0] - yRot);
        yRot = yRot + deltaYaw; yRotO += deltaYaw;
        V worldVelocity = p2w(dm, oldFrame);
        dm = w2p(worldVelocity, newFrame);
        flags.add(String.format(Locale.ROOT, "compensate[%s] dYaw=%+.4f", why, deltaYaw));
    }

    // GCI:3012-3225 living path
    void computeVisualTarget(Quaternionf out) {
        V cardinal = cardinalVec();
        V target = targetGravityVector != null && targetGravityVector.lengthSqr() > 1.0E-6 ? targetGravityVector.normalize() : cardinal;
        V targetUp = effectiveTargetUp(target);
        V currentUp = getUpVector();
        double angle = angleBetweenV(currentUp, targetUp);
        if (angle < 1.0E-4) { out.set(visualRotation); return; }
        V lookWorld = p2w(rotToVec(yRot, 0), visualRotation);
        Quaternionf delta = getRotationBetween(currentUp, targetUp, lookWorld);
        out.set(visualRotation).mul(delta.conjugate()).normalize();
    }

    // GCI:2712-2900 (client player: not remote, no ship)
    void normalizeTwist() {
        Quaternionf canonical = canonical(currGravityDirection);
        if (visualRotation.equals(canonical)) return;
        if (angleBetweenQ(visualRotation, visualTarget) > (float) Math.toRadians(0.3)) return;
        if (angleBetweenQ(visualRotation, canonical) < EXACT_ANCHOR_ANGLE) {
            Quaternionf old = new Quaternionf(visualRotation);
            visualRotation.set(canonical); visualTarget.set(canonical); prevVisualRotation.set(canonical);
            compensateFrameChange(old, visualRotation, "anchor");
            flags.add("ANCHOR");
            return;
        }
        V currentUp = getUpVector(); V cardinalUp = cardinalVec().scale(-1);
        double tilt = angleBetweenV(cardinalUp, currentUp);
        if (tilt > Math.toRadians(90)) return;
        Quaternionf reference = new Quaternionf(canonical);
        if (tilt > 1.0E-6) reference.mul(getRotationBetween(cardinalUp, currentUp, null).conjugate()).normalize();
        Quaternionf diff = new Quaternionf(visualRotation).mul(new Quaternionf(reference).conjugate());
        if (diff.w() < 0) diff.set(-diff.x(), -diff.y(), -diff.z(), -diff.w());
        float twist = 2.0f * (float) Math.atan2(diff.y(), diff.w());
        if (Math.abs(twist) < 1.0E-6f) return;
        Quaternionf delta = new Quaternionf().rotationY(-twist);
        Quaternionf old = new Quaternionf(visualRotation);
        visualRotation.premul(delta).normalize();
        prevVisualRotation.premul(delta).normalize();
        visualTarget.premul(delta).normalize();
        compensateFrameChange(old, visualRotation, String.format(Locale.ROOT, "unwind twist=%.4fdeg", Math.toDegrees(twist)));
        flags.add("UNWIND");
    }

    // GCI:2331-2700 (client player path)
    void advanceVisualRotation() {
        prevVisualRotation.set(visualRotation);
        Quaternionf frameBefore = new Quaternionf(visualRotation);
        computeVisualTarget(visualTarget);
        if (angleBetweenQ(visualTarget, lastChaseTarget) < (float) Math.toRadians(0.05)) targetStableTicks++; else targetStableTicks = 0;
        lastChaseTarget.set(visualTarget);
        float angle = angleBetweenQ(visualRotation, visualTarget);
        if (angle < (float) Math.toRadians(0.02)) { visualRotation.set(visualTarget); }
        else {
            boolean glued = false;
            if (lastGroundNormal != null && (capsuleGrounded || onGround)) { V tUp = p2w(new V(0, 1, 0), visualTarget); glued = tUp.dot(lastGroundNormal) > 0.95; }
            float smoothGain = lerp(clampF(angle / (float) Math.toRadians(3), 0.0f, 1.0f), 0.08f, 0.35f);
            float stableBlend = clampF((targetStableTicks - 2) / 8.0f, 0.0f, 1.0f);
            float proportion = glued ? 0.9f : lerp(stableBlend, smoothGain, 0.5f);
            float maxTurn = VISUAL_TURN_PER_TICK;
            if (surfaceChangeCooldown > 0) { proportion = Math.max(proportion, 0.45f); maxTurn = TRANSITION_TURN_PER_TICK; }
            float step = Math.min(angle * proportion, maxTurn);
            visualRotation.slerp(visualTarget, step / angle).normalize();
        }
        if (!visualRotation.equals(frameBefore)) {
            // controlled == true for the local player (LocalPlayer.isEffectiveAi() returns true, javap), not flying
            V world = p2w(dm, frameBefore);
            dm = w2p(world, visualRotation);
            flags.add(String.format(Locale.ROOT, "chase step=%.4fdeg", Math.toDegrees(angleBetweenQ(frameBefore, visualRotation))));
        }
        normalizeTwist();
        float stepMoved = angleBetweenQ(frameBefore, visualRotation);
        if (stepMoved > (float) Math.toRadians(1.5) && useCapsuleCollision()) {
            CResult r = capsuleCollide(pos, getUpVector(), getEffectiveUpVector(), V.ZERO, false);
            if (r.collided.lengthSqr() > 1.0E-10) { setPos(pos.add(r.collided)); flags.add("NUDGE " + r.collided); }
        }
    }

    // GCI:2195-2250
    void applyTransitionPull() {
        if (!useCapsuleCollision()) return;
        V field = getTargetGravityVector();
        if (field.lengthSqr() < 1.0E-6) return;
        V targetDown = effectiveTargetUp(field.normalize()).scale(-1);
        V frameDown = p2w(new V(0, -1, 0), visualRotation);
        if (targetDown.dot(frameDown) > 0.9998) return;
        double g = 0.08;
        V correction = targetDown.sub(frameDown).scale(g);
        dm = dm.add(w2p(correction, visualRotation));
        flags.add("transitionPull " + correction);
    }

    // GCI:1444-1530 (client player)
    void clearBlocksOnFieldExit() {
        V up = getUpVector();
        if (up.y < 0.99 && up.lengthSqr() > 1.0E-6) lastRotatedUp = up;
        V targetUp = p2w(new V(0, 1, 0), visualTarget);
        if (targetUp.y <= 0.999) { frameUprightTicks = 0; return; }
        if (frameUprightTicks > FRAME_RELEASE_TICKS) return;
        if (++frameUprightTicks < FRAME_RELEASE_TICKS) return;
        frameUprightTicks = FRAME_RELEASE_TICKS + 1;
        V escape = lastRotatedUp; if (escape == null) return; lastRotatedUp = null;
        if (!ADJUST_POSITION_CONFIG) return;
        double minX = pos.x - 0.3 + 0.01, maxX = pos.x + 0.3 - 0.01, minY = pos.y + 0.01, maxY = pos.y + 1.8 - 0.01, minZ = pos.z - 0.3 + 0.01, maxZ = pos.z + 0.3 - 0.01;
        boolean overlap = maxX > CMIN && minX < CMAX && maxY > CMIN && minY < CMAX && maxZ > CMIN && minZ < CMAX;
        if (!overlap) return;
        double uMinX = Math.max(CMIN, Math.floor(minX)), uMaxX = Math.min(CMAX, Math.floor(maxX) + 1);
        double uMinY = Math.max(CMIN, Math.floor(minY)), uMaxY = Math.min(CMAX, Math.floor(maxY) + 1);
        double uMinZ = Math.max(CMIN, Math.floor(minZ)), uMaxZ = Math.min(CMAX, Math.floor(maxZ) + 1);
        V e = escape.normalize();
        Dir nearest = Dir.getNearest(e.x, e.y, e.z);
        double bMin, bMax, uMin, uMax;
        switch (nearest) {
            case WEST: case EAST: bMin = pos.x - 0.3; bMax = pos.x + 0.3; uMin = uMinX; uMax = uMaxX; break;
            case DOWN: case UP: bMin = pos.y; bMax = pos.y + 1.8; uMin = uMinY; uMax = uMaxY; break;
            default: bMin = pos.z - 0.3; bMax = pos.z + 0.3; uMin = uMinZ; uMax = uMaxZ; break;
        }
        double offset = 0;
        if (nearest.positive()) { if (uMax > bMin) offset = uMax - bMin; } else { if (uMin < bMax) offset = bMax - uMin; }
        V off = nearest.normal().scale(offset);
        if (off.lengthSqr() < 1.0E-12) return;
        setPos(pos.add(off)); old = old.add(off);
        flags.add("CLEARBLOCKS step " + off + " via " + nearest);
    }

    // GCI:1930-1990
    void adoptGroundNormal(V normal) {
        if (recentReleasedTicks > 0 && recentReleasedNormal != null && recentReleasedNormal.dot(normal) > 0.95 && !capsuleGrounded) return;
        if (lastGroundNormal != null && lastGroundNormal.dot(normal) < 0.99) {
            if (surfaceChangeCooldown > 0) { groundNormalGraceTicks = GROUND_NORMAL_GRACE_TICKS; return; }
            surfaceChangeCooldown = SURFACE_CHANGE_COOLDOWN_TICKS;
            rotateVelocityAcrossSurfaceChange(lastGroundNormal, normal);
            flags.add("ADOPT " + normal + " (face change)");
        } else if (lastGroundNormal == null && recentReleasedNormal != null && recentReleasedNormal.dot(normal) < 0.99) {
            V fieldUp = targetGravityVector != null && targetGravityVector.lengthSqr() > 1.0E-6 ? targetGravityVector.normalize().scale(-1) : cardinalVec().scale(-1);
            if (normal.dot(fieldUp) > 0.35 && surfaceChangeCooldown == 0) { surfaceChangeCooldown = SURFACE_CHANGE_COOLDOWN_TICKS; rotateVelocityAcrossSurfaceChange(recentReleasedNormal, normal); flags.add("ADOPT-after-release " + normal); }
        }
        recentReleasedNormal = null; recentReleasedTicks = 0;
        lastGroundNormal = normal; groundNormalGraceTicks = GROUND_NORMAL_GRACE_TICKS;
        if (!useCapsuleCollision()) { snapPhysicsDirection(); applyGravityChange(); }
    }
    // GCI:2032-2057
    void rotateVelocityAcrossSurfaceChange(V oldN, V newN) {
        double dot = oldN.dot(newN);
        if (dot > 0.99 || dot < -0.5) return;
        Quaternionf arc = getRotationBetween(oldN, newN, null);
        V worldVelocity = p2w(dm, visualRotation);
        V rotated = rotate(worldVelocity, arc);
        dm = w2p(rotated, visualRotation);
        flags.add("rotateVelocityAcrossSurfaceChange " + worldVelocity + " -> " + rotated);
    }
    // GCI:3788-3841
    void snapPhysicsDirection() {
        if (targetGravityVector == null || targetGravityVector.lengthSqr() < 1.0E-6) return;
        V target = targetGravityVector.normalize();
        target = effectiveTargetUp(target).scale(-1);
        Dir candidate = Dir.getNearest(target.x, target.y, target.z);
        if (candidate == currGravityDirection) { oppositeStableTicks = 0; return; }
        double cd = target.dot(candidate.normal()), cur = target.dot(currGravityDirection.normal());
        if (cd < cur + SNAP_HYSTERESIS_DOT) return;
        if (candidate == currGravityDirection.opposite()) { oppositeStableTicks++; if (oppositeStableTicks < 3) return; }
        oppositeStableTicks = 0;
        currGravityDirection = candidate;
        flags.add("CARDINAL -> " + candidate);
    }
    // GCI:3843-3870 (Player: box update only)
    void applyGravityChange() { if (prevGravityDirection != currGravityDirection) { prevGravityDirection = currGravityDirection; updateBoundingBox(); } }

    // GCI:2060-2080
    V getInputTangentDirection(V surfaceNormal) {
        if (Math.abs(xxa) <= 0.01 && Math.abs(zza) <= 0.01) return null;
        float yawRad = yRot * ((float) Math.PI / 180.0F);
        double sin = Math.sin(yawRad), cos = Math.cos(yawRad);
        V inputLocal = new V(xxa * cos - zza * sin, 0, zza * cos + xxa * sin);
        V inputWorld = p2w(inputLocal, visualRotation);
        V t = inputWorld.sub(surfaceNormal.scale(inputWorld.dot(surfaceNormal)));
        return t.lengthSqr() > 1.0E-6 ? t.normalize() : null;
    }

    // GCI:1549-1918 (world blocks only, no ship, walking player)
    void updateSurfaceProbe() {
        if (!useCapsuleCollision()) { capsuleGrounded = onGround; capsuleGroundNormal = null; }
        boolean gravityActive = fieldGraceTicks > 0;
        if (!gravityActive) { lastGroundNormal = null; groundNormalGraceTicks = 0; surfaceChangeCooldown = 0; recentReleasedNormal = null; recentReleasedTicks = 0; return; }
        if (surfaceChangeCooldown > 0) surfaceChangeCooldown--;
        if (recentReleasedTicks > 0 && --recentReleasedTicks == 0) recentReleasedNormal = null;
        V fieldDown = targetGravityVector != null && targetGravityVector.lengthSqr() > 1.0E-6 ? targetGravityVector.normalize() : cardinalVec();
        V fieldUp = fieldDown.scale(-1);
        boolean held = lastGroundNormal != null && lastGroundNormal.dot(fieldUp) > SUPPORT_KEEP_DOT;
        V heldNormal = held ? lastGroundNormal : null;
        V probeDown = held ? heldNormal.scale(-1) : fieldDown;
        V feet = pos; double probeScale = 1.0;
        V moved = pos.sub(old);
        V tangent = held ? moved.sub(heldNormal.scale(moved.dot(heldNormal))) : V.ZERO;
        double tangentSpeed = tangent.length();
        V tangentDir = tangentSpeed > 1.0E-6 ? tangent.scale(1.0 / tangentSpeed) : V.ZERO;
        V inputTangentDir = held ? getInputTangentDirection(heldNormal) : null;
        if (inputTangentDir != null && tangentSpeed <= 0.02) { tangentDir = inputTangentDir; tangentSpeed = 0.1; }
        if (held && capsuleGrounded && capsuleGroundNormal != null) {
            if (capsuleGroundNormal.dot(heldNormal) < 0.8 && capsuleGroundNormal.dot(fieldUp) > 0.2 && capsuleGroundNormal.dot(fieldUp) > 0.5 * heldNormal.dot(fieldUp)) {
                if (++contactDisagreeTicks >= 4) { contactDisagreeTicks = 0; adoptGroundNormal(capsuleGroundNormal); flags.add("adopt via ramp consensus"); return; }
            } else contactDisagreeTicks = 0;
        }
        V normal = probeSurfaceNormal(feet.sub(probeDown.scale(0.2 * probeScale)), probeDown, (0.2 + GROUND_PROBE_DEPTH) * probeScale);
        if (normal != null && normal.dot(fieldUp) > SUPPORT_KEEP_DOT) {
            adoptGroundNormal(normal);
            if (held) {
                V probeDir = inputTangentDir != null ? inputTangentDir : (tangentSpeed > 0.02 ? tangentDir : null);
                V wall = probeDir == null ? null : probeSurfaceNormal(feet.add(heldNormal.scale(0.2 * probeScale)), probeDir, 0.5 * probeScale);
                double wallGate = heldNormal.dot(fieldUp); // fieldRadial == true for a core
                if (wall != null && wall.dot(fieldUp) > 0.2 && wall.dot(fieldUp) > wallGate && wall.dot(heldNormal) < 0.7 && wall.dot(probeDir) < -0.5) { adoptGroundNormal(wall); flags.add("adopt via concave wall"); }
            }
            return;
        }
        if (!held) {
            V frameUp = getUpVector(), frameDown = frameUp.scale(-1);
            V support = probeSurfaceNormal(feet.sub(frameDown.scale(0.2 * probeScale)), frameDown, (0.2 + GROUND_PROBE_DEPTH) * probeScale);
            if (support != null && support.dot(frameUp) > 0.35 && support.dot(fieldUp) > SUPPORT_KEEP_DOT) { adoptGroundNormal(support); flags.add("adopt via support"); return; }
        }
        if (held && tangentSpeed > 0.01) {
            V wrap = probeSurfaceNormal(feet.sub(heldNormal.scale(0.15 * probeScale)), tangentDir.scale(-1), 0.75 * probeScale);
            double wrapGate = 0.9 * heldNormal.dot(fieldUp); // fieldRadial
            if (wrap != null && wrap.dot(fieldUp) > 0.35 && wrap.dot(fieldUp) > wrapGate && wrap.dot(heldNormal) < 0.7 && wrap.dot(tangentDir) > 0.1) { adoptGroundNormal(wrap); flags.add("adopt via convex wrap"); return; }
        }
        boolean contactConfirms = held && (useCapsuleCollision() ? capsuleGrounded && capsuleGroundNormal != null && capsuleGroundNormal.dot(heldNormal) > 0.8 : onGround && heldNormal.y > 0.8);
        if (contactConfirms) { groundNormalGraceTicks = GROUND_NORMAL_GRACE_TICKS; contactDisagreeTicks = 0; return; }
        if (surfaceChangeCooldown > 0 && lastGroundNormal != null) { groundNormalGraceTicks = GROUND_NORMAL_GRACE_TICKS; return; }
        if (lastGroundNormal != null && !capsuleGrounded && moved.dot(lastGroundNormal) < -0.08) {
            V under = probeSurfaceNormal(feet, lastGroundNormal.scale(-1), 2.5 * probeScale);
            if (under == null || under.dot(lastGroundNormal) < 0.7) groundNormalGraceTicks = Math.min(groundNormalGraceTicks, 1);
        }
        if (groundNormalGraceTicks > 0) groundNormalGraceTicks--;
        else { if (lastGroundNormal != null) { recentReleasedNormal = lastGroundNormal; recentReleasedTicks = RECENT_RELEASE_MEMORY_TICKS; flags.add("RELEASE held normal"); } lastGroundNormal = null; }
    }

    // GCI:3320-3640 (single core field)
    void updateGravityStatus() {
        V toCenter = CORE.sub(pos);
        double dist = toCenter.length();
        targetGravityVector = toCenter.scale(1.0 / dist); // attracting core, GravityCoreBlockEntity:300-302
        fieldGraceTicks = FIELD_GRACE_TICKS; lastFieldVector = targetGravityVector;
        snapPhysicsDirection();
    }

    void guTick() {
        updateGravityStatus();
        applyGravityChange();
        updateSurfaceProbe();
        advanceVisualRotation();
        clearBlocksOnFieldExit();
        applyTransitionPull();
        // applyStaticFriction: returns while input held (|zza|>0.01); idle on frame-flat ground it also returns (dot>0.999)
    }

    // LocalPlayer.aiStep: moveTowardsClosestSpace x4 (vanilla 468-503, 745-748); GU LocalPlayerEntityMixin:57-68 cancels only when !isGravityDefault
    void pushOutOfBlocks() {
        if (FIX_PUSHOUT_GATE ? useCapsuleCollision() : !isGravityDefault()) return;
        double w = 0.6;
        moveTowardsClosestSpace(pos.x - w * 0.35, pos.z + w * 0.35);
        moveTowardsClosestSpace(pos.x - w * 0.35, pos.z - w * 0.35);
        moveTowardsClosestSpace(pos.x + w * 0.35, pos.z - w * 0.35);
        moveTowardsClosestSpace(pos.x + w * 0.35, pos.z + w * 0.35);
    }
    boolean suffocatesAt(int bx, int by, int bz) {
        double minY = bbMinY + 1.0E-7, maxY = bbMaxY - 1.0E-7;
        boolean col = bx >= -2 && bx <= 2 && bz >= -2 && bz <= 2;
        return col && minY < CMAX && maxY > CMIN;
    }
    void moveTowardsClosestSpace(double px, double pz) {
        int bx = (int) Math.floor(px), by = (int) Math.floor(pos.y), bz = (int) Math.floor(pz);
        if (!suffocatesAt(bx, by, bz)) return;
        double d0 = px - bx, d1 = pz - bz;
        Dir dir = null; double d2 = Double.MAX_VALUE;
        for (Dir d : new Dir[]{Dir.WEST, Dir.EAST, Dir.NORTH, Dir.SOUTH}) {
            double d3 = (d == Dir.WEST || d == Dir.EAST) ? d0 : d1;
            double d4 = d.positive() ? 1.0 - d3 : d3;
            if (d4 < d2 && !suffocatesAt(bx + d.nx, by, bz + d.nz)) { d2 = d4; dir = d; }
        }
        if (dir != null) {
            if (dir == Dir.WEST || dir == Dir.EAST) dm = new V(0.1 * dir.nx, dm.y, dm.z); else dm = new V(dm.x, dm.y, 0.1 * dir.nz);
            flags.add("PUSHOUT " + dir + String.format(Locale.ROOT, " (sample %.3f,%.3f bb y[%.4f,%.4f])", px, pz, bbMinY, bbMaxY));
        }
    }

    // vanilla LivingEntity.aiStep/travel + GU EntityMixin move transforms
    void travel() {
        dm = new V(Math.abs(dm.x) < 0.003 ? 0 : dm.x, Math.abs(dm.y) < 0.003 ? 0 : dm.y, Math.abs(dm.z) < 0.003 ? 0 : dm.z);
        xxa = 0; zza = input ? 1.0f : 0.0f;
        zza *= 0.98F;
        float f2 = 0.6f;
        float f3 = onGround ? f2 * 0.91F : 0.91F;
        float speed = onGround ? 0.1f * (0.21600002F / (f2 * f2 * f2)) : 0.02f;
        V rel = new V(xxa, 0, zza);
        double d0 = rel.lengthSqr();
        if (d0 >= 1.0E-7) {
            V v = (d0 > 1.0 ? rel.normalize() : rel).scale((double) speed);
            float f = msin(yRot * ((float) Math.PI / 180F)), f1 = mcos(yRot * ((float) Math.PI / 180F));
            dm = dm.add(new V(v.x * (double) f1 - v.z * (double) f, v.y, v.z * (double) f1 + v.x * (double) f));
        }
        move(dm);
        V vec35 = dm;
        double d2 = vec35.y - 0.08;
        dm = new V(vec35.x * (double) f3, d2 * (double) 0.98F, vec35.z * (double) f3);
    }
    void move(V local) {
        boolean vis = isVisuallyDefault();
        V world = vis ? local : p2w(local, visualRotation);
        V collided;
        if (useCapsuleCollision()) {
            V up = getUpVector(), gUp = getEffectiveUpVector();
            boolean stepEligible = capsuleGrounded && capsuleFrameGroundNormal != null && capsuleFrameGroundNormal.dot(up) > 0.85;
            CResult r = capsuleCollide(pos, up, gUp, world, stepEligible);
            collided = r.collided;
            capsuleGrounded = r.grounded; capsuleGroundNormal = r.grounded ? r.groundNormal : null; capsuleFrameGroundNormal = r.grounded ? r.frameGroundNormal : null;
            if (r.stepped) flags.add("STEP-ASSIST");
        } else {
            collided = boxCollide(pos, world);
        }
        if (collided.lengthSqr() > 1.0E-7) setPos(pos.add(collided));
        V lc;
        if (vis) lc = collided;
        else if (collided.eq(world)) lc = local;
        else {
            V dl = w2p(collided.sub(world), visualRotation);
            lc = new V(resolveAxis(local.x, dl.x), resolveAxis(local.y, dl.y), resolveAxis(local.z, dl.z));
        }
        boolean flag4 = !mequal(local.x, lc.x), flag = !mequal(local.z, lc.z);
        boolean horizontalCollision = flag4 || flag;
        boolean verticalCollision = local.y != lc.y;
        onGround = verticalCollision && local.y < 0.0D;
        if (horizontalCollision) dm = new V(flag4 ? 0.0 : dm.x, dm.y, flag ? 0.0 : dm.z);
        if (local.y != lc.y) dm = new V(dm.x, 0.0, dm.z);
        if (!vis && !collided.eq(world)) flags.add("collide dWorld=" + collided.sub(world));
    }
    static double resolveAxis(double intended, double delta) {
        if (Math.abs(delta) < 1.0E-6) return intended;
        if (delta * intended >= 0.0) return intended;
        if (Math.abs(delta) < 0.1 * Math.abs(intended)) return intended;
        return intended + delta;
    }

    // ---------------------------------------------------------------- driver
    void step() {
        tick++; flags.clear();
        old = pos; yRotO = yRot;
        V velBefore = worldVelAfterLastTravel, lookBefore = worldLookAfterLastTravel;
        guTick();
        V velAfterGu = worldVel(), lookAfterGu = worldLook();
        if (velBefore != null) {
            V expected = velBefore;
            for (String f : flags) if (f.startsWith("transitionPull")) expected = expected.add(parseVec(f.substring(f.indexOf('('))));
            boolean faceChange = flags.stream().anyMatch(f -> f.startsWith("rotateVelocityAcrossSurfaceChange"));
            V dv = velAfterGu.sub(expected);
            if (!faceChange) maxDv = Math.max(maxDv, dv.length());
            if (!faceChange && dv.length() > 2e-6) anomalies.add(String.format(Locale.ROOT, "[%s] t=%d world velocity changed across GU tick by %s (flags %s)", tag, tick, dv, flags));
            V dl = lookAfterGu.sub(lookBefore);
            boolean chased = flags.stream().anyMatch(f -> f.startsWith("chase"));
            if (!chased) maxDl = Math.max(maxDl, dl.length());
            if (!chased && dl.length() > 2e-6) anomalies.add(String.format(Locale.ROOT, "[%s] t=%d world look changed across GU tick with no chase step by %s (flags %s)", tag, tick, dl, flags));
            if (chased) {
                // look must move exactly with the frame tilt: rotate lookBefore by the same rotation that carried prev up onto the new up
                V upPrev = p2w(new V(0, 1, 0), prevVisualRotation), upNow = getUpVector();
                Quaternionf tiltQ = getRotationBetween(upPrev, upNow, null);
                V expectedLook = rotate(lookBefore, tiltQ);
                V resid = lookAfterGu.sub(expectedLook);
                maxDlChase = Math.max(maxDlChase, resid.length());
                if (resid.length() > 5e-4) anomalies.add(String.format(Locale.ROOT, "[%s] t=%d look moved by more than the tilt: residual %s (flags %s)", tag, tick, resid, flags));
            }
        }
        double twistBeforeMove = twistAboutY(visualRotation);
        boolean capsuleBefore = useCapsuleCollision();
        V dmBeforePush = dm;
        pushOutOfBlocks();
        if (!dm.eq(dmBeforePush)) anomalies.add(String.format(Locale.ROOT, "[%s] t=%d vanilla push-out changed dm %s -> %s (flags %s)", tag, tick, dmBeforePush, dm, flags));
        V posBefore = pos;
        travel();
        worldVelAfterLastTravel = worldVel(); worldLookAfterLastTravel = worldLook();
        V dpos = pos.sub(posBefore);
        log.append(String.format(Locale.ROOT, "t=%3d %s card=%-5s tilt=%7.3f twist=%8.3f yaw=%9.4f pos=%s dpos=%s vW=%s lookW=%s%n      flags=%s%n",
            tick, capsuleBefore ? "CAP" : "BOX", currGravityDirection, Math.toDegrees(angleBetweenV(getUpVector(), new V(0, 1, 0))), twistBeforeMove, yRot, pos, dpos, worldVelAfterLastTravel, worldLookAfterLastTravel, flags));
    }
    static V parseVec(String s) { String[] p = s.replace("(", "").replace(")", "").split(","); return new V(Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2])); }

    void init(String face, boolean residual) {
        V n; Dir g; V start;
        switch (face) {
            case "west": n = new V(-1, 0, 0); start = new V(CMIN - SKIN, 1.0, 0.5); break;
            case "east": n = new V(1, 0, 0); start = new V(CMAX + SKIN, 1.0, 0.5); break;
            case "north": n = new V(0, 0, -1); start = new V(0.5, 1.0, CMIN - SKIN); break;
            default: n = new V(0, 0, 1); start = new V(0.5, 1.0, CMAX + SKIN); break;
        }
        g = Dir.getNearest(-n.x, -n.y, -n.z);
        tag = face + (residual ? "+residual" : "");
        visualRotation.set(canonical(g));
        if (residual) {
            V axisW = (Math.abs(n.x) > 0.5) ? new V(0, 0, 1) : new V(1, 0, 0);
            Quaternionf tilt = new Quaternionf().fromAxisAngleRad(new Vector3f((float) axisW.x, (float) axisW.y, (float) axisW.z), (float) Math.toRadians(0.1));
            visualRotation.mul(new Quaternionf(tilt).conjugate()).normalize();
            visualRotation.premul(new Quaternionf().rotationY((float) Math.toRadians(0.5))).normalize();
        }
        prevVisualRotation.set(visualRotation); visualTarget.set(visualRotation); lastChaseTarget.set(visualRotation);
        currGravityDirection = g; prevGravityDirection = g;
        lastGroundNormal = n; groundNormalGraceTicks = GROUND_NORMAL_GRACE_TICKS; fieldGraceTicks = FIELD_GRACE_TICKS;
        pos = start; old = start; updateBoundingBox();
        capsuleGrounded = true; capsuleGroundNormal = n; capsuleFrameGroundNormal = n; onGround = true;
        yRot = 180.0f; yRotO = yRot; // local yaw 180 = world +Y (up the face) under all four side-face canonical tables
        log.append(String.format(Locale.ROOT, "== %s: gravity=%s frame=%s up=%s initial world look=%s%n", tag, g, visualRotation, getUpVector(), worldLook()));
    }

    public static void main(String[] args) {
        FIX_PUSHOUT_GATE = args.length > 0 && args[0].equals("fix");
        StringBuilder sb = new StringBuilder();
        for (Dir d : Dir.values()) {
            Quaternionf q = canonical(d);
            for (V b : new V[]{new V(1, 0, 0), new V(0, 1, 0), new V(0, 0, 1)}) {
                V viaTable = w2p(b, d), viaQuat = rotate(b, q);
                V viaTableP = p2w(b, d), viaQuatP = rotate(b, new Quaternionf(q).conjugate());
                double e1 = viaTable.sub(viaQuat).length(), e2 = viaTableP.sub(viaQuatP).length();
                if (e1 > 1e-6 || e2 > 1e-6) sb.append(String.format(Locale.ROOT, "TABLE/QUAT MISMATCH %s basis %s: w2p table %s quat %s | p2w table %s quat %s%n", d, b, viaTable, viaQuat, viaTableP, viaQuatP));
            }
            sb.append(String.format(Locale.ROOT, "canonical %s = %s%n", d, q));
        }
        System.out.print(sb);
        for (String face : new String[]{"west", "east", "north", "south"}) {
            V n = face.equals("west") ? new V(-1,0,0) : face.equals("east") ? new V(1,0,0) : face.equals("north") ? new V(0,0,-1) : new V(0,0,1);
            Dir g = Dir.getNearest(-n.x, -n.y, -n.z);
            Quaternionf f = new Quaternionf(canonical(g));
            Quaternionf delta = getRotationBetween(n, new V(0, 1, 0), null);
            Quaternionf t = new Quaternionf(f).mul(delta.conjugate()).normalize();
            System.out.println(String.format(Locale.ROOT, "holonomy %s face (gravity %s): transported frame twist about Y = %.4f deg", face, g, twistAboutY(t)));
        }
        for (boolean residual : new boolean[]{false, true}) {
            for (String face : new String[]{"west", "east", "north", "south"}) {
                GuSim s = new GuSim();
                s.init(face, residual);
                for (int i = 0; i < 10; i++) s.step();
                s.input = true;
                int anchorTick = -1; boolean unwound = false;
                for (int i = 0; i < 120; i++) {
                    s.step();
                    if (s.flags.stream().anyMatch(fl -> fl.equals("UNWIND"))) unwound = true;
                    if (unwound && anchorTick < 0 && s.flags.stream().anyMatch(fl -> fl.equals("ANCHOR"))) anchorTick = s.tick;
                    if (anchorTick > 0 && s.tick > anchorTick + 12) break;
                }
                System.out.print(s.log);
                System.out.println(String.format(Locale.ROOT, "-- anomalies for %s (fixGate=%b): %d | max|dV| across GU tick (non-facechange)=%.3e  max|dLook| (no chase)=%.3e  max look residual vs tilt (chase ticks)=%.3e", s.tag, FIX_PUSHOUT_GATE, s.anomalies.size(), s.maxDv, s.maxDl, s.maxDlChase));
                for (String a : s.anomalies) System.out.println("   " + a);
                System.out.println();
            }
        }
    }
}
