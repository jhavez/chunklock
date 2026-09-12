package me.chunklock.economy.calculation;

import org.bukkit.Material;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Which tool a material needs before a player can actually gather it.
 *
 * <h3>The gap this fills (#105)</h3>
 *
 * <p>{@link OwnedChunkScanner#getMaterialTier} ranks materials by <strong>scarcity</strong>.
 * Nothing anywhere asked whether the player could <em>harvest</em> what pricing selected, so a
 * {@code /chunklock start} in an icy mountain biome priced every neighbouring chunk at 24 GRAVEL:
 * present in the chunk, undiggable without a shovel, and with no trees to craft one from. No
 * first move existed at all.</p>
 *
 * <p><strong>Tool requirement cuts across the tiers, and tier 1 is the dangerous one.</strong>
 * That is the non-obvious part. Tier 1 mixes the game's only two no-tool materials (dirt and mud)
 * with four that need a shovel (gravel, sand, red sand, clay) and two that need a pickaxe
 * (cobblestone, netherrack) - and tier 1 is exactly where a new player is capped. The high tiers
 * are safe by accident: anyone who has progressed to tier 4 owns a pickaxe already.</p>
 *
 * <h3>Why this is not Bukkit's job</h3>
 *
 * <p>Bukkit exposes no "what tool does this block need" query. {@code Material} has hardness data
 * but not tool class, and the block-drops API needs a live server and a real {@code ItemStack} to
 * answer. This map is therefore hand-maintained, which is exactly why
 * {@code MaterialAccessibilityTest} asserts it covers every member of
 * {@link OwnedChunkScanner#harvestableMaterials()}.</p>
 *
 * <p>Pure and static by design: no Bukkit lifecycle, no plugin reference, fully unit-testable.
 * Everything downstream trusts this class, so it has to be the easiest piece to verify.</p>
 */
public final class MaterialAccessibility {

    /**
     * The tool a player needs in hand before a material yields anything.
     *
     * <p>Ordered weakest to strongest, so {@link #isReachable} is an ordinal comparison and a
     * player who can make a pickaxe can also make a shovel.</p>
     */
    public enum ToolClass {
        /** Gatherable bare-handed. Dirt, logs, crops, snow. */
        NONE,
        /** Needs a shovel to be worth digging. Gravel, sand, clay. */
        SHOVEL,
        /** Drops nothing at all without a pickaxe. Stone, ores. */
        PICKAXE
    }

    private static final Map<Material, ToolClass> REQUIRED_TOOL = buildRequiredTool();

    private MaterialAccessibility() {
    }

    /**
     * The tool class needed to gather {@code material}.
     *
     * <p><strong>Fails closed.</strong> An unmapped material returns {@link ToolClass#PICKAXE},
     * the most restrictive value, so a material added to the scanner without a matching entry
     * here becomes momentarily <em>unavailable</em> rather than silently free. The opposite
     * default would reintroduce #105 for that material with no test failing.</p>
     */
    public static ToolClass required(Material material) {
        return REQUIRED_TOOL.getOrDefault(material, ToolClass.PICKAXE);
    }

    /**
     * Whether a player whose best available tool is {@code have} can gather {@code material}.
     */
    public static boolean isReachable(ToolClass have, Material material) {
        return have.ordinal() >= required(material).ordinal();
    }

    /**
     * Whether this material can be gathered with bare hands.
     *
     * <p>The property that decides whether a chunk is payable from an empty inventory, which is
     * what the starting-chunk guarantee needs.</p>
     */
    public static boolean needsNoTool(Material material) {
        return required(material) == ToolClass.NONE;
    }

    // ---- Static data ---------------------------------------------------------------------

    /**
     * Tool requirement for every material {@link OwnedChunkScanner} prices against.
     *
     * <p>Grouped by tool rather than by tier, because tool is the axis that matters here and
     * grouping by tier is what hid the problem in the first place.</p>
     */
    private static Map<Material, ToolClass> buildRequiredTool() {
        Map<Material, ToolClass> tools = new EnumMap<>(Material.class);

        // ---- Bare-handed -----------------------------------------------------------------
        // Dirt and mud are the only two tier 1 materials in this group. Everything else
        // no-tool is wood or a crop, which is why a treeless biome is the softlock case.
        tools.put(Material.DIRT, ToolClass.NONE);
        tools.put(Material.MUD, ToolClass.NONE);

        // Logs: an axe is faster but bare hands do drop the log.
        tools.put(Material.OAK_LOG, ToolClass.NONE);
        tools.put(Material.BIRCH_LOG, ToolClass.NONE);
        tools.put(Material.SPRUCE_LOG, ToolClass.NONE);
        tools.put(Material.JUNGLE_LOG, ToolClass.NONE);
        tools.put(Material.ACACIA_LOG, ToolClass.NONE);
        tools.put(Material.DARK_OAK_LOG, ToolClass.NONE);
        tools.put(Material.CHERRY_LOG, ToolClass.NONE);
        tools.put(Material.MANGROVE_LOG, ToolClass.NONE);
        tools.put(Material.PALE_OAK_LOG, ToolClass.NONE);
        tools.put(Material.CRIMSON_STEM, ToolClass.NONE);
        tools.put(Material.WARPED_STEM, ToolClass.NONE);

        // Crops and other renewables: all hand-breakable.
        tools.put(Material.WHEAT, ToolClass.NONE);
        tools.put(Material.SUGAR_CANE, ToolClass.NONE);
        tools.put(Material.BAMBOO, ToolClass.NONE);
        tools.put(Material.CACTUS, ToolClass.NONE);
        tools.put(Material.KELP, ToolClass.NONE);
        tools.put(Material.HAY_BLOCK, ToolClass.NONE);
        tools.put(Material.MELON, ToolClass.NONE);
        tools.put(Material.PUMPKIN, ToolClass.NONE);

        // ---- Shovel ----------------------------------------------------------------------
        // These do drop bare-handed, but slowly enough to read as impossible - which is the
        // reported #105 experience, not a theoretical concern. Treated as gated.
        tools.put(Material.GRAVEL, ToolClass.SHOVEL);
        tools.put(Material.SAND, ToolClass.SHOVEL);
        tools.put(Material.RED_SAND, ToolClass.SHOVEL);
        tools.put(Material.CLAY, ToolClass.SHOVEL);
        tools.put(Material.CLAY_BALL, ToolClass.SHOVEL);

        // ---- Pickaxe ---------------------------------------------------------------------
        // Stone and ore drop *nothing* without a pickaxe, so these are hard gates rather
        // than slow ones.
        tools.put(Material.COBBLESTONE, ToolClass.PICKAXE);
        tools.put(Material.NETHERRACK, ToolClass.PICKAXE);

        tools.put(Material.STONE, ToolClass.PICKAXE);
        tools.put(Material.DEEPSLATE, ToolClass.PICKAXE);
        tools.put(Material.GRANITE, ToolClass.PICKAXE);
        tools.put(Material.DIORITE, ToolClass.PICKAXE);
        tools.put(Material.ANDESITE, ToolClass.PICKAXE);
        tools.put(Material.TUFF, ToolClass.PICKAXE);
        tools.put(Material.BASALT, ToolClass.PICKAXE);
        tools.put(Material.BLACKSTONE, ToolClass.PICKAXE);

        tools.put(Material.COPPER_ORE, ToolClass.PICKAXE);
        tools.put(Material.RAW_COPPER_BLOCK, ToolClass.PICKAXE);
        tools.put(Material.COAL_ORE, ToolClass.PICKAXE);
        tools.put(Material.DEEPSLATE_COAL_ORE, ToolClass.PICKAXE);

        tools.put(Material.IRON_ORE, ToolClass.PICKAXE);
        tools.put(Material.DEEPSLATE_IRON_ORE, ToolClass.PICKAXE);
        tools.put(Material.GOLD_ORE, ToolClass.PICKAXE);
        tools.put(Material.DEEPSLATE_GOLD_ORE, ToolClass.PICKAXE);
        tools.put(Material.LAPIS_ORE, ToolClass.PICKAXE);
        tools.put(Material.DEEPSLATE_LAPIS_ORE, ToolClass.PICKAXE);
        tools.put(Material.REDSTONE_ORE, ToolClass.PICKAXE);
        tools.put(Material.DEEPSLATE_REDSTONE_ORE, ToolClass.PICKAXE);

        tools.put(Material.DIAMOND_ORE, ToolClass.PICKAXE);
        tools.put(Material.DEEPSLATE_DIAMOND_ORE, ToolClass.PICKAXE);
        tools.put(Material.EMERALD_ORE, ToolClass.PICKAXE);
        tools.put(Material.DEEPSLATE_EMERALD_ORE, ToolClass.PICKAXE);
        tools.put(Material.ANCIENT_DEBRIS, ToolClass.PICKAXE);

        return Collections.unmodifiableMap(tools);
    }
}
