package pbd.classicmesh;

import org.joml.Vector3f;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal Wavefront OBJ reader: positions, normals, and triangulated
 * faces. No materials/texture coordinates/groups - this module exists
 * purely to give the benchmark a classic, pre-tessellated mesh to compare
 * the PBD pipeline against, not to be a general-purpose OBJ importer.
 *
 * OBJ faces reference position/texcoord/normal independently (e.g.
 * `f 1/1/1 2/2/1 3/3/2`), so a single output vertex is really a unique
 * (v, vt, vn) combination - the same position can legitimately need two
 * different output vertices if it is used with two different normals
 * across faces (hard edges). This is handled by merging on the raw face
 * token itself rather than on the position index alone.
 */
public final class ObjParser {

    public static ObjMesh parse(Path path) throws IOException {
        List<Vector3f> positions = new ArrayList<>();
        List<Vector3f> normals = new ArrayList<>();
        List<Float> vertexData = new ArrayList<>();
        List<Integer> indices = new ArrayList<>();
        Map<String, Integer> merged = new HashMap<>();

        for (String rawLine : Files.readAllLines(path)) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] tok = line.split("\\s+");

            switch (tok[0]) {
                case "v" -> positions.add(new Vector3f(
                    Float.parseFloat(tok[1]), Float.parseFloat(tok[2]), Float.parseFloat(tok[3])));
                case "vn" -> normals.add(new Vector3f(
                    Float.parseFloat(tok[1]), Float.parseFloat(tok[2]), Float.parseFloat(tok[3])));
                case "f" -> {
                    int[] faceIndices = new int[tok.length - 1];
                    for (int i = 1; i < tok.length; i++) {
                        faceIndices[i - 1] = resolveVertex(tok[i], positions, normals, vertexData, merged);
                    }
                    // Fan triangulation: works for convex n-gons, which covers
                    // the vast majority of exported OBJ files (quads especially).
                    for (int i = 1; i < faceIndices.length - 1; i++) {
                        indices.add(faceIndices[0]);
                        indices.add(faceIndices[i]);
                        indices.add(faceIndices[i + 1]);
                    }
                }
                default -> {
                    // o, g, usemtl, mtllib, s, vt, ...: ignored on purpose,
                    // see the class-level note on scope.
                }
            }
        }

        // If the file has no vn data at all, fall back to per-vertex
        // averaged face normals so shading still looks reasonable. Files
        // that mix vn on some faces but not others are not specially
        // handled - a deliberate simplification for a benchmark tool.
        if (normals.isEmpty()) {
            computeFlatNormals(vertexData, indices);
        }

        float[] data = new float[vertexData.size()];
        for (int i = 0; i < data.length; i++) data[i] = vertexData.get(i);
        int[] idx = new int[indices.size()];
        for (int i = 0; i < idx.length; i++) idx[i] = indices.get(i);

        return new ObjMesh(data, idx);
    }

    private static int resolveVertex(String token, List<Vector3f> positions, List<Vector3f> normals,
                                      List<Float> vertexData, Map<String, Integer> merged) {
        String[] parts = token.split("/", -1);
        int vi = resolveIndex(parts[0], positions.size());
        Integer ni = (parts.length >= 3 && !parts[2].isEmpty())
            ? resolveIndex(parts[2], normals.size())
            : null;

        // Key built from resolved (canonical, always-positive) indices rather
        // than the raw token text, so "1" and "-3" referring to the same
        // vertex correctly merge instead of producing a duplicate.
        String key = vi + "/" + (ni != null ? ni : "");
        Integer existing = merged.get(key);
        if (existing != null) return existing;

        Vector3f p = positions.get(vi);
        Vector3f n = ni != null ? normals.get(ni) : new Vector3f(0, 1, 0); // placeholder; fixed up by computeFlatNormals if unused

        int newIndex = vertexData.size() / 6;
        vertexData.add(p.x); vertexData.add(p.y); vertexData.add(p.z);
        vertexData.add(n.x); vertexData.add(n.y); vertexData.add(n.z);
        merged.put(key, newIndex);
        return newIndex;
    }

    /** OBJ indices are 1-based; a negative index is relative to the end of the list so far. */
    private static int resolveIndex(String raw, int count) {
        int i = Integer.parseInt(raw);
        return i > 0 ? i - 1 : count + i;
    }

    private static void computeFlatNormals(List<Float> vertexData, List<Integer> indices) {
        int vertexCount = vertexData.size() / 6;
        float[] accum = new float[vertexCount * 3];

        for (int t = 0; t < indices.size(); t += 3) {
            int ia = indices.get(t), ib = indices.get(t + 1), ic = indices.get(t + 2);
            float ax = vertexData.get(ia * 6), ay = vertexData.get(ia * 6 + 1), az = vertexData.get(ia * 6 + 2);
            float bx = vertexData.get(ib * 6), by = vertexData.get(ib * 6 + 1), bz = vertexData.get(ib * 6 + 2);
            float cx = vertexData.get(ic * 6), cy = vertexData.get(ic * 6 + 1), cz = vertexData.get(ic * 6 + 2);

            float ux = bx - ax, uy = by - ay, uz = bz - az;
            float vx = cx - ax, vy = cy - ay, vz = cz - az;
            float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;

            for (int idx : new int[]{ia, ib, ic}) {
                accum[idx * 3] += nx;
                accum[idx * 3 + 1] += ny;
                accum[idx * 3 + 2] += nz;
            }
        }

        for (int i = 0; i < vertexCount; i++) {
            float nx = accum[i * 3], ny = accum[i * 3 + 1], nz = accum[i * 3 + 2];
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len < 1e-8f) { nx = 0; ny = 1; nz = 0; len = 1; }
            vertexData.set(i * 6 + 3, nx / len);
            vertexData.set(i * 6 + 4, ny / len);
            vertexData.set(i * 6 + 5, nz / len);
        }
    }
}
