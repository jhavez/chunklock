package me.chunklock.economy.calculation;

import me.chunklock.economy.calculation.MaterialAccessibility.ToolClass;
import org.bukkit.Material;

import java.util.List;

/**
 * The best tool a player can plausibly have, inferred from what they own.
 *
 * <h3>What this answers, and why not the obvious thing (#105)</h3>
 *
 * <p>{@link MaterialAccessibility} says what tool a material <em>needs</em>. This says what tool
 * the player can actually <em>get</em>. The two together decide whether a price is payable.</p>
 *
 * <p>The obvious implementation - read the player's inventory for a shovel - is <strong>not
 * available here</strong>, and that is a threading constraint rather than a style choice.
 * {@code ResourceBasedMaterialStrategy.calculate} runs on an async thread
 * ({@code AsyncCostCalculationService} prices eight adjacent chunks off the main thread), and
 * touching a Bukkit {@code Player}'s inventory from there is precisely what issue #78 was. The
 * existing code is careful about this: chunk coordinates are read on the calling thread with a
 * comment saying why. Adding an inventory read would reintroduce a bug this project has already
 * fixed once.</p>
 *
 * <p>So the question is answered from <strong>data already scanned</strong> - the materials in
 * the player's owned chunks, which pricing holds anyway. This is a <em>capability</em> judgement,
 * not an inventory check: "could this player have made a shovel by now", not "is one in slot 3".
 * For deciding whether a price is fair that is the better question anyway, because a player who
 * owns a forest but happens to be holding nothing is not stuck.</p>
 *
 * <h3>What the scan can and cannot prove</h3>
 *
 * <p>The scan reports blocks <strong>standing in the ground</strong>, never items collected. That
 * asymmetry is load-bearing: gravel in an owned chunk means gravel lying there <em>undug</em>, so
 * it is the softlock rather than evidence against it. The first draft of this class inferred
 * "owns gravel, therefore owns a shovel" and would have declared the reported icy-mountain start
 * perfectly fine - the same false all-clear the deleted {@code ProgressionValidationService} gave
 * by listing GRAVEL among its basic-obtainable materials.</p>
 *
 * <p>Only a block worldgen cannot place is real evidence. Cobblestone is the clean case: worldgen
 * places stone, and cobble is what a pickaxe turns it into.</p>
 *
 * <h3>The wood rule is the whole mechanism</h3>
 *
 * <p>Wood is the root of the tool tree: logs give planks, planks give sticks, planks and sticks
 * give a wooden shovel and a wooden pickaxe, and a wooden pickaxe gives cobblestone for stone
 * tools. <strong>A player with any wood is never really stuck.</strong> A player with none has
 * only what bare hands can gather, which is exactly the reported softlock - an icy mountain
 * biome with no trees, priced in gravel.</p>
 *
 * <p>This also explains why the existing tier cap could not catch the bug.
 * {@code getMaxTierForProgression} already reasons about tool availability, but it uses
 * <em>unlocked chunk count</em> as the proxy: 0-2 chunks means tier 1-3, "hand/wooden tools". A
 * player at chunk zero is assumed to have wooden tools. In a treeless biome that assumption is
 * false, and nothing checked it.</p>
 *
 * <p>Pure and static: no Bukkit calls at all, so it is safe from any thread and testable without
 * a server.</p>
 */
public final class ToolAvailability {

    private ToolAvailability() {
    }

    /**
     * Materials that cannot occur in worldgen, so finding one is evidence of tool use.
     *
     * <p><strong>This distinction is the crux, and getting it wrong recreates #105.</strong>
     * The scanner reports blocks <em>standing in the ground</em> in owned chunks - not items in
     * a chest or inventory. So "the player owns gravel" means gravel is lying there undug, which
     * proves nothing about owning a shovel. It is the softlock, not the escape from it.</p>
     *
     * <p>Only a block that worldgen never places can serve as evidence. Cobblestone is the clean
     * case: worldgen places <em>stone</em>, and cobblestone is what stone becomes when a pickaxe
     * breaks it. Seeing cobble in an owned chunk therefore means someone mined and placed it.</p>
     */
    private static boolean isToolUseEvidence(Material material) {
        return material == Material.COBBLESTONE
            || material == Material.RAW_COPPER_BLOCK
            || material == Material.HAY_BLOCK;
    }

    /**
     * The best tool class a player owning these materials can plausibly obtain.
     *
     * <p>Evaluated strongest-first:</p>
     * <ol>
     *   <li><strong>Any wood</strong> - the full tool tree is reachable, so PICKAXE.</li>
     *   <li><strong>A block worldgen cannot place</strong> - cobblestone and the like are
     *       manufactured, so their presence is evidence a tool already existed. PICKAXE.</li>
     *   <li><strong>Otherwise</strong> - NONE. Bare hands only.</li>
     * </ol>
     *
     * <p>There is deliberately <strong>no SHOVEL rung from owned materials</strong>. Nothing a
     * shovel produces is distinguishable from what worldgen places - dug gravel is just gravel -
     * so no scan result can evidence a shovel without also matching the softlock case. SHOVEL
     * remains a meaningful answer for callers that learn it another way; it is simply not
     * inferable from the ground.</p>
     *
     * <p>Rung 2 exists for the established player whose forest is mined out: they have placed
     * cobble and no logs standing, and must not be demoted to bare hands, which would re-price
     * their whole neighbourhood in dirt.</p>
     *
     * @param ownedMaterials materials present in the player's owned chunks; may be empty
     */
    public static ToolClass bestAvailable(List<Material> ownedMaterials) {
        if (ownedMaterials == null || ownedMaterials.isEmpty()) {
            return ToolClass.NONE;
        }

        for (Material material : ownedMaterials) {
            if (ResourceBasedMaterialStrategy.isWoodLike(material)) {
                return ToolClass.PICKAXE; // Wood unlocks the whole tree; nothing beats it.
            }
            if (isToolUseEvidence(material)) {
                return ToolClass.PICKAXE; // Manufactured block: a tool was already in play.
            }
        }

        return ToolClass.NONE;
    }

    /**
     * Convenience over a scan result, which is the form pricing actually holds.
     */
    public static ToolClass bestAvailable(List<OwnedChunkScanner.ResourceEntry> resources,
                                          boolean fromScan) {
        if (!fromScan || resources == null) {
            return ToolClass.NONE;
        }
        return bestAvailable(resources.stream().map(OwnedChunkScanner.ResourceEntry::material).toList());
    }

    /**
     * Whether a player owning these materials has no way to obtain any tool.
     *
     * <p>The condition that made #105 unescapable, named so the starting-chunk guarantee and the
     * pricing filter can both ask for it directly.</p>
     */
    public static boolean isBareHandedOnly(List<Material> ownedMaterials) {
        return bestAvailable(ownedMaterials) == ToolClass.NONE;
    }
}
