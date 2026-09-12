package pbd.render;

import org.junit.jupiter.api.Test;
import pbd.format.ModifierRegistry;
import pbd.format.PbdParser;
import pbd.format.PbdScene;
import pbd.format.PrimitiveRegistry;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PatchExpanderTest {

    private PbdParser newParser() {
        return new PbdParser(new PrimitiveRegistry(), new ModifierRegistry());
    }

    @Test
    void countsMatchExpectedPatchesPerPrimitiveType() throws Exception {
        PbdScene scene = newParser().parseFile(Path.of("src/main/resources/scenes/basic_primitives.pbd"));
        List<GpuPatch> patches = new PatchExpander().expand(scene);

        // plane(1) + sphere(1) + cylinder(3) + cone(2) + cube(6) = 13
        assertEquals(13, patches.size());
    }

    @Test
    void everyPatchReferencesItsSourceInstanceAndPart() {
        PbdScene scene = newParser().parse("cylinder pipe01 { pos=(0,0,0) scale=(1,1,1) mat=x }");
        List<GpuPatch> patches = new PatchExpander().expand(scene);

        assertEquals(3, patches.size());
        assertEquals(0, patches.get(0).instanceIndex);
        assertEquals(0, patches.get(0).part);
        assertEquals(1, patches.get(1).part);
        assertEquals(2, patches.get(2).part);
    }
}
