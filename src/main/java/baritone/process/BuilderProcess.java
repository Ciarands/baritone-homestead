/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.process;

import baritone.Baritone;
import baritone.api.process.IBuilderProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.schematic.FillSchematic;
import baritone.api.schematic.ISchematic;
import baritone.api.schematic.IStaticSchematic;
import baritone.api.schematic.MaskSchematic;
import baritone.api.schematic.MirroredSchematic;
import baritone.api.schematic.RotatedSchematic;
import baritone.api.schematic.SubstituteSchematic;
import baritone.api.schematic.format.ISchematicFormat;
import baritone.process.builder.BuildExecutor;
import baritone.process.builder.BuildPlanner;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.schematic.MapArtSchematic;
import baritone.utils.schematic.SchematicSystem;
import baritone.utils.schematic.SelectionSchematic;
import baritone.utils.schematic.litematica.LitematicaHelper;
import baritone.utils.schematic.schematica.SchematicaHelper;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.util.Tuple;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Builds a schematic: the {@link BuildPlanner} works out what to do and from where, layer by layer from the bottom, and
 * the {@link BuildExecutor} does it, checking each step against the world. This is the process around them: starting,
 * pausing, repeating, the decision log, and saying how it went.
 */
public final class BuilderProcess extends BaritoneProcessHelper implements IBuilderProcess {

    private String name;
    private ISchematic schematic;
    private Vec3i origin;
    private boolean paused;
    private int numRepeats;
    private List<BlockState> approxPlaceable = new ArrayList<>();
    private BuildExecutor executor;
    private final BuilderLog dlog = new BuilderLog();

    public BuilderProcess(Baritone baritone) {
        super(baritone);
    }

    @Override
    public void build(String name, ISchematic schematic, Vec3i origin) {
        this.name = name;
        this.schematic = schematic;
        if (!Baritone.settings().buildSubstitutes.value.isEmpty()) {
            this.schematic = new SubstituteSchematic(this.schematic, Baritone.settings().buildSubstitutes.value);
        }
        if (Baritone.settings().buildSchematicMirror.value != net.minecraft.world.level.block.Mirror.NONE) {
            this.schematic = new MirroredSchematic(this.schematic, Baritone.settings().buildSchematicMirror.value);
        }
        if (Baritone.settings().buildSchematicRotation.value != net.minecraft.world.level.block.Rotation.NONE) {
            this.schematic = new RotatedSchematic(this.schematic, Baritone.settings().buildSchematicRotation.value);
        }
        this.schematic = new MaskSchematic(this.schematic) {
            @Override
            public boolean partOfMask(int x, int y, int z, BlockState current) {
                // partOfMask is only called inside the schematic so desiredState is not null
                return !Baritone.settings().buildSkipBlocks.value.contains(this.desiredState(x, y, z, current, Collections.emptyList()).getBlock());
            }
        };
        int x = origin.getX();
        int y = origin.getY();
        int z = origin.getZ();
        if (Baritone.settings().schematicOrientationX.value) {
            x += schematic.widthX();
        }
        if (Baritone.settings().schematicOrientationY.value) {
            y += schematic.heightY();
        }
        if (Baritone.settings().schematicOrientationZ.value) {
            z += schematic.lengthZ();
        }
        this.origin = new Vec3i(x, y, z);
        this.paused = false;
        this.numRepeats = 0;
        start();
    }

    /**
     * (Re)starts building {@link #schematic} at {@link #origin}: the executor is made on the next tick, in the world
     */
    private void start() {
        this.executor = null;
        if (Baritone.settings().buildDebugLog.value) {
            dlog.open(baritone.getDirectory().resolve("builder.log"), "Building " + name + " at " + p(new BlockPos(origin)) + " size " + schematic.widthX() + "x" + schematic.heightY() + "x" + schematic.lengthZ()
                    + " | buildIgnoreDirection=" + Baritone.settings().buildIgnoreDirection.value + " okIfWater=" + Baritone.settings().okIfWater.value
                    + " allowInventory=" + Baritone.settings().allowInventory.value + " buildPlanOnly=" + Baritone.settings().buildPlanOnly.value
                    + " throwaways=" + Baritone.settings().acceptableThrowawayItems.value);
        } else {
            dlog.close();
        }
    }

    @Override
    public void resume() {
        paused = false;
        if (executor != null) {
            executor.resume();
        }
        dbgEvent("resumed");
    }

    @Override
    public void pause() {
        paused = true;
        dbgEvent("paused (asked to)");
    }

    @Override
    public boolean isPaused() {
        return paused;
    }

    @Override
    public boolean build(String name, File schematic, Vec3i origin) {
        Optional<ISchematicFormat> format = SchematicSystem.INSTANCE.getByFile(schematic);
        if (!format.isPresent()) {
            return false;
        }
        IStaticSchematic parsed;
        try {
            parsed = format.get().parse(new FileInputStream(schematic));
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        ISchematic schem = applyMapArtAndSelection(origin, parsed);
        build(name, schem, origin);
        return true;
    }

    private ISchematic applyMapArtAndSelection(Vec3i origin, IStaticSchematic parsed) {
        ISchematic schematic = parsed;
        if (Baritone.settings().mapArtMode.value) {
            schematic = new MapArtSchematic(parsed);
        }
        if (Baritone.settings().buildOnlySelection.value) {
            schematic = new SelectionSchematic(schematic, origin, baritone.getSelectionManager().getSelections());
        }
        return schematic;
    }

    @Override
    public void buildOpenSchematic() {
        if (SchematicaHelper.isSchematicaPresent()) {
            Optional<Tuple<IStaticSchematic, BlockPos>> schematic = SchematicaHelper.getOpenSchematic();
            if (schematic.isPresent()) {
                IStaticSchematic raw = schematic.get().getA();
                BlockPos origin = schematic.get().getB();
                ISchematic schem = applyMapArtAndSelection(origin, raw);
                this.build(raw.toString(), schem, origin);
            } else {
                logDirect("No schematic currently open");
            }
        } else {
            logDirect("Schematica is not present");
        }
    }

    @Override
    public void buildOpenLitematic(int i) {
        if (LitematicaHelper.isLitematicaPresent()) {
            //if java.lang.NoSuchMethodError is thrown see comment in SchematicPlacementManager
            if (LitematicaHelper.hasLoadedSchematic(i)) {
                Tuple<IStaticSchematic, Vec3i> schematic = LitematicaHelper.getSchematic(i);
                Vec3i correctedOrigin = schematic.getB();
                ISchematic schematic2 = applyMapArtAndSelection(correctedOrigin, schematic.getA());
                build(schematic.getA().toString(), schematic2, correctedOrigin);
            } else {
                logDirect(String.format("List of placements has no entry %s", i + 1));
            }
        } else {
            logDirect("Litematica is not present");
        }
    }

    @Override
    public void clearArea(BlockPos corner1, BlockPos corner2) {
        BlockPos origin = new BlockPos(Math.min(corner1.getX(), corner2.getX()), Math.min(corner1.getY(), corner2.getY()), Math.min(corner1.getZ(), corner2.getZ()));
        int widthX = Math.abs(corner1.getX() - corner2.getX()) + 1;
        int heightY = Math.abs(corner1.getY() - corner2.getY()) + 1;
        int lengthZ = Math.abs(corner1.getZ() - corner2.getZ()) + 1;
        build("clear area", new FillSchematic(widthX, heightY, lengthZ, Blocks.AIR.defaultBlockState()), origin);
    }

    @Override
    public List<BlockState> getApproxPlaceable() {
        return new ArrayList<>(approxPlaceable);
    }

    @Override
    public Optional<Integer> getMinLayer() {
        BlockPos stand = executor == null ? null : executor.currentStand();
        return stand == null ? Optional.empty() : Optional.of(Math.max(0, stand.getY() - origin.getY()));
    }

    @Override
    public Optional<Integer> getMaxLayer() {
        return schematic == null ? Optional.empty() : Optional.of(schematic.heightY());
    }

    @Override
    public boolean isActive() {
        return schematic != null;
    }

    /**
     * @return What the build wants at that position, or null if nothing (or air): for movements putting down blocks
     */
    public BlockState placeAt(int x, int y, int z, BlockState current) {
        if (!isActive()) {
            return null;
        }
        if (!schematic.inSchematic(x - origin.getX(), y - origin.getY(), z - origin.getZ(), current)) {
            return null;
        }
        BlockState state = schematic.desiredState(x - origin.getX(), y - origin.getY(), z - origin.getZ(), current, this.approxPlaceable);
        if (state.getBlock() instanceof AirBlock) {
            return null;
        }
        return state;
    }

    public boolean placementPlausible(BlockPos pos, BlockState state) {
        VoxelShape voxelshape = state.getCollisionShape(ctx.world(), pos);
        return voxelshape.isEmpty() || ctx.world().isUnobstructed(null, voxelshape.move(pos.getX(), pos.getY(), pos.getZ()));
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (dlog.isOpen()) {
            dlog.beginTick(ctx.world().getGameTime());
        }
        PathingCommand command = tick(calcFailed, isSafeToCancel);
        if (dlog.isOpen()) {
            dbg("=> " + (command == null ? "stop" : command.commandType + (command.goal == null ? "" : " " + command.goal)));
            dlog.endTick();
        }
        return command;
    }

    private PathingCommand tick(boolean calcFailed, boolean isSafeToCancel) {
        approxPlaceable = approxPlaceable();
        baritone.getInputOverrideHandler().clearAllKeys();
        if (dlog.isOpen()) {
            LocalPlayer player = ctx.player();
            dbg("at " + p(ctx.playerFeet()) + " (" + String.format("%.2f,%.2f,%.2f", player.getX(), player.getY(), player.getZ()) + ")"
                    + (player.onGround() ? " ground" : "") + (player.onClimbable() ? " climbing" : "") + (player.isShiftKeyDown() ? " sneaking" : "")
                    + (baritone.getPathingBehavior().isPathing() ? " pathing to " + baritone.getPathingBehavior().getPath().map(path -> p(path.getDest())).orElse("?") : " not pathing")
                    + (calcFailed ? " | last path calc FAILED" : "") + (isSafeToCancel ? "" : " | not safe to cancel")
                    + " | slot " + player.getInventory().selected + " holding " + player.getMainHandItem().getItem());
        }
        if (paused) {
            dbg("paused");
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        if (executor == null) {
            executor = new BuildExecutor(baritone, schematic, new BlockPos(origin), new BuildExecutor.Log() {
                @Override
                public void line(String line) {
                    dbg(line);
                }

                @Override
                public void event(String line) {
                    dbgEvent(line);
                }
            });
            if (Baritone.settings().buildPlanOnly.value) {
                writePlan();
                onLostControl();
                return null;
            }
        }
        PathingCommand command = executor.tick(calcFailed, isSafeToCancel);
        if (command != null) {
            return command;
        }
        return finished();
    }

    /**
     * Nothing more can be done: missing materials (pause, to be resumed once they're there), the next repeat, or done
     */
    private PathingCommand finished() {
        Map<BlockPos, String> notDone = executor.planner().unbuildable();
        Map<String, Integer> missing = new TreeMap<>();
        notDone.values().stream().filter(why -> why.startsWith(BuildPlanner.MISSING))
                .forEach(why -> missing.merge(why.substring(BuildPlanner.MISSING.length()), 1, Integer::sum));
        if (!missing.isEmpty()) {
            logDirect("Missing materials for " + missing.values().stream().mapToInt(Integer::intValue).sum() + " blocks: " + missing
                    + ". Get them and #resume");
            dbgEvent("paused: missing materials " + missing);
            paused = true;
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        Vec3i repeat = Baritone.settings().buildRepeat.value;
        int max = Baritone.settings().buildRepeatCount.value;
        numRepeats++;
        BuildExecutor.Audit audit = report();
        if (!repeat.equals(Vec3i.ZERO) && (max == -1 || numRepeats < max)) {
            origin = new BlockPos(origin).offset(repeat);
            logDirect("Repeating build in vector " + repeat + ", new origin is " + origin);
            start();
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        if (Baritone.settings().notificationOnBuildFinished.value) {
            logNotification("Done building: " + audit.exact + "/" + audit.total + " exactly as in the schematic", false);
        }
        onLostControl();
        return null;
    }

    /**
     * Says how the build came out: checked against the world exactly, not taken from what the builder thinks it did
     */
    private BuildExecutor.Audit report() {
        BuildExecutor.Audit audit = executor.audit();
        String head = audit.exact + "/" + audit.total + " exactly as in the schematic";
        if (audit.notExact.isEmpty()) {
            logDirect("Done building: " + head);
            dbgEvent("done building: " + head);
            return audit;
        }
        logDirect("Done building: " + head + ". Not exactly:");
        StringBuilder all = new StringBuilder();
        audit.notExact.forEach((why, positions) -> {
            StringBuilder where = new StringBuilder();
            for (int i = 0; i < Math.min(positions.size(), 8); i++) {
                where.append(i == 0 ? "" : " ").append(p(positions.get(i)));
            }
            if (positions.size() > 8) {
                where.append(" and ").append(positions.size() - 8).append(" more");
            }
            logDirect("- " + positions.size() + " " + why + ": " + where);
            // (the log gets every position, chat the first few)
            all.append(" | ").append(positions.size()).append(" ").append(why).append(": ")
                    .append(positions.stream().map(BuilderProcess::p).collect(java.util.stream.Collectors.joining(" ")));
        });
        dbgEvent("done building: " + head + all);
        return audit;
    }

    private void writePlan() {
        List<String> lines = executor.simulate();
        Path file = baritone.getDirectory().resolve("build-plan.txt");
        try {
            Files.write(file, lines, StandardCharsets.UTF_8);
            logDirect(lines.get(1));
            logDirect("Plan written to " + file);
        } catch (IOException e) {
            logDirect("Couldn't write the plan to " + file + ": " + e);
        }
        dbgEvent("plan only: " + lines.get(1));
    }

    private List<BlockState> approxPlaceable() {
        List<BlockState> result = new ArrayList<>();
        for (ItemStack stack : ctx.player().getInventory().items) {
            if (!stack.isEmpty() && stack.getItem() instanceof BlockItem) {
                result.add(((BlockItem) stack.getItem()).getBlock().defaultBlockState());
            }
        }
        return result;
    }

    @Override
    public void onLostControl() {
        if (schematic != null) {
            dbgEvent("stopped" + (executor == null ? "" : " (" + executor.confirmed() + " steps confirmed)"));
        }
        name = null;
        schematic = null;
        executor = null;
        paused = false;
        dlog.close();
    }

    @Override
    public String displayName0() {
        return paused ? "Builder Paused" : "Building " + name;
    }

    // --- decision log (buildDebugLog)

    /**
     * A line of this tick's decisions
     */
    private void dbg(String line) {
        dlog.add(line);
    }

    /**
     * Something that happened, written at once
     */
    private void dbgEvent(String line) {
        if (dlog.isOpen() && ctx.world() != null) {
            dlog.event(ctx.world().getGameTime(), line);
        }
    }

    private static String p(BlockPos pos) {
        return pos == null ? "-" : pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
