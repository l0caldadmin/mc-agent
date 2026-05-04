package adris.altoclef.commands.random;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import adris.altoclef.AltoClef;
import adris.altoclef.commands.BlockScanner;
import adris.altoclef.trackers.EntityTracker;
import adris.altoclef.commandsystem.Arg;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.CommandException;
import adris.altoclef.util.helpers.FuzzySearchHelper;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

public class ScanCommand extends Command {

    public ScanCommand() throws CommandException {
        super("scan", "Locates nearest block", new Arg<>(String.class, "block", "DIRT", 0));
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        String search = parser.get(String.class);

        if (tryScanEntity(mod, search)) {
            finish();
            return;
        }

        Field[] declaredFields = Blocks.class.getDeclaredFields();
        Block block = null;

        List<String> allBlockNames = new ArrayList<>();

        for (Field field : declaredFields) {
            field.setAccessible(true);
            try {
                String fieldName = field.getName();
                allBlockNames.add(fieldName.toLowerCase());
                if (fieldName.equalsIgnoreCase(search)) {
                    block = (Block) field.get(Blocks.class);
                }
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
            field.setAccessible(false);
        }

        if (block == null) {
            String closest = FuzzySearchHelper.getClosestMatchMinecraftItems(search, allBlockNames);
            mod.log("No nearby mob or block matched \"" + search + "\". Perhaps you meant \"" + closest + "\"?");
            finish();
            return;
        }

        BlockScanner blockScanner = mod.getBlockScanner();
        Optional<BlockPos> p = blockScanner.getNearestBlock(block,mod.getPlayer().getPos());
        if (p.isPresent()) {
            mod.log("Closest block " + search + ": " + p.get().toString());
        } else {
            mod.log("No blocks of type " + search + " found nearby.");
        }
        finish();
    }

    private boolean tryScanEntity(AltoClef mod, String search) {
        EntityTracker entityTracker = mod.getEntityTracker();
        List<Entity> nearby = entityTracker.getCloseEntities();
        if (nearby == null || nearby.isEmpty()) {
            return false;
        }

        String normalizedSearch = normalize(search);
        List<String> entityNames = new ArrayList<>();
        Entity closestMatch = null;
        String closestName = null;

        for (Entity entity : nearby) {
            String registryName = Registries.ENTITY_TYPE.getId(entity.getType()).getPath();
            String displayName = entity.getName().getString();
            entityNames.add(registryName);
            entityNames.add(normalize(displayName));

            boolean exactMatch = normalizedSearch.equals(normalize(registryName))
                    || normalizedSearch.equals(normalize(displayName));
            if (!exactMatch) {
                continue;
            }

            if (closestMatch == null || entity.squaredDistanceTo(mod.getPlayer()) < closestMatch.squaredDistanceTo(mod.getPlayer())) {
                closestMatch = entity;
                closestName = registryName;
            }
        }

        if (closestMatch == null) {
            String fuzzy = FuzzySearchHelper.getClosestMatchMinecraftItems(normalizedSearch, entityNames);
            if (fuzzy == null) {
                return false;
            }

            for (Entity entity : nearby) {
                String registryName = normalize(Registries.ENTITY_TYPE.getId(entity.getType()).getPath());
                String displayName = normalize(entity.getName().getString());
                if (fuzzy.equals(registryName) || fuzzy.equals(displayName)) {
                    if (closestMatch == null || entity.squaredDistanceTo(mod.getPlayer()) < closestMatch.squaredDistanceTo(mod.getPlayer())) {
                        closestMatch = entity;
                        closestName = registryName;
                    }
                }
            }
        }

        if (closestMatch == null) {
            return false;
        }

        Vec3d pos = closestMatch.getPos();
        mod.log("Closest mob/entity " + closestName + ": (" + (int) pos.x + ", " + (int) pos.y + ", " + (int) pos.z + ")");
        return true;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase().replace(' ', '_');
    }

}
