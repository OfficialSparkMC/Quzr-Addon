package com.macekill.addon.modules;

import com.macekill.addon.MaceKillAddon;
import com.macekill.addon.utils.CreativeGiveUtil;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttributeModifierSlot;
import net.minecraft.component.type.AttributeModifiersComponent;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.List;
import java.util.Optional;

/**
 * 创造模式物品生成器 (通用物品, 药水已移至 CustomPotionModule)
 * 使用 Meteor StringListSetting 管理附魔和属性
 * 附魔和属性条目支持任意多个（无硬编码上限）
 * 附魔等级范围: 0 ~ Long.MAX_VALUE (64位)
 */
public class CreativeGiveModule extends Module {
    private final MinecraftClient mc = MinecraftClient.getInstance();

    public CreativeGiveModule() {
        super(MaceKillAddon.CATEGORY, "物品生成器",
            "创造模式通用物品生成 - 类型化组件\n" +
            "附魔列表格式: 附魔ID:等级 (如 minecraft:sharpness:10)\n" +
            "属性列表格式: 属性ID|值|槽位 (如 generic.attack_damage|1000|MAINHAND)\n" +
            "槽位: MAINHAND, OFFHAND, HAND, HEAD, CHEST, LEGS, FEET, ARMOR, ANY\n" +
            "提示: 药水请使用「药水生成器」模块");
    }

    public enum Preset {
        NONE, DAMAGE_SWORD, DAMAGE_ARMOR, TOTEM
    }

    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<Preset> preset = sg.add(new EnumSetting.Builder<Preset>()
        .name("快捷预设").defaultValue(Preset.NONE).build()
    );

    private final Setting<String> itemId = sg.add(new StringSetting.Builder()
        .name("物品ID").defaultValue("minecraft:diamond_sword").build()
    );

    private final Setting<Integer> count = sg.add(new IntSetting.Builder()
        .name("数量").defaultValue(1).min(1).max(64).sliderMax(64).build()
    );

    private final Setting<Boolean> enableName = sg.add(new BoolSetting.Builder()
        .name("自定义名称").defaultValue(false).build()
    );

    private final Setting<String> customName = sg.add(new StringSetting.Builder()
        .name("名称文本")
        .description("支持颜色代码: 输入 &6金色 &c红色 &a绿色 &b浅蓝 &9蓝 &d粉 &e黄 &f白 &0黑 等\n" +
                     "& 加一个字母 = 颜色, &l=粗体 &o=斜体 &n=下划线 &m=删除线 &k=混淆\n" +
                     "示例: &6&lQazr1234 &c&l神剑")
        .defaultValue("&6&lQazr1234 &c&l测试剑").build()
    );

    private final Setting<List<String>> enchantments = sg.add(new StringListSetting.Builder()
        .name("附魔列表")
        .description("每行一个，格式: 附魔ID:等级 (附魔ID可省略 minecraft: 前缀, 等级支持任意64位整数)")
        .defaultValue(List.of("minecraft:sharpness:10"))
        .build()
    );

    private final Setting<List<String>> attributes = sg.add(new StringListSetting.Builder()
        .name("属性列表")
        .description("每行一个，格式: 属性ID|值|槽位\n" +
                     "属性ID可省略 minecraft: 前缀\n" +
                     "槽位: MAINHAND, OFFHAND, HAND, HEAD, CHEST, LEGS, FEET, ARMOR, ANY")
        .defaultValue(List.of("generic.attack_damage|1000|MAINHAND"))
        .build()
    );

    private final Setting<Boolean> continuous = sg.add(new BoolSetting.Builder()
        .name("连续生成").defaultValue(false).build()
    );

    @Override
    public void onActivate() {
        if (mc.player == null || mc.world == null) { toggle(); return; }
        if (!mc.player.getAbilities().creativeMode) {
            CreativeGiveUtil.warn("需要创造模式!"); toggle(); return;
        }
        CreativeGiveUtil.resetError();
        generateAndGive();
        if (!continuous.get()) toggle();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!continuous.get() || mc.player == null) return;
        if (!mc.player.getAbilities().creativeMode) { toggle(); return; }
        generateAndGive();
    }

    private void generateAndGive() {
        Preset p = preset.get();
        if (p != Preset.NONE) { genPreset(p); return; }

        Item item = resolveItem(itemId.get());
        if (item == Items.AIR) { CreativeGiveUtil.warn("未找到物品: " + itemId.get()); return; }

        ItemStack stack = new ItemStack(item, count.get());

        if (enableName.get()) {
            stack.set(DataComponentTypes.CUSTOM_NAME, parseName(customName.get()));
        }

        for (String entry : enchantments.get()) {
            applyEnchantFromString(stack, entry);
        }
        for (String entry : attributes.get()) {
            applyAttrFromString(stack, entry);
        }

        if (CreativeGiveUtil.give(stack)) {
            CreativeGiveUtil.info("已生成 " + stack.getCount() + "x " + itemId.get());
        }
    }

    /** 解析 & 或 § 颜色代码为带样式的 Text (italic=false 确保显示) */
    private Text parseName(String raw) {
        if (raw == null || raw.isEmpty()) return Text.literal("");
        MutableText out = Text.empty();
        StringBuilder buf = new StringBuilder();
        net.minecraft.text.Style curStyle = net.minecraft.text.Style.EMPTY.withItalic(false);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            // 同时支持 & 和 § 作为颜色前缀
            if ((c == '&' || c == '§') && i + 1 < raw.length()) {
                if (buf.length() > 0) {
                    out.append(Text.literal(buf.toString()).setStyle(curStyle));
                    buf.setLength(0);
                }
                char code = Character.toLowerCase(raw.charAt(++i));
                Formatting f = Formatting.byCode(code);
                if (f != null) {
                    if (f == Formatting.BOLD) curStyle = curStyle.withBold(!curStyle.isBold());
                    else if (f == Formatting.ITALIC) curStyle = curStyle.withItalic(!curStyle.isItalic());
                    else if (f == Formatting.UNDERLINE) curStyle = curStyle.withUnderline(!curStyle.isUnderlined());
                    else if (f == Formatting.STRIKETHROUGH) curStyle = curStyle.withStrikethrough(!curStyle.isStrikethrough());
                    else if (f == Formatting.OBFUSCATED) curStyle = curStyle.withObfuscated(!curStyle.isObfuscated());
                    else if (f.isColor()) curStyle = curStyle.withColor(TextColor.fromFormatting(f));
                }
            } else {
                buf.append(c);
            }
        }
        if (buf.length() > 0) {
            out.append(Text.literal(buf.toString()).setStyle(curStyle));
        }
        return out;
    }

    /* ==================== 附魔 (格式: id:level) ==================== */
    private void applyEnchantFromString(ItemStack stack, String entry) {
        if (entry == null) return;
        String s = entry.trim();
        if (s.isEmpty()) return;
        int idx = s.lastIndexOf(':');
        if (idx <= 0) { CreativeGiveUtil.warn("附魔格式错误: " + s + " (应为 id:level)"); return; }
        String id = s.substring(0, idx);
        String levelStr = s.substring(idx + 1);
        long level;
        try { level = Long.parseLong(levelStr.trim()); }
        catch (Exception e) { CreativeGiveUtil.warn("附魔等级解析错误: " + levelStr); return; }

        try {
            Identifier enchId = Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
            if (enchId == null) { CreativeGiveUtil.warn("附魔ID错误: " + id); return; }
            Optional<RegistryEntry.Reference<Enchantment>> ref = enchantmentRegistry().getEntry(enchId);
            if (ref.isEmpty()) { CreativeGiveUtil.warn("附魔未找到: " + id); return; }

            int safeLevel = (int) Math.min(level, Integer.MAX_VALUE);
            ItemEnchantmentsComponent current = stack.getOrDefault(
                DataComponentTypes.ENCHANTMENTS, ItemEnchantmentsComponent.DEFAULT);
            ItemEnchantmentsComponent.Builder builder = new ItemEnchantmentsComponent.Builder(current);
            builder.add(ref.get(), safeLevel);
            stack.set(DataComponentTypes.ENCHANTMENTS, builder.build());
        } catch (Exception e) {
            CreativeGiveUtil.warn("附魔失败 [" + id + "]: " + e.getMessage());
        }
    }

    /* ==================== 属性 (格式: id|amount|slot) ==================== */
    private void applyAttrFromString(ItemStack stack, String entry) {
        if (entry == null) return;
        String s = entry.trim();
        if (s.isEmpty()) return;
        String[] parts = s.split("\\|");
        if (parts.length < 2) { CreativeGiveUtil.warn("属性格式错误: " + s + " (应为 id|值|槽位)"); return; }
        String id = parts[0].trim();
        String amountStr = parts[1].trim();
        String slotStr = parts.length >= 3 ? parts[2].trim().toUpperCase() : "ANY";
        double amount;
        try { amount = Double.parseDouble(amountStr); }
        catch (Exception e) { CreativeGiveUtil.warn("属性值解析错误: " + amountStr); return; }

        try {
            Identifier attrId = Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
            if (attrId == null) { CreativeGiveUtil.warn("属性ID错误: " + id); return; }
            Optional<RegistryEntry.Reference<EntityAttribute>> ref = attributeRegistry().getEntry(attrId);
            if (ref.isEmpty()) { CreativeGiveUtil.warn("属性未找到: " + id); return; }

            EntityAttributeModifier modifier = new EntityAttributeModifier(
                Identifier.of("qazr1234", "mod_" + System.nanoTime()),
                amount,
                EntityAttributeModifier.Operation.ADD_VALUE
            );
            AttributeModifierSlot slot = parseSlot(slotStr);
            AttributeModifiersComponent current = stack.getOrDefault(
                DataComponentTypes.ATTRIBUTE_MODIFIERS, AttributeModifiersComponent.DEFAULT);
            AttributeModifiersComponent updated = current.with(ref.get(), modifier, slot);
            stack.set(DataComponentTypes.ATTRIBUTE_MODIFIERS, updated);
        } catch (Exception e) {
            CreativeGiveUtil.warn("属性失败 [" + id + "]: " + e.getMessage());
        }
    }

    private AttributeModifierSlot parseSlot(String s) {
        try {
            return AttributeModifierSlot.valueOf(s);
        } catch (Exception e) {
            CreativeGiveUtil.warn("未知槽位: " + s + " (使用ANY)"); return AttributeModifierSlot.ANY;
        }
    }

    @SuppressWarnings("unchecked")
    private Registry<Enchantment> enchantmentRegistry() {
        return (Registry<Enchantment>) (Registry<?>) mc.world.getRegistryManager()
            .getOptional(RegistryKeys.ENCHANTMENT).orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private Registry<EntityAttribute> attributeRegistry() {
        return (Registry<EntityAttribute>) (Registry<?>) mc.world.getRegistryManager()
            .getOptional(RegistryKeys.ATTRIBUTE).orElseThrow();
    }

    /* ==================== 预设 ==================== */
    private void genPreset(Preset p) {
        switch (p) {
            case DAMAGE_SWORD -> genDamageSword();
            case DAMAGE_ARMOR -> genDamageArmor();
            case TOTEM -> genTotem();
        }
    }

    private void genDamageSword() {
        ItemStack stack = new ItemStack(Items.NETHERITE_SWORD, 1);
        stack.set(DataComponentTypes.CUSTOM_NAME,
            Text.literal("Qazr1234 伤害神剑").formatted(Formatting.YELLOW, Formatting.BOLD));

        Optional<RegistryEntry.Reference<EntityAttribute>> dmgAttr =
            attributeRegistry().getEntry(Identifier.of("generic.attack_damage"));
        if (dmgAttr.isPresent()) {
            EntityAttributeModifier mod = new EntityAttributeModifier(
                Identifier.of("qazr1234", "dmg"), 137891.0,
                EntityAttributeModifier.Operation.ADD_VALUE);
            AttributeModifiersComponent current = stack.getOrDefault(
                DataComponentTypes.ATTRIBUTE_MODIFIERS, AttributeModifiersComponent.DEFAULT);
            stack.set(DataComponentTypes.ATTRIBUTE_MODIFIERS,
                current.with(dmgAttr.get(), mod, AttributeModifierSlot.MAINHAND));
        }
        if (CreativeGiveUtil.give(stack))
            CreativeGiveUtil.info("伤害神剑(137891)已生成");
    }

    private void genDamageArmor() {
        Item[] pieces = {Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE,
                         Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS};
        AttributeModifierSlot[] groups = {AttributeModifierSlot.HEAD, AttributeModifierSlot.CHEST,
                                          AttributeModifierSlot.LEGS, AttributeModifierSlot.FEET};

        Optional<RegistryEntry.Reference<EntityAttribute>> armor =
            attributeRegistry().getEntry(Identifier.of("generic.armor"));
        Optional<RegistryEntry.Reference<EntityAttribute>> toughness =
            attributeRegistry().getEntry(Identifier.of("generic.armor_toughness"));
        Optional<RegistryEntry.Reference<EntityAttribute>> kb =
            attributeRegistry().getEntry(Identifier.of("generic.knockback_resistance"));

        for (int i = 0; i < pieces.length; i++) {
            ItemStack stack = new ItemStack(pieces[i], 1);
            stack.set(DataComponentTypes.CUSTOM_NAME,
                Text.literal("Qazr1234 伤害神甲").formatted(Formatting.LIGHT_PURPLE, Formatting.BOLD));
            AttributeModifiersComponent comp = stack.getOrDefault(
                DataComponentTypes.ATTRIBUTE_MODIFIERS, AttributeModifiersComponent.DEFAULT);
            if (armor.isPresent())
                comp = comp.with(armor.get(),
                    new EntityAttributeModifier(Identifier.of("qazr1234", "armor" + i), 137891.0,
                        EntityAttributeModifier.Operation.ADD_VALUE), groups[i]);
            if (toughness.isPresent())
                comp = comp.with(toughness.get(),
                    new EntityAttributeModifier(Identifier.of("qazr1234", "tough" + i), 137891.0,
                        EntityAttributeModifier.Operation.ADD_VALUE), groups[i]);
            if (kb.isPresent())
                comp = comp.with(kb.get(),
                    new EntityAttributeModifier(Identifier.of("qazr1234", "kb" + i), 1.0,
                        EntityAttributeModifier.Operation.ADD_VALUE), groups[i]);
            stack.set(DataComponentTypes.ATTRIBUTE_MODIFIERS, comp);
            CreativeGiveUtil.give(stack);
        }
        CreativeGiveUtil.info("伤害神甲全套(137891)已生成");
    }

    private void genTotem() {
        ItemStack stack = new ItemStack(Items.TOTEM_OF_UNDYING, 1);
        stack.set(DataComponentTypes.CUSTOM_NAME,
            Text.literal("Qazr1234 不死图腾").formatted(Formatting.GOLD, Formatting.BOLD));
        if (CreativeGiveUtil.give(stack))
            CreativeGiveUtil.info("不死图腾已生成");
    }

    /* ==================== 工具 ==================== */
    private Item resolveItem(String id) {
        String fullId = id.contains(":") ? id : "minecraft:" + id;
        Identifier identifier = Identifier.tryParse(fullId);
        return identifier != null ? Registries.ITEM.get(identifier) : Items.AIR;
    }
}
