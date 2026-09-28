package net.erutobusiness.spoilednested;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.util.LazyOptional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Origins（Apoli）の「inventory」の能力が持つ箱を拾う。例: Shulk の Hoarder（{@code origins:shulker_inventory}）。
 *
 * <p><b>なぜ要るのか</b>: この箱の中身はプレイヤーの持ち物ではなく、<b>能力の記録の中の {@code SimpleContainer}</b>
 * に入る（Apoli の {@code InventoryPower#getInventory} → {@code DataContainer.container}）。
 * Spoiled の巡回（持ち物・エンティティの入れ物・ブロックの入れ物）はどれもここを見ないので、
 * <b>この箱に入れた食べ物は腐らなかった</b>（シュルカーボックスごと入れれば箱の中身も）。
 *
 * <p>⚠ <b>Origins への依存は持たない（リフレクションで呼ぶ）</b>。Origins（Forge 版）は maven から取れず、
 * ビルドの依存に足すと CI でビルドできなくなるため。受け渡しは Minecraft と Forge の型（{@code Entity}・
 * {@code LazyOptional}・{@code Holder}・{@code Container}）だけで済む。
 * Origins が入っていなければ、この経路は何もしない（起動時に1行だけそう書く）。
 * 入っているのに呼び方が合わない（版が変わった）ときは、1行だけ警告を出して以後は止める。
 */
final class OriginsInventories {

    private static final Logger LOG = LogManager.getLogger(SpoiledNested.MOD_ID);

    /**
     * config の倍率を引くための名前。{@code containerModifier} に1行書けば速さを変えられる。
     * ⚠ <b>書いていなければ等速</b>（Spoiled 本体の規約）＝持ち物と同じ速さ。
     */
    static final ResourceLocation MODIFIER_ID = new ResourceLocation("origins", "inventory");

    private static final String API = "io.github.edwinmindcraft.apoli.api.";

    private static Method containerGet;      // IPowerContainer.get(Entity) → LazyOptional<IPowerContainer>
    private static Method containerPowers;   // IPowerContainer#getPowers() → List<Holder<ConfiguredPower<?, ?>>>
    private static Method powerFactory;      // ConfiguredPower#getFactory() → PowerFactory
    private static Class<?> inventoryPower;  // IInventoryPower
    private static Method inventoryOf;       // IInventoryPower#getInventory(ConfiguredPower, Entity) → Container
    private static boolean ready;

    static {
        try {
            // ⚠ 起動の途中（MOD を組み立てる時）に呼ばれるので、クラスを探すだけで初期化はしない（第2引数 false）。
            //    初期化は最初に呼ぶとき（プレイヤーの tick）＝全部の MOD が読み込まれたあとに起きる
            ClassLoader loader = OriginsInventories.class.getClassLoader();
            Class<?> container = Class.forName(API + "component.IPowerContainer", false, loader);
            Class<?> configured = Class.forName(API + "power.configuration.ConfiguredPower", false, loader);
            inventoryPower = Class.forName(API + "power.IInventoryPower", false, loader);
            containerGet = container.getMethod("get", Entity.class);
            containerPowers = container.getMethod("getPowers");
            powerFactory = configured.getMethod("getFactory");
            inventoryOf = inventoryPower.getMethod("getInventory", configured, Entity.class);
            ready = true;
            LOG.info("Origins（Apoli）の inventory の能力の箱も腐らせる（例: Shulk の Hoarder）");
        } catch (ClassNotFoundException e) {
            ready = false;
            LOG.info("Origins（Apoli）が無いので、能力の箱は見ない: {}", e.getMessage());
        } catch (ReflectiveOperationException | LinkageError e) {
            ready = false;
            LOG.warn("⚠ Origins（Apoli）は在るが呼び方が合わない。その箱の中は腐らないまま: {}", e.toString());
        }
    }

    private OriginsInventories() {
    }

    /** 起動時に呼び、Origins が在るかを1行ログに残す（中身は上の static の塊が持つ）。 */
    static void init() {
    }

    /** そのプレイヤーが持つ inventory の能力の箱を全部返す。無ければ空。 */
    static List<Container> of(Player player) {
        List<Container> boxes = new ArrayList<>();
        if (!ready) {
            return boxes;
        }
        try {
            Object holder = ((LazyOptional<?>) containerGet.invoke(null, player)).orElse(null);
            if (holder == null) {
                return boxes;
            }
            for (Object entry : (List<?>) containerPowers.invoke(holder)) {
                Object configured = ((Holder<?>) entry).value();
                if (configured == null) {
                    continue;
                }
                Object factory = powerFactory.invoke(configured);
                if (!inventoryPower.isInstance(factory)) {
                    continue;
                }
                Object box = inventoryOf.invoke(factory, configured, player);
                if (box instanceof Container c) {
                    boxes.add(c);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // ⚠ 版が変わって呼び方が合わなくなった。毎 tick 例外を出さないよう、1回だけ書いて止める
            ready = false;
            LOG.warn("⚠ Origins（Apoli）の能力の箱を読めなかったので、以後は見ない。その箱の中は腐らないまま: {}", e.toString());
            boxes.clear();
        }
        return boxes;
    }
}
