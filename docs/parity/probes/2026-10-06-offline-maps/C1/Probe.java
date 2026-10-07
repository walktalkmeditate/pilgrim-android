// SPDX-License-Identifier: GPL-3.0-or-later
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

// JVM side of the C1 probe: the byte layout U43 would write in Kotlin
// (putLong(version), putLong(rounded.toRawBits()) little-endian), the
// Swift-rounding helper, and the corridor maths, against probe.swift's output.
public class Probe {
    static double swiftRounded(double x) {
        double t = x > 0 ? Math.floor(x) : Math.ceil(x); // kotlin.math.truncate
        if (Double.isNaN(x) || Double.isInfinite(x)) return x;
        return Math.abs(x - t) >= 0.5 ? t + Math.signum(x) : t;
    }

    static double houseHelper(double v) { // HonorOverviewModel.roundedHalfAwayFromZero
        return Math.signum(v) * Math.floor(Math.abs(v) + 0.5);
    }

    static String hash(List<double[][]> rings, long version) throws Exception {
        int points = 0;
        for (double[][] r : rings) points += r.length;
        ByteBuffer b = ByteBuffer.allocate(8 + 16 * points).order(ByteOrder.LITTLE_ENDIAN);
        b.putLong(version);
        for (double[][] r : rings) for (double[] p : r) {
            b.putLong(Double.doubleToRawLongBits(swiftRounded(p[0] * 1_000_000)));
            b.putLong(Double.doubleToRawLongBits(swiftRounded(p[1] * 1_000_000)));
        }
        byte[] d = MessageDigest.getInstance("SHA-256").digest(b.array());
        StringBuilder sb = new StringBuilder();
        for (byte x : d) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    static List<double[]> simplified(List<double[]> pts, double tol) {
        if (pts.size() <= 2) return pts;
        double[] first = pts.get(0);
        double latScale = 111_320.0, lonScale = 111_320.0 * Math.cos(first[0] * Math.PI / 180);
        int n = pts.size();
        double[] lx = new double[n], ly = new double[n];
        for (int i = 0; i < n; i++) { lx[i] = (pts.get(i)[1] - first[1]) * lonScale; ly[i] = (pts.get(i)[0] - first[0]) * latScale; }
        boolean[] keep = new boolean[n]; keep[0] = true; keep[n - 1] = true;
        java.util.ArrayDeque<int[]> stack = new java.util.ArrayDeque<>();
        stack.addLast(new int[]{0, n - 1});
        while (!stack.isEmpty()) {
            int[] ab = stack.removeLast(); int a = ab[0], bb = ab[1];
            if (bb - a <= 1) continue;
            double ax = lx[a], ay = ly[a], dx = lx[bb] - ax, dy = ly[bb] - ay, lenSq = dx * dx + dy * dy;
            double far = -1.0; int idx = a;
            for (int i = a + 1; i < bb; i++) {
                double px = lx[i] - ax, py = ly[i] - ay, dist;
                if (lenSq > 0) { double u = Math.max(0, Math.min(1, (px * dx + py * dy) / lenSq)); double cx = px - u * dx, cy = py - u * dy; dist = Math.sqrt(cx * cx + cy * cy); }
                else dist = Math.sqrt(px * px + py * py);
                if (dist > far) { far = dist; idx = i; }
            }
            if (far > tol) { keep[idx] = true; stack.addLast(new int[]{a, idx}); stack.addLast(new int[]{idx, bb}); }
        }
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < n; i++) if (keep[i]) out.add(pts.get(i));
        return out;
    }

    static List<double[][]> corridor(List<double[]> pts, double h) {
        List<double[]> line = simplified(pts, 25);
        List<double[][]> parts = new ArrayList<>();
        if (line.isEmpty()) return parts;
        double[] first = line.get(0);
        double latScale = 111_320.0, lonScale = 111_320.0 * Math.cos(first[0] * Math.PI / 180);
        int n = line.size();
        double[] lx = new double[n], ly = new double[n];
        for (int i = 0; i < n; i++) { lx[i] = (line.get(i)[1] - first[1]) * lonScale; ly[i] = (line.get(i)[0] - first[0]) * latScale; }
        for (int i = 0; i < n; i++) {
            if (i + 1 < n) {
                double dx = lx[i + 1] - lx[i], dy = ly[i + 1] - ly[i];
                double len = Math.sqrt(dx * dx + dy * dy);
                if (len > 0) {
                    dx /= len; dy /= len;
                    double nx = -dy * h, ny = dx * h;
                    double[][] q = {
                        geo(first, lx[i] - nx, ly[i] - ny, latScale, lonScale), geo(first, lx[i + 1] - nx, ly[i + 1] - ny, latScale, lonScale),
                        geo(first, lx[i + 1] + nx, ly[i + 1] + ny, latScale, lonScale), geo(first, lx[i] + nx, ly[i] + ny, latScale, lonScale),
                        geo(first, lx[i] - nx, ly[i] - ny, latScale, lonScale)};
                    parts.add(q);
                }
            }
            double vx = lx[i], vy = ly[i];
            parts.add(new double[][]{
                geo(first, vx - h, vy - h, latScale, lonScale), geo(first, vx + h, vy - h, latScale, lonScale),
                geo(first, vx + h, vy + h, latScale, lonScale), geo(first, vx - h, vy + h, latScale, lonScale),
                geo(first, vx - h, vy - h, latScale, lonScale)});
        }
        return parts;
    }

    static double[] geo(double[] first, double x, double y, double latScale, double lonScale) {
        return new double[]{first[0] + y / latScale, first[1] + x / lonScale};
    }

    public static void main(String[] args) throws Exception {
        List<double[][]> square = new ArrayList<>();
        square.add(new double[][]{{0, 0}, {1, 0}, {1, 1}, {0, 1}, {0, 0}});
        System.out.println("hash(square, v2) = " + hash(square, 2));
        System.out.println("hash(square, v1) = " + hash(square, 1));
        System.out.println("hash([], v2) = " + hash(new ArrayList<>(), 2));
        List<double[][]> half = new ArrayList<>();
        half.add(new double[][]{{0.0000005, -0.0000005}});
        System.out.println("half probe hash = " + hash(half, 2));
        List<double[][]> negZero = new ArrayList<>();
        negZero.add(new double[][]{{-0.0000004, 0.0000004}});
        System.out.println("neg-zero probe hash = " + hash(negZero, 2));
        System.out.println("swiftRounded(-0.4) raw = " + Long.toHexString(Double.doubleToRawLongBits(swiftRounded(-0.4))));
        System.out.println("Math.round(-0.4) as double raw = " + Long.toHexString(Double.doubleToRawLongBits((double) Math.round(-0.4))));
        System.out.println("Math.round(-2.5) = " + Math.round(-2.5) + "  swiftRounded(-2.5) = " + swiftRounded(-2.5) + "  Math.rint(2.5) = " + Math.rint(2.5));
        System.out.println("houseHelper(0.49999999999999994) = " + houseHelper(0.49999999999999994) + "  swiftRounded = " + swiftRounded(0.49999999999999994));

        List<double[]> s0 = new ArrayList<>();
        for (int i = 0; i <= 30; i++) s0.add(new double[]{42, 0 + i * 0.001209});
        List<double[][]> rings0 = corridor(s0, 500);
        System.out.println("stage(0) parts = " + rings0.size());
        for (int i = 0; i < rings0.size(); i++) {
            StringBuilder sb = new StringBuilder(" part " + i + ":");
            for (double[] p : rings0.get(i)) sb.append(String.format(" (%.17g, %.17g)", p[0], p[1]));
            System.out.println(sb);
        }
        System.out.println("hash(stage0 corridor, v2) = " + hash(rings0, 2));
        System.out.println("hash(stage0 corridor, v1) = " + hash(rings0, 1));
    }
}
