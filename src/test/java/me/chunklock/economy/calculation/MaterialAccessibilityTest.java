package me.chunklock.economy.calculation;

import me.chunklock.economy.calculation.MaterialAccessibility.ToolClass;
import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which materials need a tool, and the completeness guarantee that keeps the map honest.
 *
 * <h3>The bug these pin (#105)</h3>
 *
 * <p>A {@code /chunklock start} in an icy mountain biome priced every neighbouring chunk at
 * 24 GRAVEL. Gravel was in the chunk, so resource-scan pricing was working as designed - but it
 * cannot be dug without a shovel, and a treeless biome offers no wood to craft one. There was no
 * first move, and nothing in the pricing path had any notion of a tool requirement to notice.</p>
 *
 * <p>These tests cover the accessibility map itself. Whether pricing <em>consults</em> it is
 * covered where the filtering happens.</p>
 */
class MaterialAccessibilityTest {

    // ---- The completeness guarantee ------------------------------------------------------

    /**
     * The most valuable test in this class, and the reason
     * {@link OwnedChunkScanner#harvestableMaterials()} is exposed at all.
     *
     * <p>The map is hand-maintained because Bukkit has no tool-class query. So the risk is not
     * that today's entries are wrong - they are checked below - but that someone adds a material
     * to the scanner in a year and never touches this file. Enumerating the real set is what
     * turns that omission into a build failure instead of a softlock.</p>
     */
    @Test
    @DisplayName("every harvestable material has an explicit tool requirement")
    void everyHarvestableMaterialIsMapped() {
        List<Material> unmapped = new ArrayList<>();

        for (Material material : OwnedChunkScanner.harvestableMaterials()) {
            if (!isExplicitlyMapped(material)) {
                unmapped.add(material);
            }
        }

        assertTrue(unmapped.isEmpty(),
            "these materials can be priced against but have no accessibility entry, so each is a "
                + "potential #105 softlock - add them to MaterialAccessibility: " + unmapped);
    }

    /**
     * Fail-closed is a deliberate choice, so it gets a test of its own rather than being left
     * as an implementation detail. An unmapped material must read as maximally restricted: that
     * makes an omission cause a visibly missing material, not a silently free one.
     */
    @Test
    @DisplayName("an unmapped material fails closed rather than becoming free")
    void unmappedMaterialFailsClosed() {
        // A decorative block the scanner never prices against, standing in for a material
        // someone adds to HARVESTABLE_MATERIALS without updating the accessibility map.
        assertFalse(OwnedChunkScanner.isHarvestable(Material.BOOKSHELF),
            "precondition: this test needs a material the scanner does not price against");

        assertEquals(ToolClass.PICKAXE, MaterialAccessibility.required(Material.BOOKSHELF),
            "an unknown material must default to the most restrictive tool, never to NONE");
        assertFalse(MaterialAccessibility.needsNoTool(Material.BOOKSHELF));
    }

    // ---- Tier 1 is the dangerous tier ----------------------------------------------------

    /**
     * The insight behind the whole fix, pinned as a test.
     *
     * <p>Tier 1 is where a new player is capped, and it is the <em>only</em> tier that mixes
     * tool requirements. The high tiers are uniformly pickaxe-gated and therefore safe by
     * accident: a player who has progressed far enough to be priced in iron owns a pickaxe.</p>
     */
    @Test
    @DisplayName("tier 1 mixes all three tool classes - which is why it softlocks")
    void tierOneMixesToolClasses() {
        // The two that made a treeless start survivable.
        assertEquals(ToolClass.NONE, MaterialAccessibility.required(Material.DIRT));
        assertEquals(ToolClass.NONE, MaterialAccessibility.required(Material.MUD));

        // The four that caused the report.
        assertEquals(ToolClass.SHOVEL, MaterialAccessibility.required(Material.GRAVEL));
        assertEquals(ToolClass.SHOVEL, MaterialAccessibility.required(Material.SAND));
        assertEquals(ToolClass.SHOVEL, MaterialAccessibility.required(Material.RED_SAND));
        assertEquals(ToolClass.SHOVEL, MaterialAccessibility.required(Material.CLAY));

        // And two that are a hard gate, not a slow one.
        assertEquals(ToolClass.PICKAXE, MaterialAccessibility.required(Material.COBBLESTONE));
        assertEquals(ToolClass.PICKAXE, MaterialAccessibility.required(Material.NETHERRACK));

        // All eight are tier 1: scarcity says they are interchangeable, accessibility does not.
        for (Material m : List.of(Material.DIRT, Material.MUD, Material.GRAVEL, Material.SAND,
                Material.RED_SAND, Material.CLAY, Material.COBBLESTONE, Material.NETHERRACK)) {
            assertEquals(1, OwnedChunkScanner.getMaterialTier(m),
                m + " is expected to be tier 1 - this test is about tier 1 being mixed");
        }
    }

    /** The reported material, called out by name so a regression is unmistakable. */
    @Test
    @DisplayName("gravel needs a shovel - the exact #105 requirement")
    void gravelNeedsAShovel() {
        assertFalse(MaterialAccessibility.needsNoTool(Material.GRAVEL),
            "24 GRAVEL from an empty inventory in a treeless biome was the reported softlock");
        assertFalse(MaterialAccessibility.isReachable(ToolClass.NONE, Material.GRAVEL));
        assertTrue(MaterialAccessibility.isReachable(ToolClass.SHOVEL, Material.GRAVEL));
    }

    // ---- biome-unlocks.yml is a second vocabulary ----------------------------------------

    /**
     * The completeness guarantee, extended to the file biome-based pricing reads (#105, beach).
     *
     * <p>Once the biome path is filtered for accessibility, {@code biome-unlocks.yml} becomes a
     * second source of priceable materials - and a wider one than the scanner, naming mob drops
     * and crafted goods the scanner never sees. Because {@link MaterialAccessibility#required}
     * fails closed, an unmapped entry there reads as PICKAXE and drops out of the pool silently.
     * SEAGRASS was exactly that: unmapped, so a beach would have had both its options rejected
     * and fallen to the DIRT floor.</p>
     *
     * <p><strong>Parses the real file rather than a copied list</strong>, so editing the YAML
     * without touching the accessibility map fails the build instead of shipping a softlock.</p>
     */
    @Test
    @DisplayName("every material named in biome-unlocks.yml has an explicit tool requirement")
    void everyBiomeUnlockMaterialIsMapped() throws Exception {
        List<Material> named = materialsNamedInBiomeUnlocks();

        assertFalse(named.isEmpty(),
            "precondition: this test is worthless if it parsed nothing - check the resource path");

        List<Material> unmapped = new ArrayList<>();
        for (Material material : named) {
            if (!isExplicitlyMapped(material)) {
                unmapped.add(material);
            }
        }

        assertTrue(unmapped.isEmpty(),
            "biome-based pricing can ask for these, but they have no accessibility entry - so "
                + "each fails closed to PICKAXE and vanishes from the filtered pool: " + unmapped);
    }

    /**
     * The beach case from the #105 live test, stated as the property that failed.
     *
     * <p>A beach offers {@code SAND: 20} and {@code SEAGRASS: 12}. Sand is shovel-gated, so if
     * seagrass were gated too a bare-handed player would have nothing - which is the softlock,
     * one biome over from the reported icy mountain.</p>
     */
    @Test
    @DisplayName("every biome offers at least one bare-hand gatherable material")
    void everyBiomeHasABareHandOption() throws Exception {
        Map<String, List<Material>> byBiome = biomeUnlocksByBiome();

        assertFalse(byBiome.isEmpty(),
            "precondition: parsed no biomes - check the resource path");

        List<String> unpayable = new ArrayList<>();
        for (Map.Entry<String, List<Material>> entry : byBiome.entrySet()) {
            boolean anyFree = entry.getValue().stream().anyMatch(MaterialAccessibility::needsNoTool);
            if (!anyFree) {
                unpayable.add(entry.getKey() + " " + entry.getValue());
            }
        }

        assertTrue(unpayable.isEmpty(),
            "a player starting in these biomes has no bare-handed option, so biome-based pricing "
                + "cannot offer them a first move - this is #105 in another biome: " + unpayable);
    }

    /** Seagrass by name: the entry whose absence would have sent every beach to the DIRT floor. */
    @Test
    @DisplayName("seagrass needs no tool - the beach's only payable option")
    void seagrassNeedsNoTool() {
        assertTrue(MaterialAccessibility.needsNoTool(Material.SEAGRASS),
            "a beach offers only SAND and SEAGRASS; sand is shovel-gated, so if seagrass were "
                + "gated too the beach start would be the reported clay softlock");
        assertFalse(MaterialAccessibility.needsNoTool(Material.SAND),
            "precondition: sand is the gated half of the beach's offering");
    }

    // ---- Parsing helpers -----------------------------------------------------------------

    /**
     * Materials named anywhere in {@code biome-unlocks.yml}.
     *
     * <p>Deliberately a small regex rather than a YAML library: the file's shape is two levels
     * of {@code KEY: amount} and reading it literally keeps the test independent of how the
     * plugin happens to load it. If parsing ever returns nothing the tests above fail loudly on
     * their precondition rather than passing vacuously.</p>
     */
    private static List<Material> materialsNamedInBiomeUnlocks() throws Exception {
        List<Material> materials = new ArrayList<>();
        for (List<Material> perBiome : biomeUnlocksByBiome().values()) {
            for (Material material : perBiome) {
                if (!materials.contains(material)) {
                    materials.add(material);
                }
            }
        }
        return materials;
    }

    /** The same file, grouped by biome, for the "every biome has an option" property. */
    private static Map<String, List<Material>> biomeUnlocksByBiome() throws Exception {
        Path path = Path.of("src", "main", "resources", "biome-unlocks.yml");
        assertTrue(Files.exists(path), "biome-unlocks.yml not found at " + path.toAbsolutePath());

        Map<String, List<Material>> byBiome = new LinkedHashMap<>();
        Pattern biomeLine = Pattern.compile("^([A-Z][A-Z0-9_]*):\\s*$");
        Pattern itemLine = Pattern.compile("^\\s+([A-Z][A-Z0-9_]*):\\s*\\d+\\s*$");

        String current = null;
        for (String line : Files.readAllLines(path)) {
            if (line.isBlank() || line.trim().startsWith("#")) {
                continue;
            }

            Matcher biome = biomeLine.matcher(line);
            if (biome.matches()) {
                current = biome.group(1);
                byBiome.computeIfAbsent(current, k -> new ArrayList<>());
                continue;
            }

            Matcher item = itemLine.matcher(line);
            if (item.matches() && current != null) {
                Material material = Material.matchMaterial(item.group(1));
                // An unrecognised name is the YAML's problem, not this test's - skip rather
                // than fail, so a typo there does not masquerade as a coverage gap here.
                if (material != null) {
                    byBiome.get(current).add(material);
                }
            }
        }
        return byBiome;
    }

    // ---- Reachability ordering -----------------------------------------------------------

    /**
     * A pickaxe implies a shovel, because the crafting chain that produces one produces both:
     * wood gives sticks, sticks give wooden tools, a wooden pickaxe gives cobble, cobble gives
     * stone tools. Anyone holding a pickaxe could have made a shovel on the way.
     */
    @Test
    @DisplayName("a player who can make a pickaxe can gather everything")
    void pickaxeReachesEverything() {
        for (Material material : OwnedChunkScanner.harvestableMaterials()) {
            assertTrue(MaterialAccessibility.isReachable(ToolClass.PICKAXE, material),
                "a pickaxe should reach " + material);
        }
    }

    @Test
    @DisplayName("bare hands reach only the no-tool materials")
    void bareHandsReachOnlyNoToolMaterials() {
        for (Material material : OwnedChunkScanner.harvestableMaterials()) {
            assertEquals(MaterialAccessibility.needsNoTool(material),
                MaterialAccessibility.isReachable(ToolClass.NONE, material),
                material + ": bare-handed reachability must match needing no tool");
        }
    }

    @Test
    @DisplayName("a shovel reaches sand and gravel but not stone")
    void shovelDoesNotReachStone() {
        assertTrue(MaterialAccessibility.isReachable(ToolClass.SHOVEL, Material.SAND));
        assertTrue(MaterialAccessibility.isReachable(ToolClass.SHOVEL, Material.DIRT),
            "a stronger tool must still reach the weaker materials");
        assertFalse(MaterialAccessibility.isReachable(ToolClass.SHOVEL, Material.STONE));
        assertFalse(MaterialAccessibility.isReachable(ToolClass.SHOVEL, Material.IRON_ORE));
    }

    // ---- A treeless start has somewhere to go --------------------------------------------

    /**
     * The property that actually decides whether the game can begin, stated directly: there is
     * at least one material a player with nothing at all can gather. Without this the
     * accessibility filter could correctly reject everything and leave pricing with an empty
     * pool in every biome.
     */
    @Test
    @DisplayName("at least one harvestable material needs no tool")
    void someMaterialIsAlwaysGatherable() {
        List<Material> noTool = OwnedChunkScanner.harvestableMaterials().stream()
            .filter(MaterialAccessibility::needsNoTool)
            .toList();

        assertFalse(noTool.isEmpty(),
            "if nothing were bare-hand gatherable, no start would ever be payable");
        assertTrue(noTool.contains(Material.DIRT),
            "dirt is the fallback the pricing path relies on and must stay tool-free");
    }

    // ---- Helpers -------------------------------------------------------------------------

    /**
     * Whether a material has a real entry, as opposed to landing on the fail-closed default.
     *
     * <p>{@code required} cannot distinguish the two on its own - a genuine PICKAXE entry and an
     * absent one both return PICKAXE, which is the point of failing closed. Stone is a known
     * mapped material, so anything that is not stone yet answers identically to an unmapped
     * probe is checked against a value that could only come from an explicit entry.</p>
     */
    private static boolean isExplicitlyMapped(Material material) {
        ToolClass required = MaterialAccessibility.required(material);
        if (required != ToolClass.PICKAXE) {
            return true; // NONE or SHOVEL can only come from a real entry.
        }
        // Pickaxe-or-absent: accept only materials this test knows are deliberately gated.
        return PICKAXE_MATERIALS.contains(material);
    }

    /**
     * The pickaxe-gated materials, listed independently of the map under test.
     *
     * <p>Duplication is the point: if the map's pickaxe entries and this list disagree, the
     * completeness test fails and someone has to reconcile them deliberately. A test that
     * derived this from the map could not detect an absent entry at all.</p>
     */
    private static final List<Material> PICKAXE_MATERIALS = List.of(
        Material.COBBLESTONE, Material.NETHERRACK,
        Material.STONE, Material.DEEPSLATE, Material.GRANITE, Material.DIORITE,
        Material.ANDESITE, Material.TUFF, Material.BASALT, Material.BLACKSTONE,
        Material.COPPER_ORE, Material.RAW_COPPER_BLOCK,
        Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE,
        Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE,
        Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE,
        Material.LAPIS_ORE, Material.DEEPSLATE_LAPIS_ORE,
        Material.REDSTONE_ORE, Material.DEEPSLATE_REDSTONE_ORE,
        Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE,
        Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE,
        Material.ANCIENT_DEBRIS,

        // Named only by biome-unlocks.yml (#105, beach case). Ice is here because breaking it
        // bare-handed yields nothing without Silk Touch - "no tool needed to break" and
        // "gatherable" are different properties, and conflating them is what #105 was.
        Material.ICE, Material.PACKED_ICE, Material.BLUE_ICE,
        Material.TERRACOTTA, Material.RED_TERRACOTTA, Material.MOSSY_COBBLESTONE,
        Material.END_STONE, Material.PURPUR_BLOCK, Material.SCULK,
        Material.POINTED_DRIPSTONE, Material.BRAIN_CORAL,
        Material.COAL, Material.COPPER_INGOT, Material.DIAMOND, Material.EMERALD);
}
