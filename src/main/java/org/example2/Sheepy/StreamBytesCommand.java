package org.example2.Sheepy;

import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import static org.example2.Sheepy.AnimLoader.plugin;

public class StreamBytesCommand implements CommandExecutor {

    static class Animation {
        private boolean dontLoad = false;
        private final BlockingQueue<AnimationParticle[]> frames;

        private Animation(BlockingQueue<AnimationParticle[]> frames) {
            this.frames = frames;
        }
    }

    static class AnimationParticle {
        private final float[] position = new float[3];
        private Color color = Color.BLACK;
        private byte pscale = 0;
    }

    public enum ParticleType {
        REDSTONE,
        multiple_dust,
        SPELL_MOB,
        SPELL_MOB_AMBIENT,
        DUST_TRANSITION,
    }

    static final List<Animation> animations = new ArrayList<>();

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        // args: <filename> [particle scale] [animation scale] [particle type] [particle count per tick]

        if (!(sender instanceof Player) && !(sender instanceof BlockCommandSender)) {
            sender.sendMessage(ChatColor.RED + "only players can use this command - " + sender.getClass());
//            return true;
        }

        if (args.length == 0) {
            sender.sendMessage("please specify file");
            return true;
        }

        stopParticleTasks();

        String fileName = args[0].replaceAll("[^a-zA-Z0-9]", "") + ".shny";
//        ParticleType particleType = args.length > 3 ? ParticleType.valueOf(args[3]) : ParticleType.REDSTONE;
        ParticleType particleType = args.length > 3 ? switch (args[3]) {
            case "REDSTONE" -> ParticleType.REDSTONE;
            case "SPELL_MOB" -> ParticleType.SPELL_MOB;
            case "SPELL_MOB_AMBIENT" -> ParticleType.SPELL_MOB_AMBIENT;
            case "DUST_TRANSITION" -> ParticleType.DUST_TRANSITION;
            case "multiple_dust" -> ParticleType.multiple_dust;

            default -> null;
        } : ParticleType.REDSTONE;

        if (particleType == null) {
            sender.sendMessage(ChatColor.RED + "invalid particle type: " + args[3]);
            return true;
        }


        // particle attrib: float/color/byte
        // particle:        AnimationParticle
        // frame:           AnimationParticle[]
        // animation:       Queue<AnimationParticle[]>

        BlockingQueue<AnimationParticle[]> frames = new ArrayBlockingQueue<>(1); // smaller numbers seem to have better performance
        Location loc = getLocation(sender);
        Animation animation = new Animation(frames);
        animations.add(animation);

        // particle spawner
        new BukkitRunnable() {

            @Override
            public void run() {

                sender.sendActionBar(Component.text(ChatColor.GRAY + "frames in queue: " + frames.size()));
                AnimationParticle[] frame = frames.poll();
                if (frame == null) {
                    if (animation.dontLoad) {
                        sender.sendActionBar(Component.text(ChatColor.GREEN + "end :)"));
                        animation.frames.clear();
                        animations.remove(animation);
                        this.cancel();
                        return;
                    }
                    sender.sendActionBar(Component.text(ChatColor.RED + "lagaa"));
                    return;
                }
                playFrame(frame, args, loc, particleType);
            }

        }.runTaskTimer(plugin, 0L, 1L);

        // file reader
        new BukkitRunnable() {
            @Override
            public void run() {

                File pluginFolder = plugin.getDataFolder();
                File animFile = new File(pluginFolder, fileName);

                plugin.getLogger().info("streaming: " + fileName);

                try {
                    byte[] fileBytes = Files.readAllBytes(animFile.toPath());
                    ByteBuffer bb = ByteBuffer.wrap(fileBytes);
                    bb.order(ByteOrder.LITTLE_ENDIAN);

                    while (bb.hasRemaining() && !animation.dontLoad) {
                        short length = bb.getShort();
                        AnimationParticle[] frame = new AnimationParticle[length];

                        // loop over particles and add them to frame
                        for (int i = 0; i < length; i++) {
                            frame[i] = new AnimationParticle();
                            for (int j = 0; j < 3; j++) {
                                short posComponent = bb.getShort();
                                float posComponentFloat = toFloat(posComponent);
                                frame[i].position[j] = posComponentFloat;
                            }
                            Color colorAndScale = Color.fromARGB(bb.getInt());
                            frame[i].color = colorAndScale;
                            frame[i].pscale = (byte) colorAndScale.getAlpha();
                        }
                        frames.put(frame);
                    }
                    animation.dontLoad = true;
                } catch (Exception e) {
//                    Bukkit.getLogger().info(e.toString());
                    animation.dontLoad = true;
                    sender.sendMessage(ChatColor.RED + "error streaming file: " + e);
                }
            }
        }.runTaskAsynchronously(plugin);

        return true;
    }

    static void playFrame(AnimationParticle[] frame, @NotNull String[] args, Location loc, ParticleType particleType) {
        for (AnimationParticle p : frame) {

            World world = loc.getWorld();

            Color color = p.color;
            float pscale_multiplier = 1f;
            try {
                if (args.length > 1)
                    pscale_multiplier = Float.parseFloat(args[1]);
            } catch (Exception ignored) {
            }
            float pscale = Byte.toUnsignedInt(p.pscale);
            pscale = (pscale + 1) / 64;
            pscale *= pscale_multiplier;
            Particle.DustOptions dustOptions = new Particle.DustOptions(color, pscale);

            float scale = 1f;

            try {
                if (args.length > 2)
                    scale = Float.parseFloat(args[2]);
            } catch (Exception ignored) {
            }

            Vector pointPos = new Vector(p.position[0], p.position[1], p.position[2]).multiply(scale);
            Vector pos = pointPos.add(loc.toVector());

            switch (particleType) {
                case REDSTONE:
                    world.spawnParticle(Particle.REDSTONE, pos.toLocation(world), 1, 0, 0, 0, 0, dustOptions, true);
                    break;

                case multiple_dust:
                    // spawn ~16k particles total every tick so previous frames disappear
                    int particlesPerTick = 8000;
                    try {
                        if (args.length > 4)
                            particlesPerTick = Integer.parseInt(args[4]);
                    } catch (Exception ignored) {
                    }
                    int amount = Math.floorDiv(particlesPerTick, frame.length);
                    world.spawnParticle(Particle.REDSTONE, pos.toLocation(world), amount, 0, 0, 0, 1, dustOptions, true);
                    break;

                case SPELL_MOB:
                    world.spawnParticle(Particle.SPELL_MOB, pos.toLocation(world), 0, 1 - color.getRed(), 1 - color.getGreen(), 1 - color.getBlue(), 1, null, true);
                    break;

                case SPELL_MOB_AMBIENT:
                    world.spawnParticle(Particle.SPELL_MOB_AMBIENT, pos.toLocation(world), 0, 1 - color.getRed(), 1 - color.getGreen(), 1 - color.getBlue(), 1, null, true);
                    break;

                case DUST_TRANSITION:
                    Particle.DustTransition dustTransition = new Particle.DustTransition(color, color, pscale);
                    world.spawnParticle(Particle.DUST_COLOR_TRANSITION, pos.toLocation(world), 0, dustTransition);
                    break;
            }
        }
    }

    static Location getLocation(CommandSender sender) {
        Vector offset = new Vector(0.5, 1, 0.5);
        if (sender instanceof Player player) {
            return player.getTargetBlock(null, 64).getLocation().add(offset);
        } else if (sender instanceof BlockCommandSender blockCommandSender) {
            return blockCommandSender.getBlock().getLocation().add(offset);
        }
        return null;
    }

    static void playFrame(AnimationParticle[] frame, @NotNull String[] args, Location loc) {
        playFrame(frame, args, loc, ParticleType.REDSTONE);
    }

    static void stopParticleTasks() {
        animations.forEach(animation -> {
            animation.dontLoad = true;
            animation.frames.clear();
        });
        animations.clear();
    }

    // from https://stackoverflow.com/a/6162687
    // ignores the higher 16 bits
    public static float toFloat(int hbits) {
        int mant = hbits & 0x03ff;            // 10 bits mantissa
        int exp = hbits & 0x7c00;            // 5 bits exponent
        if (exp == 0x7c00)                   // NaN/Inf
            exp = 0x3fc00;                    // -> NaN/Inf
        else if (exp != 0)                   // normalized value
        {
            exp += 0x1c000;                   // exp - 15 + 127
            if (mant == 0 && exp > 0x1c400)  // smooth transition
                return Float.intBitsToFloat((hbits & 0x8000) << 16
                        | exp << 13 | 0x3ff);
        } else if (mant != 0)                  // && exp==0 -> subnormal
        {
            exp = 0x1c400;                    // make it normal
            do {
                mant <<= 1;                   // mantissa * 2
                exp -= 0x400;                 // decrease exp by 1
            } while ((mant & 0x400) == 0); // while not normal
            mant &= 0x3ff;                    // discard subnormal bit
        }                                     // else +/-0 -> +/-0
        return Float.intBitsToFloat(          // combine all parts
                (hbits & 0x8000) << 16          // sign  << ( 31 - 15 )
                        | (exp | mant) << 13);         // value << ( 23 - 10 )
    }
}
