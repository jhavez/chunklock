package me.chunklock.economy.calculation;

import me.chunklock.economy.calculation.MaterialAccessibility.ToolClass;
import me.chunklock.services.ChunkProfileStore;
import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reported #105 softlock, reproduced end to end through the real pricing path.
 *
 * <h3>What happened, September 12 2026</h3>
 *
 * <p>{@code /chunklock start} dropped the owner in an icy mountain biome. <strong>Every
 * surrounding chunk asked for 24 GRAVEL.</strong> From an empty inventory in that biome there was
 * no first move:</p>
 *
 * <ul>
 *   <li>the surface is snow and ice, and the gravel has to be dug</li>
 *   <li>digging gravel without a shovel is slow enough to read as impossible</li>
 *   <li>the normal bootstrap - punch a tree, craft a shovel - was unavailable, no trees</li>
 *   <li>every neighbour wanted the same material, so walking somewhere cheaper was not an option</li>
 * </ul>
 *
 * <p>Not "hard". No available first move at all.</p>
 *
 * <h3>Why the other tests are not enough</h3>
 *
 * <p>The unit tests around this fix each pin one piece: the tool map, the capability inference,
 * the filter, the starting-chunk gate. This one drives the actual ranking and selection code the
 * server runs, from a chunk profile shaped like the reported biome, and asserts on the material a
 * player would really be charged. It is the test that fails if the pieces are individually
 * correct but wired together wrongly - which is exactly how #86's selection bug survived a green
 * suite.</p>
 */
class ColdStartAccessibilityTest {

    // ---- The reported scenario -----------------------------------------------------------

    /**
     * The bug itself. A new player, an icy mountain chunk, and the full target-chunk pricing
     * path. The price must be payable with bare hands.
     */
    @Test
    @DisplayName("an icy mountain start is never priced in gravel - #105")
    void icyMountainStartIsPayable() {
        Material priced = priceIcyMountainChunkFor(NEW_PLAYER);

        assertNotEquals(Material.GRAVEL, priced,
            "24 GRAVEL from an empty inventory in a treeless biome was the reported softlock");
        assertTrue(MaterialAccessibility.needsNoTool(priced),
            "a new player's first chunk must be payable with no tool at all, and " + priced
                + " is not");
    }

    /**
     * The same chunk priced for a player who has wood. Gravel becomes fair, which is what stops
     * this fix from being a blanket ban on shovel materials.
     */
    @Test
    @DisplayName("the same chunk may ask for gravel once the player has trees")
    void sameChunkIsFairWithWood() {
        List<OwnedChunkScanner.ResourceEntry> ranked = rankIcyMountain();
        ToolClass withWood = ToolAvailability.bestAvailable(List.of(Material.SPRUCE_LOG));

        List<OwnedChunkScanner.ResourceEntry> pool =
            ResourceBasedMaterialStrategy.filterByAccessibility(ranked, withWood);

        assertTrue(pool.stream().anyMatch(r -> r.material() == Material.GRAVEL),
            "with a shovel craftable, gravel is an honest thing to ask for");
    }

    /**
     * The neighbourhood, not just one chunk. The report was that <em>every</em> surrounding
     * chunk asked for the same unobtainable material, which is what removed the option of
     * walking somewhere else.
     */
    @Test
    @DisplayName("every chunk in a frozen neighbourhood prices to something gatherable")
    void wholeNeighbourhoodIsPayable() {
        List<List<ChunkProfileStore.ProfileEntry>> neighbourhood = List.of(
            icyMountainProfile(),
            snowySlopeProfile(),
            frozenPeakProfile());

        for (List<ChunkProfileStore.ProfileEntry> profile : neighbourhood) {
            Material priced = priceFor(profile, NEW_PLAYER);
            assertTrue(MaterialAccessibility.needsNoTool(priced),
                "a frozen neighbour priced at " + priced + " leaves the player with no first move");
        }
    }

    // ---- The fix must not overreach ------------------------------------------------------

    /**
     * An established player is the population this fix could most easily harm: their prices are
     * already committed and their progression assumes the old pricing. Accessibility filtering
     * stops applying past the early game, so a chunk full of stone still prices in stone.
     */
    @Test
    @DisplayName("an established player's prices are unchanged")
    void establishedPlayerPricesAreUnchanged() {
        List<OwnedChunkScanner.ResourceEntry> ranked = rankIcyMountain();

        List<OwnedChunkScanner.ResourceEntry> pool =
            ResourceBasedMaterialStrategy.filterByAccessibility(ranked, ToolClass.PICKAXE);

        assertTrue(pool.size() == ranked.size(),
            "nothing is filtered out for a player who can mine - their prices must not move");
    }

    /**
     * A forest start was never broken, and must stay unbroken. Wood is bare-hand gatherable, so
     * the filter has no effect and the chunk still prices by its own character.
     */
    @Test
    @DisplayName("a forest start still prices in wood")
    void forestStartIsUnaffected() {
        List<ChunkProfileStore.ProfileEntry> forest = new ArrayList<>(List.of(
            entry(Material.OAK_LOG, 420),
            entry(Material.DIRT, 380),
            entry(Material.STONE, 5100)));

        Material priced = priceFor(forest, NEW_PLAYER);

        assertTrue(MaterialAccessibility.needsNoTool(priced));
        assertNotEquals(Material.STONE, priced, "a new player cannot mine stone");
    }

    // ---- What the old behaviour was ------------------------------------------------------

    /**
     * The bug, demonstrated rather than described: without the filter the same chunk prices in
     * gravel. This is what makes the first test meaningful - it shows the scenario really does
     * select gravel when accessibility is not consulted, so the assertion above is not passing
     * for some unrelated reason.
     */
    @Test
    @DisplayName("without accessibility filtering the same chunk does select gravel")
    void unfilteredSelectionPicksGravel() {
        List<OwnedChunkScanner.ResourceEntry> unfiltered = rankIcyMountain();

        Material priced = ResourceBasedMaterialStrategy
            .selectFromTargetChunk(unfiltered, new ArrayDeque<>()).material();

        assertEquals(Material.GRAVEL, priced,
            "this is the reported bug: the most distinctive material in an icy mountain chunk "
                + "is gravel, and nothing in selection asked whether it could be dug");
        assertFalse(MaterialAccessibility.needsNoTool(priced));
    }

    // ---- Helpers -------------------------------------------------------------------------

    /** Unlocked-chunk count for a player who has just run /chunklock start. */
    private static final int NEW_PLAYER = 0;

    /**
     * Price a chunk the way {@code calculate} does on the target-chunk path: rank by
     * distinctiveness, cap by progression tier, filter by accessibility, then select.
     */
    private static Material priceFor(List<ChunkProfileStore.ProfileEntry> profile, int unlockedChunks) {
        List<OwnedChunkScanner.ResourceEntry> ranked =
            ResourceBasedMaterialStrategy.rankObtainable(profile, baseline(), maxTierFor(unlockedChunks));

        // A new player owns only the chunk they spawned in, so their tool capability is whatever
        // that chunk offers - which in a frozen biome is nothing.
        ToolClass available = ToolAvailability.bestAvailable(
            profile.stream().map(ChunkProfileStore.ProfileEntry::material).toList());

        List<OwnedChunkScanner.ResourceEntry> pool =
            ResourceBasedMaterialStrategy.filterByAccessibility(ranked, available);

        return ResourceBasedMaterialStrategy.selectFromTargetChunk(pool, new ArrayDeque<>()).material();
    }

    private static Material priceIcyMountainChunkFor(int unlockedChunks) {
        return priceFor(icyMountainProfile(), unlockedChunks);
    }

    private static List<OwnedChunkScanner.ResourceEntry> rankIcyMountain() {
        return ResourceBasedMaterialStrategy.rankObtainable(
            icyMountainProfile(), baseline(), maxTierFor(NEW_PLAYER));
    }

    /**
     * The reported chunk: gravel well above its world average, a normal stone column, and the
     * small amount of dirt that makes the biome survivable at all.
     */
    private static List<ChunkProfileStore.ProfileEntry> icyMountainProfile() {
        return new ArrayList<>(List.of(
            entry(Material.GRAVEL, 880),
            entry(Material.STONE, 5400),
            entry(Material.DIRT, 70),
            entry(Material.DEEPSLATE, 2900)));
    }

    private static List<ChunkProfileStore.ProfileEntry> snowySlopeProfile() {
        return new ArrayList<>(List.of(
            entry(Material.GRAVEL, 640),
            entry(Material.STONE, 5800),
            entry(Material.DIRT, 55)));
    }

    private static List<ChunkProfileStore.ProfileEntry> frozenPeakProfile() {
        return new ArrayList<>(List.of(
            entry(Material.GRAVEL, 520),
            entry(Material.STONE, 6100),
            entry(Material.DIRT, 40),
            entry(Material.ANDESITE, 300)));
    }

    /** World averages, so distinctiveness has something to rank against. */
    private static Map<Material, Double> baseline() {
        Map<Material, Double> b = new LinkedHashMap<>();
        b.put(Material.STONE, 5200.0);
        b.put(Material.DEEPSLATE, 3100.0);
        b.put(Material.DIRT, 260.0);
        b.put(Material.GRAVEL, 100.0);
        b.put(Material.ANDESITE, 280.0);
        return b;
    }

    /** Mirrors ResourceBasedMaterialStrategy.getMaxTierForProgression, which is private. */
    private static int maxTierFor(int unlockedChunks) {
        if (unlockedChunks >= 15) return 6;
        if (unlockedChunks >= 8) return 5;
        if (unlockedChunks >= 3) return 4;
        return 3;
    }

    private static ChunkProfileStore.ProfileEntry entry(Material material, int blockCount) {
        return new ChunkProfileStore.ProfileEntry(material, blockCount,
            OwnedChunkScanner.getMaterialTier(material));
    }

    private static void assertEquals(Material expected, Material actual, String message) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual, message);
    }
}
