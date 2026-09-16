package pbd.format;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;

/**
 * Raw, non-procedural mesh geometry - position + normal + UV per
 * vertex, plain triangle index list. This is what the "mesh" primitive
 * type carries: content that doesn't fit the procedural-primitive model
 * (an arbitrary imported shape, say) gets embedded directly as data
 * instead of being described parametrically like every other primitive
 * in this format.
 *
 * Binary layout (before base64 encoding):
 * <pre>
 * int32 vertexCount
 * int32 indexCount
 * per vertex: float32 x,y,z, nx,ny,nz, u,v   (8 floats = 32 bytes)
 * per index:  int32
 * </pre>
 * All little-endian. This is deliberately its own small format rather
 * than reusing pbd.classicmesh.ObjMesh's layout as-is - ObjMesh has no
 * UV slot, and adding one there would ripple into every other caller of
 * that class for a field only this primitive type needs.
 */
public final class PbdMeshData {

    public final float[] positions; // 3 per vertex
    public final float[] normals;   // 3 per vertex
    public final float[] uvs;       // 2 per vertex
    public final int[] indices;

    public PbdMeshData(float[] positions, float[] normals, float[] uvs, int[] indices) {
        this.positions = positions;
        this.normals = normals;
        this.uvs = uvs;
        this.indices = indices;
    }

    public int vertexCount() {
        return positions.length / 3;
    }

    public String toBase64() {
        int vertexCount = vertexCount();
        ByteBuffer buf = ByteBuffer.allocate(4 + 4 + vertexCount * 32 + indices.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(vertexCount);
        buf.putInt(indices.length);
        for (int i = 0; i < vertexCount; i++) {
            buf.putFloat(positions[i * 3]); buf.putFloat(positions[i * 3 + 1]); buf.putFloat(positions[i * 3 + 2]);
            buf.putFloat(normals[i * 3]); buf.putFloat(normals[i * 3 + 1]); buf.putFloat(normals[i * 3 + 2]);
            buf.putFloat(uvs[i * 2]); buf.putFloat(uvs[i * 2 + 1]);
        }
        for (int idx : indices) buf.putInt(idx);
        return Base64.getEncoder().encodeToString(buf.array());
    }

    public static PbdMeshData fromBase64(String base64) {
        byte[] bytes = Base64.getDecoder().decode(base64);
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int vertexCount = buf.getInt();
        int indexCount = buf.getInt();

        float[] positions = new float[vertexCount * 3];
        float[] normals = new float[vertexCount * 3];
        float[] uvs = new float[vertexCount * 2];
        for (int i = 0; i < vertexCount; i++) {
            positions[i * 3] = buf.getFloat(); positions[i * 3 + 1] = buf.getFloat(); positions[i * 3 + 2] = buf.getFloat();
            normals[i * 3] = buf.getFloat(); normals[i * 3 + 1] = buf.getFloat(); normals[i * 3 + 2] = buf.getFloat();
            uvs[i * 2] = buf.getFloat(); uvs[i * 2 + 1] = buf.getFloat();
        }
        int[] indices = new int[indexCount];
        for (int i = 0; i < indexCount; i++) indices[i] = buf.getInt();

        return new PbdMeshData(positions, normals, uvs, indices);
    }

    /** Interleaved position+normal only (no UV) - the layout
     * pbd.classicmesh.ObjMesh/ClassicMeshRenderer already expect, for
     * reusing that existing render path rather than building a second
     * one just for this primitive type. UV is dropped here, not lost -
     * it's still in this object, just not needed by that particular
     * renderer yet (texturing a "mesh" primitive is later work, same as
     * the rest of this project's texture-mapping timeline). */
    public float[] toPositionNormalInterleaved() {
        int vertexCount = vertexCount();
        float[] out = new float[vertexCount * 6];
        for (int i = 0; i < vertexCount; i++) {
            out[i * 6] = positions[i * 3]; out[i * 6 + 1] = positions[i * 3 + 1]; out[i * 6 + 2] = positions[i * 3 + 2];
            out[i * 6 + 3] = normals[i * 3]; out[i * 6 + 4] = normals[i * 3 + 1]; out[i * 6 + 5] = normals[i * 3 + 2];
        }
        return out;
    }

    /** Same as toPositionNormalInterleaved, with each vertex's own u,v
     * appended - pos.x,y,z, nrm.x,y,z, u,v per vertex (8 floats). This
     * is what ClassicMeshRenderer's VBO actually expects now (see its
     * own doc): a "mesh" instance's UVs were being extracted and stored
     * correctly the whole time (see PbdMeshData's own constructor,
     * TextureData round trip) but never actually reaching the GPU -
     * this method existing at all is the fix, not a change to how UVs
     * themselves get computed or read. */
    public float[] toPositionNormalUvInterleaved() {
        int vertexCount = vertexCount();
        float[] out = new float[vertexCount * 8];
        for (int i = 0; i < vertexCount; i++) {
            out[i * 8] = positions[i * 3]; out[i * 8 + 1] = positions[i * 3 + 1]; out[i * 8 + 2] = positions[i * 3 + 2];
            out[i * 8 + 3] = normals[i * 3]; out[i * 8 + 4] = normals[i * 3 + 1]; out[i * 8 + 5] = normals[i * 3 + 2];
            out[i * 8 + 6] = uvs[i * 2]; out[i * 8 + 7] = uvs[i * 2 + 1];
        }
        return out;
    }
}
