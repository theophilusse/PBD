package pbd.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import pbd.format.ModifierRegistry;
import pbd.format.PbdParser;
import pbd.format.PbdScene;
import pbd.format.PrimitiveRegistry;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HierarchyResolverTest {

    private PbdParser newParser() {
        return new PbdParser(new PrimitiveRegistry(), new ModifierRegistry());
    }

    @Test
    void propagatesTranslationDownAChainOfParents() throws Exception {
        PbdScene scene = newParser().parseFile(Path.of("src/main/resources/scenes/rigged_arm.pbd"));
        Matrix4f[] world = new HierarchyResolver().resolve(scene);

        int shoulderIdx = indexOf(scene, "shoulder");
        int upperarmIdx = indexOf(scene, "upperarm");
        int forearmIdx = indexOf(scene, "forearm");

        Vector3f shoulderPos = world[shoulderIdx].getTranslation(new Vector3f());
        Vector3f upperarmPos = world[upperarmIdx].getTranslation(new Vector3f());
        Vector3f forearmPos = world[forearmIdx].getTranslation(new Vector3f());

        assertEquals(1.4f, shoulderPos.y, 1e-4f);
        assertEquals(1.1f, upperarmPos.y, 1e-4f);  // 1.4 - 0.3
        assertEquals(0.7f, forearmPos.y, 1e-4f);   // 1.4 - 0.3 - 0.4
    }

    @Test
    void rootInstancesDoNotInfluenceEachOther() {
        PbdScene scene = newParser().parse("""
            cube a { pos=(5,0,0) scale=(1,1,1) mat=x }
            cube b { pos=(0,5,0) scale=(1,1,1) mat=x }
            """);
        Matrix4f[] world = new HierarchyResolver().resolve(scene);

        Vector3f aPos = world[indexOf(scene, "a")].getTranslation(new Vector3f());
        Vector3f bPos = world[indexOf(scene, "b")].getTranslation(new Vector3f());

        assertEquals(5f, aPos.x, 1e-4f);
        assertEquals(5f, bPos.y, 1e-4f);
    }

    private static int indexOf(PbdScene scene, String id) {
        for (int i = 0; i < scene.instances.size(); i++) {
            if (scene.instances.get(i).id.equals(id)) return i;
        }
        throw new AssertionError("id not found: " + id);
    }
}
