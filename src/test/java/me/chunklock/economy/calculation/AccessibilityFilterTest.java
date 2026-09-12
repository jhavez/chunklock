package me.chunklock.economy.calculation;

import me.chunklock.economy.calculation.MaterialAccessibility.ToolClass;
import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The accessibility filter, and the reported softlock it exists to prevent.
 *
 * <h3>The bug this pins (#105)</h3>
 *
 * <p>{@code /chunklock start} in an icy mountain biome priced every neighbouring chunk at
 * 24 GRAVEL. From an empty inventory in that biome there was no first move: gravel needs a
 * shovel, a shovel needs wood, and there were no trees. Every neighbour asked for the same
 * thing, so walking somewhere cheaper was not an option either.</p>
 *
 * <p>Pricing was working as designed. The design had no accessibility axis - the tiers rank
 * scarcity, and nothing asked whether the player could harvest what was selected.</p>
 */
class AccessibilityFilterTest {

    // ---- The reported scenario -----------------------------------------------------------

    /**
     * The softlock itself. An icy chunk offers gravel and stone; a new player has neither tool.
     * Selection must not be handed either material.
     */
    @Test
    @DisplayName("a bare-handed player is never priced in gravel - the #105 fix")
    void bareHandedPlayerIsNotPricedInGravel() {
        List<OwnedChunkScanner.ResourceEntry> icyChunk = List.of(
            entry(Material.GRAVEL, 900, 1),
            entry(Material.STONE, 4000, 4),
            entry(Material.DIRT, 120, 1));

        List<OwnedChunkScanner.ResourceEntry> reachable =
            ResourceBasedMaterialStrategy.filterByAccessibility(icyChunk, ToolClass.NONE);

        List<Material> materials = reachable.stream()
            .map(OwnedChunkScanner.ResourceEntry::material).toList();

        assertEquals(List.of(Material.DIRT), materials,
            "only dirt is gatherable bare-handed, so only dirt may be asked for");

        // And the material selection actually lands on is payable.
        Material selected = ResourceBasedMaterialStrategy
            .selectFromTargetChunk(reachable, new ArrayDeque<>()).material();
        assertTrue(MaterialAccessibility.needsNoTool(selected),
            "the selected material must be gatherable with no tool");
        assertNotEquals(Material.GRAVEL, selected, "24 GRAVEL was the reported softlock");
    }

    /**
     * The same chunk, same price list, one difference: the player owns wood somewhere. Gravel
     * becomes a fair ask because a shovel is now craftable.
     */
    @Test
    @DisplayName("the same chunk may ask for gravel once the player has wood")
    void gravelIsFairOnceWoodIsOwned() {
        List<OwnedChunkScanner.ResourceEntry> icyChunk = List.of(
            entry(Material.GRAVEL, 900, 1),
            entry(Material.DIRT, 120, 1));

        ToolClass withWood = ToolAvailability.bestAvailable(List.of(Material.OAK_LOG));

        List<OwnedChunkScanner.ResourceEntry> reachable =
            ResourceBasedMaterialStrategy.filterByAccessibility(icyChunk, withWood);

        assertEquals(2, reachable.size(), "with wood, both materials are fair asks");
        assertTrue(reachable.stream().anyMatch(r -> r.material() == Material.GRAVEL));
    }

    // ---- Filtering must not break what #86 fixed -----------------------------------------

    /**
     * Filtering runs <em>before</em> selection precisely so the distinctiveness ranking
     * survives. This test pins the ordering contract: whatever order the caller established is
     * the order that comes back, minus the unreachable entries.
     */
    @Test
    @DisplayName("filtering preserves the caller's ranking rather than re-sorting")
    void filteringPreservesOrdering() {
        // Deliberately not in tier order - this is what a distinctiveness ranking looks like.
        List<OwnedChunkScanner.ResourceEntry> ranked = List.of(
            entry(Material.CLAY, 90, 1),
            entry(Material.OAK_LOG, 200, 2),
            entry(Material.DIRT, 400, 1),
            entry(Material.STONE, 5300, 4));

        List<Material> filtered = ResourceBasedMaterialStrategy
            .filterByAccessibility(ranked, ToolClass.NONE).stream()
            .map(OwnedChunkScanner.ResourceEntry::material).toList();

        assertEquals(List.of(Material.OAK_LOG, Material.DIRT), filtered,
            "surviving entries keep their original relative order - re-sorting here would "
                + "discard the ranking and reintroduce #86's shape");
    }

    // ---- Degenerate cases ----------------------------------------------------------------

    /**
     * The case that decides whether this filter can make things worse.
     *
     * <p>If every candidate is unreachable, returning empty would leave selection with nothing
     * and callers guessing. Handing back the original list keeps today's behaviour - a possibly
     * unpayable price - which is bad, but strictly better than no price at all. The callers own
     * the fallback decision.</p>
     */
    @Test
    @DisplayName("an entirely unreachable pool is passed through, not emptied")
    void unreachablePoolIsPassedThrough() {
        List<OwnedChunkScanner.ResourceEntry> allGated = List.of(
            entry(Material.GRAVEL, 900, 1),
            entry(Material.STONE, 4000, 4));

        List<OwnedChunkScanner.ResourceEntry> result =
            ResourceBasedMaterialStrategy.filterByAccessibility(allGated, ToolClass.NONE);

        assertSame(allGated, result,
            "no price at all is worse than an awkward one; the caller decides what to do");
    }

    @Test
    @DisplayName("a pickaxe filters nothing out")
    void pickaxeKeepsEverything() {
        List<OwnedChunkScanner.ResourceEntry> everything = List.of(
            entry(Material.GRAVEL, 900, 1),
            entry(Material.STONE, 4000, 4),
            entry(Material.DIAMOND_ORE, 12, 6),
            entry(Material.DIRT, 120, 1));

        assertEquals(everything,
            ResourceBasedMaterialStrategy.filterByAccessibility(everything, ToolClass.PICKAXE),
            "an established player's prices must not change");
    }

    @Test
    @DisplayName("empty and null inputs are returned unchanged")
    void degenerateInputsAreSafe() {
        assertTrue(ResourceBasedMaterialStrategy
            .filterByAccessibility(List.of(), ToolClass.NONE).isEmpty());
        assertEquals(null, ResourceBasedMaterialStrategy
            .filterByAccessibility(null, ToolClass.NONE));
    }

    // ---- The full chain ------------------------------------------------------------------

    /**
     * The two pieces from tasks 1-2 joined to the filter, driven from the scan data pricing
     * actually holds. This is the end-to-end claim: a treeless start cannot be priced in
     * something undiggable.
     */
    @Test
    @DisplayName("end to end: a treeless start is priced in something gatherable")
    void endToEndTreelessStartIsPayable() {
        // What the scanner reports for a single owned chunk in an icy mountain biome.
        List<OwnedChunkScanner.ResourceEntry> scan = List.of(
            entry(Material.GRAVEL, 900, 1),
            entry(Material.STONE, 4200, 4),
            entry(Material.DIRT, 80, 1));

        ToolClass available = ToolAvailability.bestAvailable(scan, true);
        assertEquals(ToolClass.NONE, available, "no wood in an icy biome means bare hands");

        List<OwnedChunkScanner.ResourceEntry> pool =
            ResourceBasedMaterialStrategy.filterByAccessibility(scan, available);

        Material priced = ResourceBasedMaterialStrategy
            .selectMaterial(pool, 0, new ArrayDeque<>()).material();

        assertTrue(MaterialAccessibility.needsNoTool(priced),
            "a first unlock must be payable from an empty inventory");
        assertFalse(MaterialAccessibility.required(priced) == ToolClass.SHOVEL);
    }

    /**
     * The re-roll button's count must agree with what pricing actually does (#83).
     *
     * <p>{@code countSelectableCandidates} exists so a re-roll is only offered when it can
     * change something, and its own documentation says it deliberately mirrors
     * {@code calculate} - same source, same tier cap, same window. Accessibility filtering is
     * now part of that, so it has to be applied in both or the button lies: greyed out when a
     * re-roll would work, or charged for when it cannot move.</p>
     *
     * <p>This checks the filter's effect on the count rather than calling the method, which
     * needs a live server. The window cap is applied after filtering in both places.</p>
     */
    @Test
    @DisplayName("filtering changes the re-roll count the same way it changes selection")
    void rerollCountAgreesWithSelection() {
        List<OwnedChunkScanner.ResourceEntry> icyChunk = List.of(
            entry(Material.GRAVEL, 900, 1),
            entry(Material.STONE, 4000, 4),
            entry(Material.DIRT, 120, 1));

        int reachableCount = ResourceBasedMaterialStrategy
            .filterByAccessibility(icyChunk, ToolClass.NONE).size();

        assertEquals(1, reachableCount,
            "one reachable material means no alternative, so the re-roll button must grey out - "
                + "counting all three would charge for a re-roll that cannot move");

        assertEquals(3, ResourceBasedMaterialStrategy
                .filterByAccessibility(icyChunk, ToolClass.PICKAXE).size(),
            "and an equipped player still has the alternatives they had before");
    }

    // ---- Helpers -------------------------------------------------------------------------

    private static OwnedChunkScanner.ResourceEntry entry(Material material, int count, int tier) {
        return new OwnedChunkScanner.ResourceEntry(material, count, tier);
    }
}
