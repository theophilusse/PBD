package pbd.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * "Free-flying" camera: WASD moves in the horizontal plane, independent of
 * the look direction (no "diving" by looking down while moving forward);
 * Space/Shift move vertically; the mouse drives yaw/pitch, no roll (not
 * requested). Movement is in units/second - multiply by the frame's delta
 * time on the caller's side to stay frame-rate independent.
 */
public final class FlyCamera {

    public final Vector3f position = new Vector3f(4, 2, 6);
    public float yaw = (float) Math.toRadians(-135);   // looks toward the origin at startup
    public float pitch = (float) Math.toRadians(-15);

    public float moveSpeed = 5.0f;         // units/second
    public float mouseSensitivity = 0.0025f;
    public float fovyDegrees = 70f;
    public float nearPlane = 0.05f;
    public float farPlane = 300f;

    private static final float PITCH_LIMIT = (float) Math.toRadians(89.0);
    private static final float TAU = (float) (Math.PI * 2.0);

    /** Look direction (includes pitch) - used for viewProj, not for WASD movement. */
    public Vector3f forward() {
        float fx = (float) (Math.cos(pitch) * Math.sin(yaw));
        float fy = (float) Math.sin(pitch);
        float fz = (float) (-Math.cos(pitch) * Math.cos(yaw));
        float len = (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
        return new Vector3f(fx / len, fy / len, fz / len);
    }

    /** Call with the mouse movement (pixels) since the last frame. */
    public void look(float dx, float dy) {
        yaw += dx * mouseSensitivity;
        yaw = ((yaw % TAU) + TAU) % TAU; // keeps yaw bounded, purely cosmetic
        pitch -= dy * mouseSensitivity;
        if (pitch > PITCH_LIMIT) pitch = PITCH_LIMIT;
        if (pitch < -PITCH_LIMIT) pitch = -PITCH_LIMIT;
    }

    /** Each flag = key currently held down; deltaSeconds = time elapsed since the last frame. */
    public void move(boolean forwardKey, boolean backKey, boolean leftKey, boolean rightKey,
                      boolean upKey, boolean downKey, float deltaSeconds) {
        float sinYaw = (float) Math.sin(yaw);
        float cosYaw = (float) Math.cos(yaw);
        // Horizontal forward/right (in the XZ plane, independent of pitch) - already unit length.
        float fhx = sinYaw, fhz = -cosYaw;
        float rhx = cosYaw, rhz = sinYaw;
        float dist = moveSpeed * deltaSeconds;

        float dx = 0, dy = 0, dz = 0;
        if (forwardKey) { dx += fhx * dist; dz += fhz * dist; }
        if (backKey)    { dx -= fhx * dist; dz -= fhz * dist; }
        if (rightKey)   { dx += rhx * dist; dz += rhz * dist; }
        if (leftKey)    { dx -= rhx * dist; dz -= rhz * dist; }
        if (upKey)   dy += dist;
        if (downKey) dy -= dist;

        position.x += dx;
        position.y += dy;
        position.z += dz;
    }

    public Matrix4f viewProj(float aspectRatio) {
        Vector3f f = forward();
        Vector3f target = new Vector3f(position.x + f.x, position.y + f.y, position.z + f.z);
        return new Matrix4f()
            .perspective((float) Math.toRadians(fovyDegrees), aspectRatio, nearPlane, farPlane)
            .lookAt(position, target, new Vector3f(0, 1, 0));
    }
}
