package pbd.voxel;

import org.joml.Matrix4f;
import pbd.format.PbdMeshData;

import java.util.List;

/**
 * How the particles of a hit get on the screen. Every particle is the SAME cube - one mesh, built once and shared - placed,
 * turned and scaled by its own matrix and tinted by its own colour, so a few hundred chips cost a few hundred matrices and
 * not a few hundred meshes. This class is the GL-free half of that: the cube, and the matrix of a particle. The GL half
 * (PbdRenderer.drawDestroyed) only loops over {@link ParticleSystem#view} and hands each matrix to the shared mesh, so the
 * part that can go wrong without a window - which way is up, which order the turn and the scale are applied in, what a
 * degenerate axis does - is checked under tools/regression (RegressionParticles).
 */
public final class ParticleDraw {

    private ParticleDraw() {}

    /**
     * The cube every particle is drawn with: edge 1, one corner at the origin and the opposite one at (1, 1, 1) - the box a
     * single voxel of edge 1 makes, so it has the same faces, normals and winding as the voxel meshes. {@link #matrix}
     * centres it before turning it, so a particle turns about its own middle.
     */
    public static PbdMeshData cube() {
        return VoxelMeshBuilder.buildMesh(List.of(new int[]{0, 0, 0, 1}), 1f);
    }

    /**
     * The world matrix that turns {@link #cube} into particle {@code v}: the middle of the cube is moved to the origin, the
     * cube is scaled to the particle's edge length, turned by the particle's angle about its axis (any axis length: it is
     * normalised here, and an axis of no length leaves the cube unturned), and moved to the particle's position.
     * {@code dest} is overwritten and returned, so a draw loop can reuse one matrix.
     */
    public static Matrix4f matrix(ParticleSystem.View v, Matrix4f dest) {
        dest.translation((float) v.x, (float) v.y, (float) v.z); // sets the whole matrix, nothing of the last use is left
        double len = Math.sqrt((double) v.axisX * v.axisX + (double) v.axisY * v.axisY + (double) v.axisZ * v.axisZ);
        if (len > 1e-6 && Double.isFinite(len) && Float.isFinite(v.angle)) {
            dest.rotate(v.angle, (float) (v.axisX / len), (float) (v.axisY / len), (float) (v.axisZ / len));
        }
        return dest.scale(v.size).translate(-0.5f, -0.5f, -0.5f);
    }
}
