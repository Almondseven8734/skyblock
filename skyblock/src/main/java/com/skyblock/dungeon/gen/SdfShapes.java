package com.skyblock.dungeon.gen;

/**
 * Signed-distance-field based room/tunnel shape evaluation, ported
 * from an earlier standalone prototype's sdfShapes.js.
 *
 * This is the actual generation-shape fix requested: the previous
 * carver treated every room as a flat floor + a plain domed ceiling
 * (Perlin cave noise thresholded against a fixed cutoff). This module
 * instead evaluates each room as an irregular "bubbly" blob - the
 * radius in every direction (both horizontal angle AND vertically) is
 * perturbed by layered sine-wave angular noise plus a few random
 * "lobes" (see irregularRoomSDF) so a room reads as an organic
 * cavern-blob with no flat floor anywhere, rather than a dome sitting
 * on a flat disc. Tunnels (tunnelArchSDF) are true arches whose width
 * scales with the connecting rooms' size instead of a fixed worm
 * diameter, per the "tunnels should almost match room sizes" request.
 *
 * Pure math, no Bukkit dependency - DungeonRoomPlanner is the only
 * caller, and owns translating these boolean/SDF results into actual
 * block placement.
 */
public final class SdfShapes {

    private SdfShapes() {}

    // ─── Hash / noise helpers ────────────────────────────────────────────────

    private static double hash2(int x, int z) {
        int h = (x * 1619) ^ (z * 31337);
        h ^= h >>> 13;
        h = (int) (Integer.toUnsignedLong(h) * 0x9e3779b9L & 0xFFFFFFFFL);
        h ^= h >>> 15;
        return Integer.toUnsignedLong(h) / (double) 0xFFFFFFFFL; // unsigned [0,1)
    }

    private static double smoothNoise2(double x, double z, double scale) {
        int ix = (int) Math.floor(x / scale), iz = (int) Math.floor(z / scale);
        double fx = (x / scale) - ix, fz = (z / scale) - iz;
        double ux = fx * fx * (3 - 2 * fx), uz = fz * fz * (3 - 2 * fz);
        double n00 = hash2(ix, iz) * 2 - 1;
        double n10 = hash2(ix + 1, iz) * 2 - 1;
        double n01 = hash2(ix, iz + 1) * 2 - 1;
        double n11 = hash2(ix + 1, iz + 1) * 2 - 1;
        return n00 * (1 - ux) * (1 - uz) + n10 * ux * (1 - uz) + n01 * (1 - ux) * uz + n11 * ux * uz;
    }

    // ─── Room SDF: irregular bubbly blob ─────────────────────────────────────

    /**
     * Signed distance from point (px,py,pz) to the surface of an
     * irregular room blob centered at (cx, floorY, cz), returning <= 0
     * when the point is inside (carve to air).
     *
     * Unlike a plain ellipsoid/dome, the effective XZ radius at every
     * angle is perturbed by:
     *   - 4 layered sine harmonics (angNoise) for a smooth wavy outline
     *   - 3 random "lobes" (Gaussian bumps in angle) for occasional
     *     bulges/alcoves, so rooms don't all read as slightly-wobbly
     *     circles
     * and the effective vertical radius (effDomeH) is independently
     * perturbed by 2D value noise sampled in the XZ plane - so the
     * blob's "roof/floor" height also varies with position, meaning
     * there is no flat floor plane anywhere: the bottom of the blob
     * curves the same way the top does, just like the rest of an
     * organic cavern.
     *
     * py < floorY is NOT specially rejected here (unlike the old
     * carver's "always solid below the floor plane") - the ellipsoid
     * distance check below already naturally closes the shape at its
     * bottom, curved, the same way it closes at the top.
     */
    public static double irregularRoomSDF(int px, int py, int pz,
                                           double cx, double floorY, double cz,
                                           double rx, double domeH, double rz, int ns) {
        double dx = px - cx, dz = pz - cz;
        double angle = Math.atan2(dz, dx);
        double dist2D = Math.sqrt(dx * dx + dz * dz);

        double p1 = ((ns) & 0xff) / 255.0 * Math.PI * 2;
        double p2 = ((ns >> 8) & 0xff) / 255.0 * Math.PI * 2;
        double p3 = ((ns >> 16) & 0xff) / 255.0 * Math.PI * 2;
        double p4 = ((ns >> 24) & 0x7f) / 127.0 * Math.PI * 2;
        double angNoise = 0.12 * Math.sin(2 * angle + p1)
                + 0.09 * Math.sin(3 * angle + p2)
                + 0.07 * Math.sin(5 * angle + p3)
                + 0.05 * Math.sin(7 * angle + p4);

        double lobePush = 0;
        for (int i = 0; i < 3; i++) {
            double lobeAng = hash2(ns + i * 7, i * 13 + 1) * Math.PI * 2;
            double lobeAmp = 0.15 + hash2(ns + i * 3, i * 17 + 2) * 0.25;
            double lobeWidth = 0.6 + hash2(ns + i * 5, i * 11 + 3) * 0.8;
            double diff = angle - lobeAng;
            double dAng = diff - Math.round(diff / (Math.PI * 2)) * Math.PI * 2;
            lobePush += lobeAmp * Math.exp(-dAng * dAng / (lobeWidth * lobeWidth));
        }

        double effRx = rx * (1 + angNoise + lobePush);
        double effRz = rz * (1 + angNoise + lobePush);
        double angCos = Math.cos(angle), angSin = Math.sin(angle);
        double effR = 1.0 / Math.sqrt((angSin / effRx) * (angSin / effRx) + (angCos / effRz) * (angCos / effRz));
        double normXZ = dist2D / effR;

        // Vertical radius noise, sampled in the XZ plane so it's smooth
        // and continuous as a column moves - this is what curves the
        // bottom of the blob instead of leaving a flat floor.
        double heightNoise = smoothNoise2(px * 0.4, pz * 0.4, 3.0) * 0.25;
        double effDomeH = domeH * (1.0 + heightNoise);
        double dy = (py - floorY) / effDomeH;
        return Math.sqrt(normXZ * normXZ + dy * dy) - 1.0;
    }

    // ─── Tunnel SDF: arched passage between two points ───────────────────────

    private static final double JUNCTION_FLAT = 4.0;

    /**
     * How far below the connecting rooms' floor plane the tunnel's
     * centerline is allowed to dip at its deepest (dxz == 0), in
     * blocks. Matches the "belly" feel of irregularRoomSDF's curved
     * bottom instead of the old flat py < floorY cutoff.
     */
    private static final double TUNNEL_FLOOR_BOWL_DEPTH = 2.5;

    /**
     * True if (px,py,pz) falls inside the arched tunnel passage running
     * from (ax,ay,az) to (bx,by,bz) with half-width r. The passage's
     * cross-section floor is a shallow, curved bowl - lowest along the
     * tunnel's centerline and rising back up to meet the base floor
     * plane at the tunnel walls (dxz == halfW) - so it reads as
     * continuous with a room's curved bottom (see irregularRoomSDF)
     * instead of the old flat plane. A short flattened "junction"
     * landing still applies at each end so the bowl doesn't undercut
     * the room floors it connects to. The ceiling is unchanged: a
     * cosine-profile arch whose peak height scales with the tunnel's
     * own radius, so a wide tunnel (carved with a radius close to its
     * connected rooms' size, per design) gets a correspondingly tall
     * arch instead of a fixed-height crawlspace.
     */
    public static boolean tunnelArchSDF(int px, int py, int pz,
                                         double ax, double ay, double az,
                                         double bx, double by, double bz, double r) {
        double halfW = Math.max(r, 1.0);
        double abx = bx - ax, abz = bz - az;
        double ab2 = abx * abx + abz * abz;
        double segLen = Math.sqrt(ab2);
        double ext = ab2 == 0 ? 0 : (halfW + 2) / segLen;
        double tRaw = ab2 == 0 ? 0 : ((px - ax) * abx + (pz - az) * abz) / ab2;
        double tExt = Math.max(-ext, Math.min(1 + ext, tRaw));
        double tClamped = Math.max(0, Math.min(1, tRaw));
        double cx2 = ax + tExt * abx, cz2 = az + tExt * abz;
        double dxz = Math.sqrt((px - cx2) * (px - cx2) + (pz - cz2) * (pz - cz2));
        if (dxz > halfW) return false;

        double baseFloorY;
        if (segLen < 1) {
            baseFloorY = Math.min(ay, by);
        } else {
            double flatFrac = JUNCTION_FLAT / segLen;
            double tFloor = tClamped < flatFrac ? 0 : tClamped > 1 - flatFrac ? 1 : tClamped;
            baseFloorY = Math.floor(ay + tFloor * (by - ay));
        }

        // Curved bowl floor: deepest at the centerline (dxz = 0),
        // easing back up to baseFloorY at the walls (dxz = halfW), so
        // the tunnel floor matches the belly of the rooms it connects
        // instead of cutting off flat.
        double bowlFrac = 1.0 - Math.min(1.0, dxz / halfW); // 1 at center, 0 at wall
        double bowlNoise = smoothNoise2(px * 0.5, pz * 0.5, 4.0) * 0.3;
        double bowlDepth = TUNNEL_FLOOR_BOWL_DEPTH * (1.0 + bowlNoise) * bowlFrac * bowlFrac;
        double floorY = baseFloorY - bowlDepth;

        if (py < floorY) return false;

        double cosProfile = Math.cos((dxz / halfW) * (Math.PI / 2));
        double maxH = Math.max(halfW * 1.8, 4.0);
        double ceilNoise = smoothNoise2(px * 0.5, pz * 0.5, 4.0) * 0.3;
        double height = (3.0 + (maxH - 3.0) * cosProfile) * (1.0 + ceilNoise);
        return (py <= floorY + height);
    }
}
