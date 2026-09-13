package me.chunklock.managers;

import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import me.chunklock.models.Difficulty;

import java.util.UUID;
import java.util.logging.Level;

public class ChunkEvaluator {

    private final PlayerDataManager playerDataManager;
    private final ChunkValueRegistry chunkValueRegistry;
    private final java.util.logging.Logger logger;

    public ChunkEvaluator(PlayerDataManager playerDataManager, ChunkValueRegistry chunkValueRegistry, java.util.logging.Logger logger) {
        this.playerDataManager = playerDataManager;
        this.chunkValueRegistry = chunkValueRegistry;
        this.logger = logger;
    }

    public ChunkValueData evaluateChunk(UUID playerId, Chunk chunk) {
        try {
            if (chunk == null) {
                logger.warning("Attempted to evaluate null chunk" + 
                    (playerId != null ? " for player " + playerId : ""));
                return new ChunkValueData(0, Difficulty.EASY, Biome.PLAINS);
            }

            if (chunk.getWorld() == null) {
                logger.warning("Chunk has null world" + 
                    (playerId != null ? " for player " + playerId : ""));
                return new ChunkValueData(0, Difficulty.EASY, Biome.PLAINS);
            }

            int score = 0;

            // 1. Distance factor with improved error handling
            try {
                Chunk originChunk = getOriginChunk(playerId, chunk);
                int dx = originChunk.getX() - chunk.getX();
                int dz = originChunk.getZ() - chunk.getZ();
                int distance = Math.abs(dx) + Math.abs(dz);
                score += distance * 5;
            } catch (IllegalArgumentException e) {
                logger.log(Level.FINE, "Invalid argument calculating distance for chunk evaluation", e);
                // Continue with score = 0 for distance
            } catch (IllegalStateException e) {
                logger.log(Level.FINE, "Invalid state calculating distance for chunk evaluation", e);
                // Continue with score = 0 for distance
            } catch (Exception e) {
                logger.log(Level.WARNING, "Unexpected error calculating distance for chunk evaluation", e);
                // Continue with score = 0 for distance
            }

            // 2. Biome factor with improved error handling
            Biome biome = Biome.PLAINS; // Default fallback
            try {
                biome = getBiomeSafely(chunk);
                score += chunkValueRegistry.getBiomeWeight(biome);
            } catch (IllegalArgumentException e) {
                logger.log(Level.FINE, "Invalid biome data for chunk evaluation", e);
                score += chunkValueRegistry.getBiomeWeight(Biome.PLAINS);
            } catch (IllegalStateException e) {
                logger.log(Level.FINE, "Invalid world state getting biome for chunk evaluation", e);
                score += chunkValueRegistry.getBiomeWeight(Biome.PLAINS);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Unexpected error getting biome for chunk evaluation", e);
                score += chunkValueRegistry.getBiomeWeight(Biome.PLAINS);
            }

            // 3. Block scan factor with improved error handling
            try {
                score += scanSurfaceBlocks(chunk);
            } catch (IllegalArgumentException e) {
                logger.log(Level.FINE, "Invalid arguments scanning surface blocks for chunk evaluation", e);
                // Continue without surface block score
            } catch (IllegalStateException e) {
                logger.log(Level.FINE, "Invalid world state scanning surface blocks for chunk evaluation", e);
                // Continue without surface block score
            } catch (Exception e) {
                logger.log(Level.WARNING, "Unexpected error scanning surface blocks for chunk evaluation", e);
                // Continue without surface block score
            }

            // 4. Determine difficulty with bounds checking
            Difficulty difficulty = calculateDifficulty(score);

            return new ChunkValueData(score, difficulty, biome);

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Critical error in chunk evaluation" + 
                (playerId != null ? " for player " + playerId : ""), e);
            return new ChunkValueData(0, Difficulty.EASY, Biome.PLAINS);
        }
    }

    private Chunk getOriginChunk(UUID playerId, Chunk fallbackChunk) throws IllegalArgumentException, IllegalStateException {
        try {
            if (playerId != null && playerDataManager != null && playerDataManager.getChunkSpawn(playerId) != null) {
                var spawnLocation = playerDataManager.getChunkSpawn(playerId);
                if (spawnLocation != null && spawnLocation.getWorld() != null) {
                    return spawnLocation.getChunk();
                }
            }
        } catch (IllegalArgumentException e) {
            logger.log(Level.FINE, "Invalid player ID or spawn data for player " + playerId, e);
            throw e;
        } catch (IllegalStateException e) {
            logger.log(Level.FINE, "Invalid world state getting origin chunk for player " + playerId, e);
            throw e;
        } catch (Exception e) {
            logger.log(Level.WARNING, "Unexpected error getting origin chunk for player " + playerId, e);
        }
        
        if (fallbackChunk == null) {
            throw new IllegalArgumentException("Both player origin and fallback chunk are null");
        }
        
        return fallbackChunk;
    }

    private Biome getBiomeSafely(Chunk chunk) throws IllegalArgumentException, IllegalStateException {
        if (chunk == null) {
            throw new IllegalArgumentException("Chunk cannot be null");
        }
        
        if (chunk.getWorld() == null) {
            throw new IllegalStateException("Chunk world is null");
        }

        try {
            // Try to get biome from center of chunk at surface level
            int surfaceY = chunk.getWorld().getHighestBlockYAt(chunk.getBlock(8, 0, 8).getLocation());
            surfaceY = Math.max(chunk.getWorld().getMinHeight(), Math.min(surfaceY, chunk.getWorld().getMaxHeight() - 1));
            
            Block centerBlock = chunk.getBlock(8, surfaceY, 8);
            if (centerBlock != null) {
                return centerBlock.getBiome();
            }
        } catch (IllegalArgumentException e) {
            logger.log(Level.FINE, "Invalid block coordinates getting biome", e);
            // Fall through to fallback
        } catch (IllegalStateException e) {
            logger.log(Level.FINE, "Invalid world state getting surface biome", e);
            // Fall through to fallback
        } catch (Exception e) {
            logger.log(Level.FINE, "Error getting surface biome", e);
            // Fall through to fallback
        }

        // Fallback: try getting biome from a simpler location
        try {
            Block fallbackBlock = chunk.getBlock(8, 64, 8);
            if (fallbackBlock != null) {
                return fallbackBlock.getBiome();
            }
        } catch (IllegalArgumentException e) {
            logger.log(Level.FINE, "Invalid fallback block coordinates", e);
        } catch (Exception e) {
            logger.log(Level.FINE, "Error getting fallback biome", e);
        }

        // Final fallback
        logger.log(Level.WARNING, "Failed to get biome from chunk, using PLAINS as fallback");
        return Biome.PLAINS;
    }

    private int scanSurfaceBlocks(Chunk chunk) throws IllegalArgumentException, IllegalStateException {
        if (chunk == null) {
            throw new IllegalArgumentException("Chunk cannot be null");
        }
        
        if (chunk.getWorld() == null) {
            throw new IllegalStateException("Chunk world is null");
        }

        int score = 0;
        int successfulScans = 0;
        
        // Sample fewer blocks for better performance (every 4 blocks instead of dense sampling)
        for (int x = 0; x < 16; x += 4) {
            for (int z = 0; z < 16; z += 4) {
                try {
                    int y = chunk.getWorld().getHighestBlockYAt(chunk.getBlock(x, 0, z).getLocation());
                    y = Math.max(chunk.getWorld().getMinHeight(), Math.min(y - 1, chunk.getWorld().getMaxHeight() - 1));
                    
                    Block block = chunk.getBlock(x, y, z);
                    if (block != null && block.getType() != null) {
                        Material mat = block.getType();
                        score += chunkValueRegistry.getBlockWeight(mat);
                        successfulScans++;
                    }
                } catch (IllegalArgumentException e) {
                    logger.log(Level.FINE, "Invalid block coordinates at " + x + "," + z + " in chunk", e);
                    // Continue with other blocks
                } catch (IllegalStateException e) {
                    logger.log(Level.FINE, "Invalid world state scanning block at " + x + "," + z + " in chunk", e);
                    // Continue with other blocks
                } catch (Exception e) {
                    logger.log(Level.FINE, "Error scanning block at " + x + "," + z + " in chunk", e);
                    // Continue with other blocks
                }
            }
        }
        
        if (successfulScans == 0) {
            logger.warning("No blocks could be scanned in chunk surface scan");
        }
        
        return score;
    }

    private Difficulty calculateDifficulty(int score) {
        try {
            if (chunkValueRegistry == null) {
                logger.warning("ChunkValueRegistry is null, using NORMAL difficulty as fallback");
                return Difficulty.NORMAL;
            }

            int easyMax = chunkValueRegistry.getThreshold("easy");
            int normalMax = chunkValueRegistry.getThreshold("normal");
            int hardMax = chunkValueRegistry.getThreshold("hard");

            // Validate thresholds are in order
            if (easyMax >= normalMax || normalMax >= hardMax) {
                logger.warning("Invalid difficulty thresholds, using defaults");
                return score < 30 ? Difficulty.EASY : 
                       score < 60 ? Difficulty.NORMAL : 
                       score < 90 ? Difficulty.HARD : Difficulty.IMPOSSIBLE;
            }

            if (score < easyMax) {
                return Difficulty.EASY;
            } else if (score < normalMax) {
                return Difficulty.NORMAL;
            } else if (score < hardMax) {
                return Difficulty.HARD;
            } else {
                return Difficulty.IMPOSSIBLE;
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error calculating difficulty, using NORMAL as fallback", e);
            return Difficulty.NORMAL;
        }
    }

    /**
     * Validates that the evaluation parameters are reasonable
     */
    public boolean validateChunkEvaluation(UUID playerId, Chunk chunk) {
        try {
            if (chunk == null) return false;
            if (chunk.getWorld() == null) return false;
            
            // Validate chunk coordinates are reasonable
            int x = chunk.getX();
            int z = chunk.getZ();
            if (Math.abs(x) > 30000 || Math.abs(z) > 30000) {
                logger.warning("Chunk coordinates out of reasonable range: " + x + "," + z);
                return false;
            }
            
            return true;
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error validating chunk evaluation parameters", e);
            return false;
        }
    }

    /**
     * Check if a chunk is suitable for spawning (not water-dominated).
     * Returns true if the chunk has less than 90% water/water-based blocks.
     */
    public boolean isChunkSuitableForSpawning(Chunk chunk) {
        try {
            if (chunk == null || chunk.getWorld() == null) {
                return false;
            }

            int totalBlocks = 0;
            int waterBlocks = 0;
            
            // Sample surface blocks across the chunk (every 2 blocks for better coverage)
            for (int x = 0; x < 16; x += 2) {
                for (int z = 0; z < 16; z += 2) {
                    try {
                        // Get the highest block at this position
                        int y = chunk.getWorld().getHighestBlockYAt(chunk.getBlock(x, 0, z).getLocation());
                        y = Math.max(chunk.getWorld().getMinHeight(), Math.min(y, chunk.getWorld().getMaxHeight() - 1));
                        
                        Block surfaceBlock = chunk.getBlock(x, y, z);
                        if (surfaceBlock != null && surfaceBlock.getType() != null) {
                            totalBlocks++;
                            Material mat = surfaceBlock.getType();
                            
                            // Check for water and water-based blocks
                            if (isWaterRelatedBlock(mat)) {
                                waterBlocks++;
                            }
                        }
                    } catch (Exception e) {
                        logger.log(Level.FINE, "Error checking block at " + x + "," + z + " for spawn suitability", e);
                        // Continue with other blocks
                    }
                }
            }
            
            if (totalBlocks == 0) {
                logger.warning("No blocks could be scanned for spawn suitability check");
                return false; // If we can't scan, assume unsafe
            }
            
            double waterPercentage = (double) waterBlocks / totalBlocks;
            boolean suitable = waterPercentage < 0.90; // Less than 90% water
            
            logger.fine("Chunk spawn suitability: " + waterBlocks + "/" + totalBlocks + 
                " water blocks (" + String.format("%.1f", waterPercentage * 100) + "%) - " + (suitable ? "SUITABLE" : "TOO MUCH WATER"));
            
            return suitable;
            
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error checking chunk spawn suitability", e);
            return false; // If error, assume unsafe
        }
    }

    /**
     * How far below the surface {@link #hasBareHandAccessibleMaterial} looks.
     *
     * <p>Deep enough to find the dirt under a few blocks of snow, shallow enough that the check
     * stays cheap on the main thread. A player with no tools is not reaching anything further
     * down than this anyway.</p>
     */
    private static final int SURFACE_PROBE_DEPTH = 8;

    /**
     * Whether a starting chunk offers any material a player with nothing can gather (#105).
     *
     * <p>Spawn selection scores candidates on difficulty and takes a low one. Nothing checked
     * that the chunk contains anything <em>payable from an empty inventory</em>, and those are
     * not the same property: an icy mountain chunk scores as easy and offers only gravel, stone
     * and ice. Gravel needs a shovel, a shovel needs wood, and there were no trees - so the
     * reported start had no first move at all.</p>
     *
     * <p>Accessibility filtering in pricing (#105 task 3) makes a payable price <em>likely</em>;
     * this makes the first one <em>certain</em>, by declining to start a player somewhere the
     * filter would have nothing to offer.</p>
     *
     * <p><strong>Looks below the surface, but not far.</strong> The water check above samples
     * the surface block only, which is right for its question and wrong for this one: an icy
     * mountain has snow on top and the dirt that would save it a few blocks down, so a
     * surface-only sample would reject perfectly good chunks. {@link #SURFACE_PROBE_DEPTH}
     * bounds how deep it looks, because this runs on the main thread inside a search that tries
     * up to a hundred candidates.</p>
     *
     * <p>Returns true on any error. A chunk that cannot be scanned must not be rejected on
     * suspicion - the failure mode of this check returning false too often is that spawn search
     * finds nothing at all and the player cannot start, which is worse than the softlock.</p>
     */
    public boolean hasBareHandAccessibleMaterial(Chunk chunk) {
        try {
            if (chunk == null || chunk.getWorld() == null) {
                return true; // Cannot tell; do not block spawning on it.
            }

            World world = chunk.getWorld();
            int minY = world.getMinHeight();

            // Sampled from the surface down rather than over the full column height, and only
            // to SURFACE_PROBE_DEPTH. This check runs on the main thread inside a spawn search
            // that tries up to a hundred candidates, so a full-column scan of every one would
            // be tens of thousands of block reads per rejected chunk - and the rejection case
            // is exactly the cold biome this exists to catch. Anything a new player can reach
            // without mining is within a few blocks of the surface anyway.
            for (int x = 0; x < 16; x += 4) {
                for (int z = 0; z < 16; z += 4) {
                    int worldX = chunk.getX() * 16 + x;
                    int worldZ = chunk.getZ() * 16 + z;

                    int surfaceY = world.getHighestBlockYAt(worldX, worldZ);
                    int floor = Math.max(minY, surfaceY - SURFACE_PROBE_DEPTH);

                    for (int y = surfaceY; y >= floor; y--) {
                        Material mat = world.getBlockAt(worldX, y, worldZ).getType();
                        if (mat == Material.AIR || mat == Material.CAVE_AIR || mat == Material.VOID_AIR) {
                            continue;
                        }
                        if (me.chunklock.economy.calculation.OwnedChunkScanner.isHarvestable(mat)
                            && me.chunklock.economy.calculation.MaterialAccessibility.needsNoTool(mat)) {
                            return true;
                        }
                    }
                }
            }

            logger.fine("Chunk " + chunk.getX() + "," + chunk.getZ()
                + " has no bare-hand gatherable material - unsuitable as a starting chunk (#105)");
            return false;

        } catch (Exception e) {
            logger.log(Level.FINE, "Error checking starting-chunk accessibility", e);
            return true; // Never block spawning because the check itself failed.
        }
    }

    /**
     * Whether a start here offers a payable <em>first move</em> (#105, beach case).
     *
     * <h3>Why the starting chunk alone is the wrong question</h3>
     *
     * <p>{@link #hasBareHandAccessibleMaterial} asks whether the player's own chunk contains
     * something gatherable bare-handed. That is necessary but <strong>not sufficient</strong>,
     * because a player does not pay with their own chunk - they pay to unlock a
     * <em>neighbour</em>, priced from that neighbour's contents.</p>
     *
     * <p>A beach start proved the gap: sand on the surface, dirt a few blocks down - so the
     * starting chunk passed - while every surrounding chunk priced in clay, which needs a shovel
     * the treeless biome could not supply. The chunk was fine and the neighbourhood was a
     * softlock, which is exactly the pair the original check could not distinguish.</p>
     *
     * <p>So this asks the question the plan for #105 originally specified: the starting chunk
     * must be gatherable <em>and</em> at least one of the four orthogonal neighbours must be too.
     * Diagonals are excluded deliberately - the first unlock is always orthogonal.</p>
     *
     * <p><strong>Only inspects chunks that are already loaded.</strong> This runs on the main
     * thread inside a search over many candidates, and forcing four neighbours to generate per
     * candidate is the cost that made an earlier full-column scan untenable. An unloaded
     * neighbour is treated as acceptable, which keeps the check failing <em>open</em>: the
     * failure mode of rejecting too eagerly is that spawn search finds nowhere to put the
     * player at all.</p>
     */
    public boolean hasPayableFirstMove(Chunk chunk) {
        try {
            if (chunk == null || chunk.getWorld() == null) {
                return true; // Cannot tell; do not block spawning on it.
            }

            if (!hasBareHandAccessibleMaterial(chunk)) {
                return false;
            }

            World world = chunk.getWorld();
            int[][] offsets = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

            boolean sawLoadedNeighbour = false;
            for (int[] offset : offsets) {
                int nx = chunk.getX() + offset[0];
                int nz = chunk.getZ() + offset[1];

                // Deliberately not world.getChunkAt(): that generates the chunk on the spot.
                if (!world.isChunkLoaded(nx, nz)) {
                    continue;
                }

                sawLoadedNeighbour = true;
                if (hasBareHandAccessibleMaterial(world.getChunkAt(nx, nz))) {
                    return true;
                }
            }

            if (!sawLoadedNeighbour) {
                // Nothing to judge by. Fail open rather than reject a possibly fine start.
                return true;
            }

            logger.fine("Chunk " + chunk.getX() + "," + chunk.getZ()
                + " is gatherable but no loaded neighbour is - no payable first move (#105)");
            return false;

        } catch (Exception e) {
            logger.log(Level.FINE, "Error checking first-move payability", e);
            return true; // Never block spawning because the check itself failed.
        }
    }

    /**
     * Check if a material is water-related (should be avoided for spawning)
     */
    private boolean isWaterRelatedBlock(Material material) {
        return material == Material.WATER ||
               material == Material.KELP ||
               material == Material.KELP_PLANT ||
               material == Material.SEAGRASS ||
               material == Material.TALL_SEAGRASS ||
               material == Material.SEA_PICKLE ||
               me.chunklock.util.item.MaterialUtil.getMaterialName(material).contains("CORAL") ||
               me.chunklock.util.item.MaterialUtil.getMaterialName(material).contains("SPONGE");
    }

    /**
     * Optimized evaluation for caching (without player-specific distance calculation)
     */
    public ChunkValueData evaluateChunkForCache(Chunk chunk) {
        try {
            if (chunk == null || chunk.getWorld() == null) {
                return new ChunkValueData(0, Difficulty.EASY, Biome.PLAINS);
            }

            int score = 0;

            // Skip distance calculation for cached evaluation
            // Only include biome and block factors

            // Biome factor
            Biome biome = Biome.PLAINS;
            try {
                biome = getBiomeSafely(chunk);
                score += chunkValueRegistry.getBiomeWeight(biome);
            } catch (Exception e) {
                score += chunkValueRegistry.getBiomeWeight(Biome.PLAINS);
            }

            // Block scan factor
            try {
                score += scanSurfaceBlocks(chunk);
            } catch (Exception e) {
                // Continue without surface block score
            }

            Difficulty difficulty = calculateDifficulty(score);
            return new ChunkValueData(score, difficulty, biome);

        } catch (Exception e) {
            logger.log(Level.WARNING, "Error in cached chunk evaluation", e);
            return new ChunkValueData(0, Difficulty.EASY, Biome.PLAINS);
        }
    }

    public static class ChunkValueData {
        public final int score;
        public final Difficulty difficulty;
        public final Biome biome;

        public ChunkValueData(int score, Difficulty difficulty, Biome biome) {
            this.score = Math.max(0, score); // Ensure non-negative score
            this.difficulty = difficulty != null ? difficulty : Difficulty.EASY;
            this.biome = biome != null ? biome : Biome.PLAINS;
        }

        @Override
        public String toString() {
            return String.format("ChunkValueData{score=%d, difficulty=%s, biome=%s}", score, difficulty, biome);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            ChunkValueData that = (ChunkValueData) obj;
            return score == that.score && 
                   difficulty == that.difficulty && 
                   biome == that.biome;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(score, difficulty, biome);
        }
    }
}