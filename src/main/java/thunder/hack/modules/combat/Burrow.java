package thunder.hack.modules.combat;

import com.google.common.eventbus.Subscribe;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import thunder.hack.cmd.Command;
import thunder.hack.events.impl.PacketEvent;
import thunder.hack.events.impl.PlayerUpdateEvent;
import thunder.hack.modules.Module;
import thunder.hack.setting.Setting;
import thunder.hack.utility.player.InventoryUtility;
import thunder.hack.utility.player.PlaceUtility;
import thunder.hack.utility.Timer;
import thunder.hack.utility.math.MathUtility;


public class Burrow extends Module {

    public Burrow() {
        super("Burrow", "Ставит в тебя блок", Category.COMBAT);
    }

    private final Setting<OffsetMode> offsetMode = new Setting<>("Mode", OffsetMode.Smart);
    public Setting<Float> vClip = new Setting("V-Clip", -9.0F, -256.0F, 256.0F,v -> offsetMode.getValue() == OffsetMode.Constant);
    public Setting<Integer> delay = new Setting<>("Delay", 100, 0, 1000);
    public Setting<Boolean> scaleDown = new Setting<>("Scale-Down", false);
    public Setting<Boolean> scaleVelocity = new Setting<>("Scale-Velocity", false);
    public Setting<Boolean> scaleExplosion = new Setting<>("Scale-Explosion", false);
    public Setting<Float> scaleFactor = new Setting("Scale-Factor", 1.0F, 0.1F, 10.0F);
    public Setting<Integer> scaleDelay = new Setting<>("Scale-Delay", 250, 0, 1000);
    public Setting<Boolean> attack = new Setting<>("Attack", true);
    public Setting<Boolean> placeDisable = new Setting<>("PlaceDisable", false);
    public Setting<Boolean> wait = new Setting<>("Wait", true);
    public Setting<Boolean> evade = new Setting<>("Evade", false,v -> offsetMode.getValue() == OffsetMode.Constant);
    public Setting<Boolean> noVoid = new Setting<>("NoVoid", false,v -> offsetMode.getValue() == OffsetMode.Smart);
    public Setting<Boolean> onGround = new Setting<>("OnGround", true);
    public Setting<Boolean> allowUp = new Setting<>("IgnoreHeadBlock", false);
    public Setting<Boolean> rotate = new Setting<>("Rotate", true);
    public Setting<Boolean> discrete = new Setting<>("Discrete", true,v -> offsetMode.getValue() == OffsetMode.Smart);
    public Setting<Boolean> air = new Setting<>("Air", false,v -> offsetMode.getValue() == OffsetMode.Smart);
    public Setting<Boolean> fallback = new Setting<>("Fallback", true,v -> offsetMode.getValue() == OffsetMode.Smart);
    public Setting<Boolean> skipZero = new Setting<>("SkipZero", true,v -> offsetMode.getValue() == OffsetMode.Smart);

    private double motionY;
    private BlockPos startPos;
    private volatile double last_x;
    private volatile double last_y;
    private volatile double last_z;
    private final Timer scaleTimer = new Timer();
    private final Timer timer = new Timer();

    @Override
    public void onEnable() {
        timer.reset();
        startPos = getPlayerPos();
    }

    @EventHandler
    public void onPacketReceive(PacketEvent.Receive event) {
        if (event.getPacket() instanceof ExplosionS2CPacket) {
            if (scaleExplosion.getValue()) {
                motionY = ((ExplosionS2CPacket) event.getPacket()).getPlayerVelocityY();
                scaleTimer.reset();
            }
            if (scaleVelocity.getValue()) {
                return;
            }
            if (mc.player != null) {
                motionY = ((ExplosionS2CPacket) event.getPacket()).getPlayerVelocityY() / 8000.0;
                scaleTimer.reset();
            }
        }

        if (event.getPacket() instanceof PlayerPositionLookS2CPacket) {
            PlayerPositionLookS2CPacket packet = event.getPacket();
            double x = packet.getX();
            double y = packet.getY();
            double z = packet.getZ();

            if (packet.getFlags().contains(PositionFlag.X)) {
                x += mc.player.getX();
            }
            if (packet.getFlags().contains(PositionFlag.Y)) {
                y += mc.player.getY();
            }

            if (packet.getFlags().contains(PositionFlag.Z)) {
                z += mc.player.getZ();
            }

            last_x = MathUtility.clamp(x, -3.0E7, 3.0E7);
            last_y = y;
            last_z = MathUtility.clamp(z, -3.0E7, 3.0E7);
        }
    }

    @EventHandler
    public void onEntityUpdate(PlayerUpdateEvent e) {
        if (wait.getValue()) {
            BlockPos currentPos = getPlayerPos();
            if (!currentPos.equals(startPos)) {
                disable();
                return;
            }
        }

        if ((mc.world.getBlockState(BlockPos.ofFloored(mc.player.getPos().offset(Direction.UP,0.2))).blocksMovement() || !mc.player.verticalCollision)) {
            return;
        }

        PlayerEntity rEntity = mc.player;

        BlockPos pos = getPosition(rEntity);
        if (!mc.world.getBlockState(pos).isReplaceable()) {
            if (!wait.getValue())
                disable();
            return;
        }

        BlockPos posHead = getPosition(rEntity).up().up();
        if (!mc.world.getBlockState(posHead).isReplaceable() && wait.getValue()) {
            return;
        }

        for (Entity entity : mc.world.getNonSpectatingEntities(Entity.class, new Box(pos))) {
            if (entity != null && !mc.player.equals(entity) && entity.isAlive()) {
                if (entity instanceof EndCrystalEntity && attack.getValue()) {
                    PlayerInteractEntityC2SPacket attackPacket = PlayerInteractEntityC2SPacket.attack(mc.player, ((mc.player)).isSneaking());
                    AutoCrystal.changeId(attackPacket,entity.getId());
                    mc.player.networkHandler.sendPacket(attackPacket);
                    mc.player.networkHandler.sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));
                    entity.kill();
                    entity.setRemoved(Entity.RemovalReason.KILLED);
                    entity.onRemoved();
                    continue;
                }
                if (!wait.getValue()) disable();
                return;
            }
        }

        if (!allowUp.getValue()) {
            BlockPos upUp = pos.up(2);
            BlockState upState = mc.world.getBlockState(upUp);
            if (upState.blocksMovement()) {
                if (!wait.getValue())
                    disable();
                return;
            }
        }

        int slot = (InventoryUtility.findHotbarBlock(Blocks.OBSIDIAN) == -1 || mc.world.getBlockState(pos.down()).getBlock() == Blocks.ENDER_CHEST ? InventoryUtility.findHotbarBlock(Blocks.ENDER_CHEST) : InventoryUtility.findHotbarBlock(Blocks.OBSIDIAN));
        int prevSlot = mc.player.getInventory().selectedSlot;
        if (slot == -1) {
            Command.sendMessage("No Block found!");
            disable();
            return;
        }

        double y = applyScale(getY(rEntity, offsetMode.getValue()));
        if (Double.isNaN(y)) {
            return;
        }

        float[] r = PlaceUtility.getRotationForPlace(pos,true);

        PlayerEntity finalREntity = rEntity;

        if (mc.isInSingleplayer()) {
            disable();
            return;
        }

        if (rotate.getValue()) {
            if(r != null) {
                if (finalREntity.getPos().equals(new Vec3d(last_x, last_y, last_z))) {
                    mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(r[0], r[1], onGround.getValue()));
                } else {
                    mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.Full(finalREntity.getX(), finalREntity.getY(), finalREntity.getZ(), r[0], r[1], onGround.getValue()));
                }
            }
        }

        mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(finalREntity.getX(),  finalREntity.getY() + 0.42, finalREntity.getZ(), onGround.getValue()));
        mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(finalREntity.getX(),  finalREntity.getY() + 0.75, finalREntity.getZ(), onGround.getValue()));
        mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(finalREntity.getX(),  finalREntity.getY() + 1.01, finalREntity.getZ(), onGround.getValue()));
        mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(finalREntity.getX(),  finalREntity.getY() + 1.16, finalREntity.getZ(), onGround.getValue()));

        int prev_slot = mc.player.getInventory().selectedSlot;
        if(mc.player.getInventory().selectedSlot != slot){
            mc.player.getInventory().selectedSlot = slot;
            mc.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(slot));
        }
        PlaceUtility.placeBlock(pos, true);
        mc.player.getInventory().selectedSlot = prev_slot;
        mc.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(prev_slot));

        mc.player.networkHandler.sendPacket(new HandSwingC2SPacket(slot == -2 ? Hand.OFF_HAND : Hand.MAIN_HAND));

        mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(rEntity.getX(),  y, rEntity.getZ(), false));
        timer.reset();

        mc.player.getInventory().selectedSlot = prevSlot;
        mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(prevSlot));
        if (!wait.getValue() || placeDisable.getValue()) disable();
    }

    public double getY(Entity entity, OffsetMode mode) {
        if (mode == OffsetMode.Constant) {
            double y = entity.getY() + vClip.getValue();
            if (evade.getValue() && Math.abs(y) < 1) {
                y = -1;
            }

            return y;
        }

        double d = getY(entity, 3, 10, true);
        if (Double.isNaN(d)) {
            d = getY(entity, -3, -10, false);
            if (Double.isNaN(d)) {
                if (fallback.getValue()) {
                    return getY(entity, OffsetMode.Constant);
                }
            }
        }

        return d;
    }

    public static BlockPos getPosition(Entity entity) {
        double y = entity.getY();
        if (entity.getY() - Math.floor(entity.getY()) > 0.5) {
            y = Math.ceil(entity.getY());
        }

        return BlockPos.ofFloored(entity.getX(), y, entity.getZ());
    }

    public double getY(Entity entity, double min, double max, boolean add) {
        if (min > max && add || max > min && !add) {
            return Double.NaN;
        }

        double x = entity.getX();
        double y = entity.getY();
        double z = entity.getZ();

        boolean air = false;
        double lastOff = 0.0;
        BlockPos last = null;
        for (double off = min; add ? off < max : off > max; off = (add ? ++off : --off)) {
            BlockPos pos =  BlockPos.ofFloored(x, y - off, z);
            if (noVoid.getValue() && pos.getY() < 0) {
                continue;
            }

            if (skipZero.getValue() && Math.abs(y) < 1) {
                air = false;
                last = pos;
                lastOff = y - off;
                continue;
            }

            BlockState state = mc.world.getBlockState(pos);
            if (!this.air.getValue() && !state.blocksMovement() || state.getBlock() == Blocks.AIR) {
                if (air) {
                    if (add) {
                        return discrete.getValue() ? pos.getY() : y - off;
                    } else {
                        return discrete.getValue() ? last.getY() : lastOff;
                    }
                }
                air = true;
            } else {
                air = false;
            }
            last = pos;
            lastOff = y - off;
        }

        return Double.NaN;
    }

    protected double applyScale(double value) {
        if (value < mc.player.getY() && !scaleDown.getValue()
                || !scaleExplosion.getValue() && !scaleVelocity.getValue()
                || scaleTimer.passedMs(scaleDelay.getValue())
                || motionY == 0.0) {
            return value;
        }

        if (value < mc.player.getY()) {
            value -= (motionY * scaleFactor.getValue());
        } else {
            value += (motionY * scaleFactor.getValue());
        }


        return discrete.getValue() ? Math.floor(value) : value;
    }

    public static BlockPos getPlayerPos() {
        return Math.abs(mc.player.getVelocity().y) > 0.1 ? BlockPos.ofFloored(mc.player.getPos()) : getPosition(mc.player);
    }

    public enum OffsetMode {
        Constant,
        Smart
    }
}
