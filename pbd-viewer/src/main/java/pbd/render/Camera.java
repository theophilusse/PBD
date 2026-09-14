package pbd.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Camera minimale : position/cible/up + projection perspective. Suffisante
 * pour un premier viewer, pas de controles interactifs pour l'instant.
 */
public final class Camera {

    public final Vector3f position = new Vector3f(3, 3, 6);
    public final Vector3f target = new Vector3f(0, 0, 0);
    public final Vector3f up = new Vector3f(0, 1, 0);

    public float fovyDegrees = 60f;
    public float nearPlane = 0.1f;
    public float farPlane = 200f;

    public Matrix4f viewProj(float aspectRatio) {
        return new Matrix4f()
            .perspective((float) Math.toRadians(fovyDegrees), aspectRatio, nearPlane, farPlane)
            .lookAt(position, target, up);
    }
}
