package com.patolojiatlasi.qupath;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class FavoritesTest {

    private static AtlasCase c(String repo, String dzi) {
        return new AtlasCase(repo, "HE", "HE", "T " + repo, "", "Colon", "", "published", dzi, "", 0.0);
    }

    @Test
    void toggleFlipsMembershipKeyedByStrippedUrl() {
        Favorites fav = Favorites.inMemory();          // test ctor: no disk
        AtlasCase withQ = c("a", "https://x/a/HE.dzi?mpp=0.26");
        AtlasCase bare  = c("a", "https://x/a/HE.dzi");
        assertFalse(fav.contains(withQ));
        assertTrue(fav.toggle(withQ));                 // now favorite
        assertTrue(fav.contains(bare));                // same favorite despite query difference
        assertFalse(fav.toggle(bare));                 // toggles off
        assertFalse(fav.contains(withQ));
    }

    @Test
    void resolveReturnsCatalogueCases() {
        Favorites fav = Favorites.inMemory();
        fav.toggle(c("a", "https://x/a/HE.dzi"));
        List<AtlasCase> catalog = List.of(c("a", "https://x/a/HE.dzi"), c("b", "https://x/b/HE.dzi"));
        List<AtlasCase> res = fav.resolve(catalog);
        assertTrue(res.size() == 1 && res.get(0).getReponame().equals("a"));
    }
}
