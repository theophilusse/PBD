package pbd.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import pbd.format.PbdInstance;
import pbd.format.PbdScene;

import java.util.Arrays;

/**
 * Notices when an enabled light has MOVED since the lights buffer was last
 * built, so PbdRenderer can rebuild just that buffer. A light is an
 * ordinary instance - it can be parented to a lever arm or to a keyframed
 * door, and then it travels with it (a lamp hung on a swinging door, a
 * flashlight on a drawer) - but the lights buffer used to be filled once
 * at load and again only on a light switch, so such a lamp's light stayed
 * behind at the pose the scene loaded in while its mesh moved away.
 *
 * What counts as "moved": the light's world position or the world
 * direction it aims (-Z of its own frame, what a spot cone follows) changed
 * by more than a rounding error. Pure data in, boolean out, no GL - runs
 * under tools/regression like the rest of the lever-arm chain.
 *
 * Remembers WHICH lights were enabled when {@link #remember} ran (the set
 * the buffer was built from), so {@link #switched} can tell when one has
 * since been switched on or off: PbdEngine.SceneHandle.toggleLight and
 * setLightEnabled only flip a flag in the scene, nothing in them reaches the
 * GPU buffer, and until this check existed a switched light changed on
 * screen only if something else happened to rebuild the buffer.
 */
public final class LightTracker {

    /** Positions are in world units (metres-ish) and aims are unit vectors: 1e-5 is far below anything visible and above float noise from re-multiplying the same matrices. */
    private static final float EPSILON = 1e-5f;

    private int[] lights = new int[0];
    private float[] pose = new float[0];   // per remembered light: world position xyz, then world aim xyz

    /** The direction a light shines along in world space: -Z of its own frame (Blender's spot-lamp convention, see properties.py's pbd_light_mode), rotated by its world matrix and normalized. Shared with PbdRenderer.uploadLights so both agree on what "aim" means. */
    public static Vector3f aim(Matrix4f world) {
        return world.transformDirection(new Vector3f(0f, 0f, -1f)).normalize();
    }

    /** Records where every currently enabled light is: call right after the lights buffer was built from {@code world}. */
    public void remember(PbdScene scene, Matrix4f[] world) {
        int[] found = new int[scene.instances.size()];
        int n = 0;
        for (int i = 0; i < scene.instances.size(); i++) {
            PbdInstance inst = scene.instances.get(i);
            if ("light".equals(inst.type) && inst.lightEnabled) found[n++] = i;
        }
        lights = Arrays.copyOf(found, n);
        pose = new float[n * 6];
        for (int k = 0; k < n; k++) writePose(world[lights[k]], pose, k * 6);
    }

    /** Whether any remembered light now sits or aims somewhere other than where remember() saw it. Never changes the snapshot - the caller rebuilds the buffer and calls remember() again. */
    public boolean moved(Matrix4f[] world) {
        if (lights.length == 0) return false;
        float[] now = new float[6];
        for (int k = 0; k < lights.length; k++) {
            if (lights[k] >= world.length) return true; // the scene shrank under us: rebuild
            writePose(world[lights[k]], now, 0);
            for (int j = 0; j < 6; j++) {
                if (Math.abs(now[j] - pose[k * 6 + j]) > EPSILON) return true;
            }
        }
        return false;
    }

    /**
     * Whether the set of enabled lights is no longer the one remember() saw: a light was switched on or off since the buffer was
     * built (by the facade, or by a door it follows - see LightLinks), or the scene gained or lost one. Never changes the
     * snapshot - the caller rebuilds the buffer and calls remember() again.
     */
    public boolean switched(PbdScene scene) {
        int n = 0; // how many enabled lights so far; they come in scene order, like the remembered ones
        for (int i = 0; i < scene.instances.size(); i++) {
            PbdInstance inst = scene.instances.get(i);
            if (!"light".equals(inst.type) || !inst.lightEnabled) continue;
            if (n >= lights.length || lights[n] != i) return true;
            n++;
        }
        return n != lights.length;
    }

    /** How many enabled lights are being tracked. */
    public int trackedCount() { return lights.length; }

    private static void writePose(Matrix4f world, float[] out, int at) {
        Vector3f position = world.getTranslation(new Vector3f());
        Vector3f aim = aim(world);
        out[at] = position.x; out[at + 1] = position.y; out[at + 2] = position.z;
        out[at + 3] = aim.x;  out[at + 4] = aim.y;      out[at + 5] = aim.z;
    }
}
