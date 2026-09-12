package pbd.fbx;

import pbd.classicmesh.ObjMesh;

import java.util.List;

/**
 * Turns a parsed FBX node tree (see FbxBinaryReader) into an ObjMesh -
 * reusing the existing classic-mesh rendering path rather than building
 * a new one, since position+normal interleaved data is exactly what
 * this already renders.
 *
 * Handles exactly what BeerBottle.FBX (the one real file this was
 * developed and verified against) actually contains: a single Geometry
 * with all-triangle polygons, normals mapped ByPolygonVertex+Direct, and
 * a Model node whose GeometricRotation/LclScaling need applying to the
 * raw vertex data to get a correctly oriented, reasonably scaled result.
 * UV data (present in the file, ByPolygonVertex+IndexToDirect) is read
 * but not carried into the ObjMesh yet - texturing is explicitly later
 * work (see this project's roadmap), and ObjMesh itself has no UV slot
 * to put it in without extending that class too.
 *
 * What ISN'T handled, because no real example has exercised it yet:
 * n-gon polygons (only triangles seen so far - would need fan
 * triangulation), multiple Geometry nodes in one file (takes the first,
 * warns about the rest), normals/UVs mapped ByVertex or with a Direct
 * UV reference instead of IndexToDirect, skinning/bones, and the full
 * FBX node transform stack (RotationOffset/RotationPivot/PostRotation
 * etc. are all ignored - only PreRotation+LclRotation, which cancel out
 * in the one file this was checked against, and GeometricRotation/
 * GeometricScaling/GeometricTranslation, which are the ones that
 * actually affect raw vertex data, are read at all).
 */
public final class FbxGeometryExtractor {

    public static final class ExtractionResult {
        public final ObjMesh mesh;
        public final float[] boundingBoxMin; // xyz, in the same units/orientation as mesh
        public final float[] boundingBoxMax;
        public final List<String> warnings;

        ExtractionResult(ObjMesh mesh, float[] min, float[] max, List<String> warnings) {
            this.mesh = mesh;
            this.boundingBoxMin = min;
            this.boundingBoxMax = max;
            this.warnings = warnings;
        }
    }

    public ExtractionResult extract(List<FbxBinaryReader.Node> topLevelNodes) {
        List<String> warnings = new java.util.ArrayList<>();

        FbxBinaryReader.Node objects = topLevelNodes.stream()
            .filter(n -> n.name.equals("Objects")).findFirst()
            .orElseThrow(() -> new FbxBinaryReader.ParseException("No 'Objects' node found - not a valid FBX scene file"));

        List<FbxBinaryReader.Node> geometries = objects.findAll("Geometry");
        if (geometries.isEmpty()) {
            throw new FbxBinaryReader.ParseException("No 'Geometry' node found under Objects - file has no mesh data");
        }
        if (geometries.size() > 1) {
            warnings.add("File has " + geometries.size() + " Geometry nodes - only the first is loaded, "
                + "the rest are ignored (multi-mesh FBX files aren't handled yet)");
        }
        FbxBinaryReader.Node geometry = geometries.get(0);

        double[] rawVertices = (double[]) requireChild(geometry, "Vertices").properties.get(0);
        int[] polygonVertexIndex = (int[]) requireChild(geometry, "PolygonVertexIndex").properties.get(0);

        float[] normalsByPolyVertex = readNormals(geometry, warnings);

        // GeometricRotation/GeometricScaling/GeometricTranslation: FBX's
        // own mechanism for baking a transform into mesh data only,
        // without affecting the node's own position/rotation/scale in
        // the wider scene hierarchy - found on the Model node that owns
        // this Geometry, not the Geometry node itself. See this class's
        // own doc comment for why only these three (not the full
        // pivot/offset/post-rotation stack) are read.
        FbxBinaryReader.Node model = findOwningModel(topLevelNodes, geometry, warnings);
        double[] geomRotationDeg = {0, 0, 0};
        double[] geomScale = {1, 1, 1};
        double[] geomTranslate = {0, 0, 0};
        double[] lclScale = {1, 1, 1};
        if (model != null) {
            geomRotationDeg = readVector3Property(model, "GeometricRotation", geomRotationDeg);
            geomScale = readVector3Property(model, "GeometricScaling", geomScale);
            geomTranslate = readVector3Property(model, "GeometricTranslation", geomTranslate);
            lclScale = readVector3Property(model, "Lcl Scaling", lclScale);
        }

        int vertexCount = rawVertices.length / 3;
        float[][] transformedVerts = new float[vertexCount][3];
        for (int i = 0; i < vertexCount; i++) {
            double x = rawVertices[i * 3] * geomScale[0] + geomTranslate[0];
            double y = rawVertices[i * 3 + 1] * geomScale[1] + geomTranslate[1];
            double z = rawVertices[i * 3 + 2] * geomScale[2] + geomTranslate[2];
            double[] rotated = rotateXYZDegrees(x, y, z, geomRotationDeg);
            transformedVerts[i][0] = (float) (rotated[0] * lclScale[0]);
            transformedVerts[i][1] = (float) (rotated[1] * lclScale[1]);
            transformedVerts[i][2] = (float) (rotated[2] * lclScale[2]);
        }

        // Unpacks PolygonVertexIndex into individual triangles - every
        // real polygon seen so far is already a triangle (3 entries
        // between end-of-polygon markers), so this reads 3 at a time
        // rather than fan-triangulating an arbitrary n-gon.
        List<Float> vertexData = new java.util.ArrayList<>();
        List<Integer> indices = new java.util.ArrayList<>();
        int polyVertexCursor = 0;
        int triangleVertsInCurrentPoly = 0;
        int emittedVertexCount = 0;
        for (int i = 0; i < polygonVertexIndex.length; i++) {
            int raw = polygonVertexIndex[i];
            boolean isLastInPolygon = raw < 0;
            int vertIndex = isLastInPolygon ? (-raw - 1) : raw;

            float[] pos = transformedVerts[vertIndex];
            int normalBase = polyVertexCursor * 3;
            float nx = normalsByPolyVertex != null ? normalsByPolyVertex[normalBase] : 0f;
            float ny = normalsByPolyVertex != null ? normalsByPolyVertex[normalBase + 1] : 1f;
            float nz = normalsByPolyVertex != null ? normalsByPolyVertex[normalBase + 2] : 0f;
            // Normals also live in the mesh's own raw space - same
            // rotation as positions, but no translation and no scale
            // (a direction, not a point - scaling would need the
            // inverse-transpose to stay correct under non-uniform
            // scale, not attempted here since every example seen uses
            // uniform scale).
            double[] rotatedNormal = rotateXYZDegrees(nx, ny, nz, geomRotationDeg);

            vertexData.add(pos[0]); vertexData.add(pos[1]); vertexData.add(pos[2]);
            vertexData.add((float) rotatedNormal[0]); vertexData.add((float) rotatedNormal[1]); vertexData.add((float) rotatedNormal[2]);
            indices.add(emittedVertexCount);
            emittedVertexCount++;

            polyVertexCursor++;
            triangleVertsInCurrentPoly++;
            if (isLastInPolygon) {
                if (triangleVertsInCurrentPoly != 3) {
                    throw new FbxBinaryReader.ParseException("Polygon with " + triangleVertsInCurrentPoly
                        + " vertices found (not a triangle) - n-gon triangulation isn't implemented yet, "
                        + "every real file checked during development only had triangles");
                }
                triangleVertsInCurrentPoly = 0;
            }
        }

        float[] vertexArray = toFloatArray(vertexData);
        int[] indexArray = toIntArray(indices);
        ObjMesh mesh = new ObjMesh(vertexArray, indexArray);

        float[] bbMin = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] bbMax = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (float[] v : transformedVerts) {
            for (int a = 0; a < 3; a++) {
                bbMin[a] = Math.min(bbMin[a], v[a]);
                bbMax[a] = Math.max(bbMax[a], v[a]);
            }
        }

        return new ExtractionResult(mesh, bbMin, bbMax, warnings);
    }

    private float[] readNormals(FbxBinaryReader.Node geometry, List<String> warnings) {
        FbxBinaryReader.Node layerNormal = geometry.child("LayerElementNormal");
        if (layerNormal == null) return null;

        String mapping = readStringChild(layerNormal, "MappingInformationType", "");
        String reference = readStringChild(layerNormal, "ReferenceInformationType", "");
        FbxBinaryReader.Node normalsNode = layerNormal.child("Normals");
        if (normalsNode == null) return null;
        double[] normalsRaw = (double[]) normalsNode.properties.get(0);
        float[] normals = new float[normalsRaw.length];
        for (int i = 0; i < normalsRaw.length; i++) normals[i] = (float) normalsRaw[i];

        if (!mapping.equals("ByPolygonVertex")) {
            warnings.add("LayerElementNormal MappingInformationType='" + mapping + "' (expected ByPolygonVertex) - "
                + "normals may be misaligned, this exact variant hasn't been seen in a real file yet");
        }
        if (!reference.equals("Direct")) {
            warnings.add("LayerElementNormal ReferenceInformationType='" + reference + "' (expected Direct) - "
                + "an IndexToDirect-referenced normal array isn't handled, normals may be wrong");
        }
        return normals;
    }

    private FbxBinaryReader.Node findOwningModel(List<FbxBinaryReader.Node> topLevelNodes,
                                                   FbxBinaryReader.Node geometry, List<String> warnings) {
        // The Geometry<->Model link lives in the top-level Connections
        // block (C entries: "OO", childId, parentId), not as direct
        // nesting - matches every real file seen, where Objects holds a
        // flat list of Geometry/Model/Material/etc. nodes side by side,
        // wired together only by these connection records.
        long geometryId = (long) geometry.properties.get(0);
        FbxBinaryReader.Node connections = topLevelNodes.stream()
            .filter(n -> n.name.equals("Connections")).findFirst().orElse(null);
        FbxBinaryReader.Node objects = topLevelNodes.stream()
            .filter(n -> n.name.equals("Objects")).findFirst().orElse(null);
        if (connections == null || objects == null) return null;

        for (FbxBinaryReader.Node c : connections.childrenNamed("C")) {
            if (c.properties.size() >= 3 && "OO".equals(c.properties.get(0))) {
                long childId = (long) c.properties.get(1);
                long parentId = (long) c.properties.get(2);
                if (childId == geometryId) {
                    for (FbxBinaryReader.Node model : objects.childrenNamed("Model")) {
                        if (!model.properties.isEmpty() && (long) model.properties.get(0) == parentId) {
                            return model;
                        }
                    }
                }
            }
        }
        warnings.add("Could not find the Model node owning this Geometry via Connections - "
            + "GeometricRotation/Scaling/Translation and Lcl Scaling will not be applied, mesh may be misoriented or wrong scale");
        return null;
    }

    private double[] readVector3Property(FbxBinaryReader.Node model, String propertyName, double[] fallback) {
        FbxBinaryReader.Node props70 = model.child("Properties70");
        if (props70 == null) return fallback;
        for (FbxBinaryReader.Node p : props70.childrenNamed("P")) {
            if (p.properties.size() >= 7 && propertyName.equals(p.properties.get(0))) {
                // P's properties are: name, type, subtype, flags, then the value(s) -
                // a Vector3D/Lcl Rotation/Lcl Scaling always has exactly 3 double values after that.
                return new double[]{
                    (double) p.properties.get(4),
                    (double) p.properties.get(5),
                    (double) p.properties.get(6)
                };
            }
        }
        return fallback;
    }

    private String readStringChild(FbxBinaryReader.Node parent, String childName, String fallback) {
        FbxBinaryReader.Node child = parent.child(childName);
        if (child == null || child.properties.isEmpty()) return fallback;
        return (String) child.properties.get(0);
    }

    private FbxBinaryReader.Node requireChild(FbxBinaryReader.Node parent, String name) {
        FbxBinaryReader.Node child = parent.child(name);
        if (child == null) {
            throw new FbxBinaryReader.ParseException("Geometry node missing required child '" + name + "'");
        }
        return child;
    }

    /** Intrinsic X then Y then Z (applied in that order to the point) -
     * matches how FBX's own GeometricRotation/PreRotation/Lcl Rotation
     * fields are documented to compose (XYZ order), and gave a
     * correctly-oriented result on the one real file this was checked
     * against - not independently re-derived from scratch under time
     * pressure, given this project's own history of getting a rotation
     * composition order wrong that way (see PbdParser's "rot" field). */
    private double[] rotateXYZDegrees(double x, double y, double z, double[] degXYZ) {
        double[] p = {x, y, z};
        p = rotateAxis(p, 0, Math.toRadians(degXYZ[0]));
        p = rotateAxis(p, 1, Math.toRadians(degXYZ[1]));
        p = rotateAxis(p, 2, Math.toRadians(degXYZ[2]));
        return p;
    }

    private double[] rotateAxis(double[] p, int axis, double rad) {
        if (rad == 0) return p;
        double c = Math.cos(rad), s = Math.sin(rad);
        double x = p[0], y = p[1], z = p[2];
        return switch (axis) {
            case 0 -> new double[]{x, y * c - z * s, y * s + z * c};
            case 1 -> new double[]{x * c + z * s, y, -x * s + z * c};
            case 2 -> new double[]{x * c - y * s, x * s + y * c, z};
            default -> p;
        };
    }

    private float[] toFloatArray(List<Float> list) {
        float[] out = new float[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = list.get(i);
        return out;
    }

    private int[] toIntArray(List<Integer> list) {
        int[] out = new int[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = list.get(i);
        return out;
    }
}
