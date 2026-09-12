package me.chunklock.economy.calculation;

import me.chunklock.economy.calculation.MaterialAccessibility.ToolClass;
import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What tool a player can plausibly obtain, inferred from what they own.
 *
 * <h3>The bug these pin (#105)</h3>
 *
 * <p>The reported softlock was an icy mountain start: no trees, every neighbouring chunk priced
 * at 24 GRAVEL, and no way to make the shovel gravel needs. The missing fact was not "is a
 * shovel in the player's inventory" - pricing runs async and cannot read an inventory (#78) -
 * but <strong>"could this player have made one at all"</strong>.</p>
 *
 * <p>Wood is the answer to that question. It is the root of the tool tree, so a player with any
 * wood is never really stuck, and a player with none has only bare hands.</p>
 */
class ToolAvailabilityTest {

    // ---- The reported scenario -----------------------------------------------------------

    /**
     * The softlock itself, as a test: the icy mountain biome that started this.
     *
     * <p>Snow, ice and gravel, and nothing else. Bare hands reach none of it, so the correct
     * answer is NONE - which is what lets pricing refuse to ask for the gravel.</p>
     */
    @Test
    @DisplayName("a treeless icy chunk leaves the player bare-handed - the #105 softlock")
    void treelessIcyChunkIsBareHandedOnly() {
        List<Material> icyMountain = List.of(Material.GRAVEL, Material.STONE);

        assertEquals(ToolClass.NONE, ToolAvailability.bestAvailable(icyMountain),
            "no wood means no way to craft any tool, whatever else is lying around");
    }

    /**
     * The counterexample that shows the rule is about wood specifically, not about how much
     * material a player has. A pile of gravel is not progress if nothing can dig it.
     */
    @Test
    @DisplayName("adding more gravel does not make a treeless start survivable")
    void moreGravelDoesNotHelp() {
        assertEquals(ToolClass.NONE,
            ToolAvailability.bestAvailable(List.of(Material.GRAVEL)));
        assertEquals(ToolClass.NONE,
            ToolAvailability.bestAvailable(List.of(Material.GRAVEL, Material.SAND, Material.CLAY)),
            "three shovel materials and no shovel is still no shovel");
    }

    // ---- Wood is the whole mechanism -----------------------------------------------------

    @Test
    @DisplayName("any wood unlocks the full tool tree")
    void anyWoodReachesEverything() {
        // Logs, stems and the generic wood blocks all count - isWoodLike matches by suffix.
        for (Material wood : List.of(Material.OAK_LOG, Material.SPRUCE_LOG, Material.CHERRY_LOG,
                Material.CRIMSON_STEM, Material.WARPED_STEM, Material.PALE_OAK_LOG)) {
            assertEquals(ToolClass.PICKAXE, ToolAvailability.bestAvailable(List.of(wood)),
                wood + " should unlock the whole tool tree: log -> planks -> sticks -> tools");
        }
    }

    /**
     * The exact contrast the reported bug turns on: the same gravel requirement is fine in a
     * forest and a softlock in an icy biome. One log is the entire difference.
     */
    @Test
    @DisplayName("one log is the difference between stuck and fine")
    void oneLogChangesEverything() {
        List<Material> withoutWood = List.of(Material.GRAVEL);
        List<Material> withWood = List.of(Material.GRAVEL, Material.OAK_LOG);

        assertTrue(ToolAvailability.isBareHandedOnly(withoutWood));
        assertFalse(ToolAvailability.isBareHandedOnly(withWood));

        assertTrue(MaterialAccessibility.isReachable(
                ToolAvailability.bestAvailable(withWood), Material.GRAVEL),
            "with wood the player can make a shovel, so pricing in gravel is fair");
        assertFalse(MaterialAccessibility.isReachable(
                ToolAvailability.bestAvailable(withoutWood), Material.GRAVEL),
            "without wood it is not - this is the bug");
    }

    // ---- Owning a material is evidence the tool existed ----------------------------------

    /**
     * The case that stops this being over-strict on established players.
     *
     * <p>A player who has mined their forest out has placed cobble and no logs left standing.
     * Demoting them to bare hands would re-price their whole neighbourhood in dirt, which would
     * be a worse bug than the one being fixed.</p>
     */
    @Test
    @DisplayName("a mined-out player keeps their tools - placed cobble proves a pickaxe")
    void minedOutPlayerKeepsPickaxe() {
        List<Material> noTreesLeft = List.of(Material.COBBLESTONE, Material.STONE, Material.GRAVEL);

        assertEquals(ToolClass.PICKAXE, ToolAvailability.bestAvailable(noTreesLeft),
            "cobblestone cannot come from worldgen, so someone mined it with a pickaxe");
    }

    /**
     * The distinction the first draft of this class got wrong, so it is pinned explicitly.
     *
     * <p>The scan reports blocks <strong>standing in the ground</strong>, not items collected.
     * Gravel present means gravel lying there undug - which is the softlock, not evidence of a
     * shovel. An earlier version inferred SHOVEL from exactly this and would have declared the
     * reported icy-mountain start perfectly fine.</p>
     */
    @Test
    @DisplayName("material in the ground is never evidence of the tool that digs it")
    void undugMaterialIsNotEvidence() {
        List<Material> shovelMaterials = new ArrayList<>(List.of(Material.CLAY, Material.DIRT));
        assertEquals(ToolClass.NONE, ToolAvailability.bestAvailable(shovelMaterials),
            "clay in the ground is what needs a shovel, not proof of owning one");

        assertEquals(ToolClass.NONE, ToolAvailability.bestAvailable(List.of(Material.STONE)),
            "worldgen places stone everywhere - its presence says nothing about a pickaxe");
    }

    /**
     * Natural stone and manufactured cobblestone are both pickaxe-gated materials, and they give
     * opposite answers here. That looks inconsistent until the reason is stated: only the
     * manufactured one could not have come from worldgen.
     */
    @Test
    @DisplayName("stone is not evidence of mining but cobblestone is")
    void onlyManufacturedBlocksAreEvidence() {
        assertEquals(ToolClass.NONE, ToolAvailability.bestAvailable(List.of(Material.STONE)));
        assertEquals(ToolClass.PICKAXE, ToolAvailability.bestAvailable(List.of(Material.COBBLESTONE)));

        assertEquals(ToolClass.PICKAXE, MaterialAccessibility.required(Material.STONE),
            "both are pickaxe-gated materials - the difference is only whether worldgen places them");
        assertEquals(ToolClass.PICKAXE, MaterialAccessibility.required(Material.COBBLESTONE));
    }

    /**
     * There is deliberately no way for scan data to produce SHOVEL, because nothing a shovel
     * makes is distinguishable from what worldgen places. Pinned so a future change that adds a
     * shovel inference has to confront the reasoning rather than stumble into it.
     */
    @Test
    @DisplayName("no owned-material combination infers SHOVEL")
    void shovelIsNotInferableFromTheGround() {
        for (Material material : OwnedChunkScanner.harvestableMaterials()) {
            assertFalse(ToolAvailability.bestAvailable(List.of(material)) == ToolClass.SHOVEL,
                material + " must not infer SHOVEL - dug gravel is indistinguishable from "
                    + "gravel that was always there");
        }
    }

    // ---- Degenerate inputs ---------------------------------------------------------------

    @Test
    @DisplayName("no owned materials means bare hands, not a crash")
    void emptyAndNullAreBareHanded() {
        assertEquals(ToolClass.NONE, ToolAvailability.bestAvailable(List.of()));
        assertEquals(ToolClass.NONE, ToolAvailability.bestAvailable((List<Material>) null));
        assertTrue(ToolAvailability.isBareHandedOnly(List.of()));
    }

    /**
     * A brand-new player owns nothing at all, which is the state the starting-chunk guarantee
     * has to price against. It must resolve to bare hands rather than to an optimistic default.
     */
    @Test
    @DisplayName("a brand-new player is bare-handed")
    void newPlayerIsBareHanded() {
        assertEquals(ToolClass.NONE, ToolAvailability.bestAvailable(List.of()),
            "a first unlock must be payable with no tool at all");
    }

    // ---- The scan-result convenience -----------------------------------------------------

    @Test
    @DisplayName("reading from a scan result agrees with reading from materials")
    void scanOverloadAgrees() {
        List<OwnedChunkScanner.ResourceEntry> scan = List.of(
            new OwnedChunkScanner.ResourceEntry(Material.GRAVEL, 300, 1),
            new OwnedChunkScanner.ResourceEntry(Material.OAK_LOG, 40, 2));

        assertEquals(ToolClass.PICKAXE, ToolAvailability.bestAvailable(scan, true));
    }

    /**
     * When pricing did not come from a scan there is no evidence either way, and the safe
     * reading is bare hands. Guessing optimistically here would put the softlock back for
     * exactly the players pricing knows least about.
     */
    @Test
    @DisplayName("no scan data means bare hands, not an optimistic guess")
    void absentScanDataIsPessimistic() {
        List<OwnedChunkScanner.ResourceEntry> scan = List.of(
            new OwnedChunkScanner.ResourceEntry(Material.OAK_LOG, 40, 2));

        assertEquals(ToolClass.NONE, ToolAvailability.bestAvailable(scan, false),
            "target-chunk pricing carries no information about what the player owns");
    }
}
