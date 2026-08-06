package net.erutobusiness.spoilednested;

import com.mrbysco.spoiled.config.SpoiledConfigCache;
import com.mrbysco.spoiled.handler.SpoilHandler;
import com.mrbysco.spoiled.mixin.RandomizableContainerBlockEntityAccessor;
import com.mrbysco.spoiled.recipe.SpoilRecipe;
import com.mrbysco.spoiled.util.ChunkHelper;
import com.mrbysco.spoiled.util.SpoilHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 入れ子の容器とエンダーチェストを、Spoiled 本体と同じ拍子で処理する。
 *
 * <p>⚠ <b>Spoiled 本体と同じ tick でしか動かない</b>（{@code gameTime % spoilRate == 0}）。
 * 独自の間隔で回すと、同じアイテムが1回の更新で二重に進む。
 */
public class NestedSpoilHandler {

    private static final ResourceLocation ENDER_CHEST =
        new ResourceLocation("minecraft", "ender_chest");

    /** その tick が Spoiled の更新タイミングか。 */
    private static boolean isUpdateTick(Level level) {
        long rate = SpoiledConfigCache.spoilRate;
        return rate > 0 && level.getGameTime() % rate == 0;
    }

    /**
     * config の倍率で「今回進めるか」を決める。Spoiled 本体と同じ式にそろえてある。
     * <p>{@code 0 以下 = 進めない ／ 1.0 = 常に進める ／ それ以外 = 確率で間引く}
     */
    private static boolean rolls(Level level, ResourceLocation id) {
        double rate = 1.0D;
        if (id != null && SpoiledConfigCache.containerModifier != null
            && SpoiledConfigCache.containerModifier.containsKey(id)) {
            rate = SpoiledConfigCache.containerModifier.get(id);
        }
        if (rate <= 0) {
            return false;
        }
        return rate == 1.0 || level.random.nextDouble() <= rate;
    }

    // ── 1. 入れ子の容器（チェストに入れたシュルカーボックス等）─────────────
    @SubscribeEvent
    public void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.side != LogicalSide.SERVER) {
            return;
        }
        if (!(event.level instanceof ServerLevel level)) {
            return;
        }
        // Spoiled 本体がオーバーワールド限定なので、こちらもそろえる
        // （片方だけ広げると「ネザーのチェストの中のシュルカーだけ腐る」ことになる）
        if (level.dimension() != Level.OVERWORLD || !isUpdateTick(level)) {
            return;
        }

        // ⚠ **この走査は Spoiled 本体と同じ範囲をもう1回歩く**（入れ子を見るため）。
        //    30 秒に1回とはいえ、大きな拠点ではバーストになるので所要を自分で測る。
        //    最初の数回は必ず出し、その後は重いときだけ出す（配布物に入るのでログを汚さない）。
        long t0 = System.nanoTime();
        int scanned = 0, containers = 0, nested = 0;

        for (BlockPos pos : ChunkHelper.getBlockEntityPositions(level)) {
            scanned++;
            if (!level.isAreaLoaded(pos, 1)) {
                continue;
            }
            BlockEntity be = level.getBlockEntity(pos);
            if (be == null || be.isRemoved() || !be.hasLevel()) {
                continue;
            }
            // ⚠ **未開封のルートチェストに触らない。** 中身を読むと戦利品が
            //    その場で生成されてしまう（Spoiled 本体も同じ理由で飛ばしている）
            if (be instanceof RandomizableContainerBlockEntity randomizable
                && ((RandomizableContainerBlockEntityAccessor) randomizable).getLootTable() != null) {
                continue;
            }
            IItemHandler outer = be.getCapability(ForgeCapabilities.ITEM_HANDLER).orElse(null);
            if (outer == null) {
                continue;
            }
            containers++;
            // 外側の器の倍率（冷蔵庫 0.01 など）をまず通す。内側の倍率は
            // spoilItemInHandler が itemContainerModifier で掛けるので、両方が効く
            ResourceLocation outerId = ForgeRegistries.BLOCK_ENTITY_TYPES.getKey(be.getType());
            if (!rolls(level, outerId)) {
                continue;
            }
            for (int i = 0; i < outer.getSlots(); i++) {
                ItemStack containerStack = outer.getStackInSlot(i);
                if (containerStack.isEmpty()) {
                    continue;
                }
                // ⚠ **入れ物であるスロットだけを見る。** 中身が食料そのものの場合は
                //    Spoiled 本体が既に処理しているので、ここで触ると二重に進む
                IItemHandler inner = containerStack
                    .getCapability(ForgeCapabilities.ITEM_HANDLER).orElse(null);
                if (inner == null) {
                    // ⚠ ただし**塩漬けだけは本体が完全に無視する**（saltCompat）ので、
                    //    入れ物でなくてもこちらが低い倍率で進める。二重にはならない
                    salted(level, outer, i, containerStack);
                    continue;
                }
                nested++;
                spoilInside(level, containerStack, inner);
            }
        }
        report(System.nanoTime() - t0, scanned, containers, nested);
    }

    /** 走査の所要を控える。最初の5回は必ず出し、以後は重いときだけ。 */
    private static int reports = 0;
    private static long worstMs = 0;

    private static void report(long nanos, int scanned, int containers, int nested) {
        long ms = nanos / 1_000_000L;
        worstMs = Math.max(worstMs, ms);
        boolean first = reports < 5;
        reports++;
        if (first || ms >= 50) {
            org.apache.logging.log4j.LogManager.getLogger(SpoiledNested.MOD_ID).info(
                "入れ子の走査: {}ms（ブロックエンティティ {} / 器 {} / 入れ子 {}）最悪 {}ms",
                ms, scanned, containers, nested, worstMs);
        }
    }

    private void spoilInside(ServerLevel level, ItemStack containerStack, IItemHandler nested) {
        for (int j = 0; j < nested.getSlots(); j++) {
            ItemStack stack = nested.getStackInSlot(j);
            if (stack.isEmpty()) {
                continue;
            }
            if (SaltedSpoiling.isSalted(stack)) {
                salted(level, nested, j, stack);
                continue;
            }
            SpoilRecipe recipe = SpoilHelper.getSpoilRecipe(level, stack);
            if (recipe == null) {
                continue;
            }
            // 本体の入口をそのまま使う。倍率の判定も腐敗後の置換もここが持っている
            SpoilHandler.spoilItemInHandler(containerStack, nested, j, stack, recipe,
                level.registryAccess(), level.random);
        }
    }

    /** 塩漬けの1スタックを低い倍率で進め、腐りきったら置き換える（IItemHandler 版）。 */
    private void salted(Level level, IItemHandler handler, int slot, ItemStack stack) {
        if (!SaltedSpoiling.tick(level, stack)) {
            return;
        }
        SpoilRecipe recipe = SaltedSpoiling.recipeFor(level, stack);
        if (recipe == null) {
            return;
        }
        ItemStack result = recipe.getResultItem(level.registryAccess()).copy();
        int count = stack.getCount();
        stack.setCount(0);
        if (!result.isEmpty()) {
            result.setCount(count);
            handler.insertItem(slot, result, false);
        }
    }

    /** 同上（バニラの Container 版。プレイヤーの持ち物・エンダーチェスト用）。 */
    private void salted(Level level, Container container, int slot, ItemStack stack) {
        if (!SaltedSpoiling.tick(level, stack)) {
            return;
        }
        SpoilRecipe recipe = SaltedSpoiling.recipeFor(level, stack);
        if (recipe == null) {
            return;
        }
        ItemStack result = recipe.getResultItem(level.registryAccess()).copy();
        int count = stack.getCount();
        container.setItem(slot, result.isEmpty() ? ItemStack.EMPTY
            : withCount(result, count));
    }

    private static ItemStack withCount(ItemStack stack, int count) {
        stack.setCount(count);
        return stack;
    }

    // ── 2. エンダーチェスト ────────────────────────────────────────
    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Player player = event.player;
        Level level = player.level();
        if (level.isClientSide || !isUpdateTick(level)) {
            return;
        }
        // クリエイティブは本体と同じく対象外
        if (player.getAbilities().instabuild) {
            return;
        }

        // ⚠ **持ち物の中の塩漬け**。本体は saltCompat で丸ごと無視するので、
        //    ここで低い倍率で進める（倍率のつまみは containerModifier の `spoiled:salted`）
        Container inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && SaltedSpoiling.isSalted(stack)) {
                salted(level, inv, i, stack);
            }
        }

        // ⚠ 倍率は containerModifier の `minecraft:ender_chest` を読む。
        //    **未登録なら等速**ではなく、ここでは「書いていなければ触らない」ほうが
        //    安全だが、本体の規約（未登録＝等速）に合わせる。config に1行書けば効く
        if (!rolls(level, ENDER_CHEST)) {
            return;
        }
        Container ender = player.getEnderChestInventory();
        for (int i = 0; i < ender.getContainerSize(); i++) {
            ItemStack stack = ender.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            // エンダーチェストの中の入れ物（シュルカー）も潜る
            IItemHandler nested = stack.getCapability(ForgeCapabilities.ITEM_HANDLER).orElse(null);
            if (nested != null) {
                spoilInside((ServerLevel) level, stack, nested);
                continue;
            }
            if (SaltedSpoiling.isSalted(stack)) {
                salted(level, ender, i, stack);
                continue;
            }
            SpoilRecipe recipe = SpoilHelper.getSpoilRecipe(level, stack);
            if (recipe == null) {
                continue;
            }
            SpoilHelper.updateSpoilingStack(stack, recipe);
            if (SpoilHelper.isSpoiled(stack)) {
                ItemStack spoiled = recipe.getResultItem(level.registryAccess()).copy();
                int count = stack.getCount();
                if (spoiled.isEmpty()) {
                    ender.setItem(i, ItemStack.EMPTY);
                } else {
                    spoiled.setCount(count);
                    ender.setItem(i, spoiled);
                }
            }
        }
    }
}
