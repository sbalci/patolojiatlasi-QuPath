package com.patolojiatlasi.qupath.pathologycot;

import static org.junit.jupiter.api.Assertions.*;
import java.io.File; import java.nio.file.Files; import java.util.*;
import org.junit.jupiter.api.*; import org.junit.jupiter.api.io.TempDir;

class PathologyCotIOTest {
    @TempDir File dir;

    private File writeFragment() throws Exception {
        String json = """
          {"schema":"atlas-focus-contribution/5","slideKey":"https://x/y.dzi","sessionId":"abcd1234",
           "imageWidth":100000,"imageHeight":80000,"baseMagnification":40.0,
           "path":[[0,5000,5000,4096,4096,4000,-1,-1],[250,5000,5000,4096,4096,4000,-1,-1],
                   [500,5000,5000,4096,4096,4000,-1,-1],[1500,5000,5000,4096,4096,4000,-1,-1]],
           "decision":{"diagnosis":"benign","confidence":4,"decisionMs":9000,"promptShownMs":8000}}""";
        File f = new File(dir, "frag.json"); Files.writeString(f.toPath(), json); return f;
    }

    @Test void readsFragmentFields() throws Exception {
        CotFragment fr = PathologyCotIO.readFragment(writeFragment());
        assertEquals("https://x/y.dzi", fr.slideKey());
        assertEquals(40.0, fr.baseMagnification());
        assertEquals(4, fr.path().size());
        assertEquals(6, fr.path().get(0).length >= 6 ? 6 : fr.path().get(0).length); // has zoom
        assertEquals("benign", fr.decision().get("diagnosis"));
    }

    @Test void writesBehaviorsJson() throws Exception {
        CotFragment fr = PathologyCotIO.readFragment(writeFragment());
        List<Behavior> b = PathologyCotDiscretizer.discretize(fr.path(), fr.baseMagnification(),
                fr.imageWidth(), fr.imageHeight());
        File out = new File(dir, "behaviors.json");
        PathologyCotIO.writeBehaviors(out, fr, b);
        String s = Files.readString(out.toPath());
        assertTrue(s.contains("\"schema\": \"pathology-cot-behaviors/1\""));
        assertTrue(s.contains("\"behaviors\""));
        assertTrue(s.contains("\"type\""));
        assertTrue(s.contains("\"confidence\": 4"), s); // decision map re-serializes as plain values, not JsonElements
    }

    @Test void slideMatchAnonymizesLikeRecorder() throws Exception {
        CotFragment http = PathologyCotIO.readFragment(writeFragment());
        assertTrue(PathologyCotIO.slideMatches(http, "https://x/y.dzi?mpp=0.25")); // query stripped
        assertFalse(PathologyCotIO.slideMatches(http, "https://x/OTHER.dzi"));
    }
}
