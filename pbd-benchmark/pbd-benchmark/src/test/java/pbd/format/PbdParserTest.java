package pbd.format;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PbdParserTest {

    private PbdParser newParser() {
        return new PbdParser(new PrimitiveRegistry(), new ModifierRegistry());
    }

    @Test
    void parsesBasicFields() {
        PbdScene scene = newParser().parse("""
            cylinder pipe01 {
              pos   = (2, 0, 0)
              scale = (0.3, 2.0, 0.3)
              mat   = metal
            }
            """);

        assertEquals(1, scene.instances.size());
        PbdInstance pipe = scene.instances.get(0);
        assertEquals("pipe01", pipe.id);
        assertEquals("cylinder", pipe.type);
        assertEquals(2f, pipe.position.x, 1e-6f);
        assertEquals(0f, pipe.position.y, 1e-6f);
        assertEquals(0f, pipe.position.z, 1e-6f);
        assertEquals(0.3f, pipe.scale.x, 1e-6f);
        assertEquals(2.0f, pipe.scale.y, 1e-6f);
        assertEquals("metal", pipe.material);
    }

    @Test
    void defaultsRotationToIdentityWhenRotIsAbsent() {
        PbdScene scene = newParser().parse("cube c1 { pos=(0,0,0) scale=(1,1,1) mat=x }");
        var q = scene.instances.get(0).rotation;
        assertEquals(0f, q.x, 1e-6f);
        assertEquals(0f, q.y, 1e-6f);
        assertEquals(0f, q.z, 1e-6f);
        assertEquals(1f, q.w, 1e-6f);
    }

    @Test
    void parsesModifiersAttachedToAnInstance() {
        PbdScene scene = newParser().parse("""
            sphere petal01 {
              pos   = (0, 0, 0)
              scale = (0.3, 0.15, 0.6)
              mat   = petal

              modifier bend  { axis=x angle=40 }
              modifier curve { ref=petal_curve01 axis=y }
            }
            """);

        PbdInstance petal = scene.instances.get(0);
        assertEquals(2, petal.modifiers.size());
        assertEquals("bend", petal.modifiers.get(0).type);
        assertEquals(40f, petal.modifiers.get(0).getFloat("angle", -1), 1e-6f);
        assertEquals("petal_curve01", petal.modifiers.get(1).get("ref"));
    }

    @Test
    void parsesCurvePoints() {
        PbdScene scene = newParser().parse("""
            curve petal_curve01 {
              kind   = bezier
              points = [(0,0,0), (0,0.4,0.1), (0,0.8,0.4)]
            }
            """);

        PbdCurve curve = scene.curves.get("petal_curve01");
        assertNotNull(curve);
        assertEquals("bezier", curve.kind);
        assertEquals(3, curve.points.size());
        assertEquals(0.8f, curve.points.get(2).y, 1e-6f);
        assertEquals(0.4f, curve.points.get(2).z, 1e-6f);
    }

    @Test
    void resolvesHierarchyWithParentBeforeChildEvenWhenDeclaredOutOfOrder() {
        PbdScene scene = newParser().parse("""
            cylinder forearm { parent=upperarm pos=(0,-0.4,0) scale=(0.08,0.35,0.08) mat=skin }
            cylinder shoulder { pos=(0,1.4,0) scale=(0.15,0.15,0.15) mat=skin }
            cylinder upperarm { parent=shoulder pos=(0,-0.3,0) scale=(0.1,0.4,0.1) mat=skin }
            """);

        int shoulderIdx = indexOf(scene, "shoulder");
        int upperarmIdx = indexOf(scene, "upperarm");
        int forearmIdx = indexOf(scene, "forearm");

        assertTrue(shoulderIdx < upperarmIdx);
        assertTrue(upperarmIdx < forearmIdx);
        assertEquals(-1, scene.instances.get(shoulderIdx).parentIndex);
        assertEquals(shoulderIdx, scene.instances.get(upperarmIdx).parentIndex);
        assertEquals(upperarmIdx, scene.instances.get(forearmIdx).parentIndex);
    }

    @Test
    void detectsParentCycle() {
        PbdParser parser = newParser();
        assertThrows(IllegalStateException.class, () -> parser.parse("""
            cylinder a { parent=b pos=(0,0,0) scale=(1,1,1) mat=skin }
            cylinder b { parent=a pos=(0,0,0) scale=(1,1,1) mat=skin }
            """));
    }

    @Test
    void rejectsUnknownParentReference() {
        PbdParser parser = newParser();
        assertThrows(IllegalArgumentException.class, () ->
            parser.parse("cylinder a { parent=ghost pos=(0,0,0) scale=(1,1,1) mat=skin }"));
    }

    @Test
    void rejectsUnknownPrimitiveType() {
        PbdParser parser = newParser();
        assertThrows(IllegalStateException.class, () -> parser.parse("spehre oops { pos=(0,0,0) }"));
    }

    @Test
    void parsesAllExampleSceneFiles() throws Exception {
        Path scenesDir = Path.of("src/main/resources/scenes");
        for (String file : new String[]{"basic_primitives.pbd", "flower_petal.pbd", "rigged_arm.pbd"}) {
            PbdScene scene = newParser().parseFile(scenesDir.resolve(file));
            assertFalse(scene.instances.isEmpty() && scene.curves.isEmpty(),
                file + " should produce at least one instance or one curve");
        }
    }

    private static int indexOf(PbdScene scene, String id) {
        for (int i = 0; i < scene.instances.size(); i++) {
            if (scene.instances.get(i).id.equals(id)) return i;
        }
        throw new AssertionError("id not found: " + id);
    }
}
