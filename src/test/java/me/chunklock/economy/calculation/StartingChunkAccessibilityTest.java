package me.chunklock.economy.calculation;

import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What makes a chunk usable as a starting chunk (#105).
 *
 * <h3>The gap this covers</h3>
 *
 * <p>Spawn selection scored candidates on difficulty and took a low one. Nothing checked that a
 * player starting there could <em>pay for anything</em>, and those are different properties: the
 * reported icy mountain chunk scored as easy and offered only gravel, stone and ice. All three
 * need a tool, and the biome had no trees to make one from.</p>
 *
 * <p>{@code ChunkEvaluator.hasBareHandAccessibleMaterial} walks a chunk's blocks and asks whether
 * any is both priceable and gatherable with no tool. Walking real blocks needs a live world, so
 * these tests cover the decision that walk makes per material - the part that can be wrong in a
 * way no server would reveal.</p>
 */
class StartingChunkAccessibilityTest {

    /**
     * The reported chunk. Every material in it is tool-gated, so it must be rejected as a
     * starting chunk however easy its difficulty score looks.
     */
    @Test
    @DisplayName("an icy mountain chunk offers no bare-hand material - the #105 start")
    void icyMountainChunkIsRejected() {
        List<Material> icyMountain = List.of(
            Material.GRAVEL, Material.STONE, Material.ICE, Material.SNOW_BLOCK,
            Material.PACKED_ICE, Material.DIORITE);

        assertFalse(icyMountain.stream().anyMatch(StartingChunkAccessibilityTest::qualifies),
            "gravel needs a shovel, stone needs a pickaxe, and ice and snow are not priced "
                + "against at all - nothing here can start a game");
    }

    /**
     * The same chunk with trees. One log is the whole difference, as in the pricing tests.
     */
    @Test
    @DisplayName("the same chunk with a tree is a fine place to start")
    void icyChunkWithTreesIsAccepted() {
        List<Material> withTrees = List.of(
            Material.GRAVEL, Material.STONE, Material.SNOW_BLOCK, Material.SPRUCE_LOG);

        assertTrue(withTrees.stream().anyMatch(StartingChunkAccessibilityTest::qualifies),
            "spruce logs are gatherable bare-handed and priced against, so the game can begin");
    }

    @Test
    @DisplayName("a plains chunk qualifies on dirt alone")
    void plainsChunkQualifies() {
        assertTrue(qualifies(Material.DIRT));
        assertTrue(List.of(Material.DIRT, Material.STONE, Material.GRAVEL).stream()
            .anyMatch(StartingChunkAccessibilityTest::qualifies));
    }

    /**
     * Both halves of the condition are load-bearing, and each rejects a different thing.
     *
     * <p>Ice is gatherable-ish but never priced against, so it cannot pay for a chunk. Gravel is
     * priced against but needs a shovel. Only a material that is both qualifies.</p>
     */
    @Test
    @DisplayName("a material must be both priceable and tool-free to count")
    void bothHalvesOfTheConditionMatter() {
        // Priced against, but needs a tool.
        assertTrue(OwnedChunkScanner.isHarvestable(Material.GRAVEL));
        assertFalse(MaterialAccessibility.needsNoTool(Material.GRAVEL));
        assertFalse(qualifies(Material.GRAVEL));

        // Tool-free, but never priced against - so it cannot buy a chunk.
        assertFalse(OwnedChunkScanner.isHarvestable(Material.SNOW_BLOCK));
        assertFalse(qualifies(Material.SNOW_BLOCK));

        // Both.
        assertTrue(OwnedChunkScanner.isHarvestable(Material.DIRT));
        assertTrue(MaterialAccessibility.needsNoTool(Material.DIRT));
        assertTrue(qualifies(Material.DIRT));
    }

    /**
     * The set of materials that can start a game, enumerated. Small enough to read, and worth
     * reading: it is why a treeless cold biome is the dangerous case and a forest never is.
     */
    @Test
    @DisplayName("the materials that can start a game are dirt, mud, wood and crops")
    void whatCanStartAGame() {
        List<Material> starters = OwnedChunkScanner.harvestableMaterials().stream()
            .filter(StartingChunkAccessibilityTest::qualifies)
            .toList();

        assertTrue(starters.contains(Material.DIRT));
        assertTrue(starters.contains(Material.OAK_LOG));
        assertTrue(starters.contains(Material.WHEAT));

        assertFalse(starters.contains(Material.GRAVEL), "the reported softlock material");
        assertFalse(starters.contains(Material.STONE));
        assertFalse(starters.contains(Material.COBBLESTONE));

        // Every starter is tool-free by construction; this pins that none slipped through.
        assertTrue(starters.stream().allMatch(MaterialAccessibility::needsNoTool));
    }

    /**
     * Mirrors the condition in {@code ChunkEvaluator.hasBareHandAccessibleMaterial}. Kept as a
     * separate expression rather than calling the method, which needs a live world - so if the
     * two ever disagree, this test is the record of what was intended.
     */
    private static boolean qualifies(Material material) {
        return OwnedChunkScanner.isHarvestable(material)
            && MaterialAccessibility.needsNoTool(material);
    }
}
