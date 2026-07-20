package com.macekill.addon.modules;

import com.macekill.addon.MaceKillAddon;
import com.macekill.addon.utils.CreativeGiveUtil;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 自定义药水生成器 (照搬 Wurst PotionCmd 逻辑)
 * 独立模块, 不与其他物品生成器耦合
 *
 * 效果列表格式: 效果ID|等级(0-255)|持续秒数
 * 多个效果: 喷溅/滞留只能有1个, 普通可以有多个
 *
 * 常用效果ID:
 *   instant_health, instant_damage, strength, regeneration,
 *   speed, slowness, jump_boost, resistance, fire_resistance,
 *   invisibility, night_vision, water_breathing, absorption,
 *   health_boost, saturation, glowing, levitation, slow_falling,
 *   luck, unluck, bad_omen, hero_of_the_village, darkness
 */
public class CustomPotionModule extends Module {
    private final MinecraftClient mc = MinecraftClient.getInstance();

    public enum PotionType {
        REGULAR, SPLASH, LINGERING
    }

    public CustomPotionModule() {
        super(MaceKillAddon.CATEGORY, "药水生成器",
            "创造模式自定义药水生成\n" +
            "效果列表: 每行一个, 格式 效果ID|等级|持续秒数\n" +
            "示例: instant_health|125|1   (瞬间治疗125级)\n" +
            "可省略 minecraft: 前缀, 持续时间最大60分钟");
    }

    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<PotionType> potionType = sg.add(new EnumSetting.Builder<PotionType>()
        .name("药水类型")
        .defaultValue(PotionType.SPLASH).build()
    );

    private final Setting<Integer> count = sg.add(new IntSetting.Builder()
        .name("数量").defaultValue(1).min(1).max(64).sliderMax(64).build()
    );

    private final Setting<List<String>> potionEffects = sg.add(new StringListSetting.Builder()
        .name("效果列表")
        .description("每行一个，格式: 效果ID|等级(0-255)|持续时间(秒)\n" +
                     "可省略 minecraft: 前缀\n" +
                     "示例:\n" +
                     "  instant_health|125|1    (瞬间治疗125级)\n" +
                     "  strength|10|300        (力量10级 5分钟)\n" +
                     "  regeneration|5|30      (再生5级 30秒)\n" +
                     "  speed|2|600            (速度2级 10分钟)\n" +
                     "常用: instant_health, instant_damage, strength, regeneration, " +
                     "speed, slowness, jump_boost, resistance, fire_resistance, " +
                     "invisibility, night_vision, water_breathing, absorption, " +
                     "health_boost, saturation, glowing, levitation, slow_falling, " +
                     "luck, unluck, bad_omen, hero_of_the_village, darkness, " +
                     "weaving, oozing, infested, wind_charged, raid_omen, trial_omen")
        .defaultValue(List.of("instant_health|125|1"))
        .build()
    );

    private final Setting<Boolean> customColor = sg.add(new BoolSetting.Builder()
        .name("自定义颜色").defaultValue(false).build()
    );

    private final Setting<SettingColor> potionColor = sg.add(new ColorSetting.Builder()
        .name("药水颜色")
        .description("点击打开 HSV 颜色选择器, 选定后应用到药水的粒子颜色")
        .defaultValue(new SettingColor(255, 0, 0)) // 默认红色
        .build()
    );

    private final Setting<Boolean> customName = sg.add(new BoolSetting.Builder()
        .name("自定义名称").defaultValue(false).build()
    );

    private final Setting<String> nameText = sg.add(new StringSetting.Builder()
        .name("名称文本")
        .description("支持颜色代码: &6金色 &c红色 &a绿色 &b浅蓝 &9蓝 &d粉 &e黄 &f白 &0黑 等\n" +
                     "&l=粗体 &o=斜体 &n=下划线 &m=删除线 &k=混淆")
        .defaultValue("&c&lQazr1234 &6&l杀戮药水").build()
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
        PotionType type = potionType.get();
        Item baseItem = switch (type) {
            case REGULAR -> Items.POTION;
            case SPLASH -> Items.SPLASH_POTION;
            case LINGERING -> Items.LINGERING_POTION;
        };

        ItemStack stack = new ItemStack(baseItem, count.get());

        // 解析效果列表
        List<StatusEffectInstance> effects = new ArrayList<>();
        for (String entry : potionEffects.get()) {
            if (entry == null) continue;
            String s = entry.trim();
            if (s.isEmpty()) continue;
            String[] parts = s.split("\\|");
            if (parts.length < 1) { CreativeGiveUtil.warn("效果格式错误: " + s + " (应为 id|等级|秒)"); continue; }

            String idStr = parts[0].trim();
            int amplifier = 0;
            int durationTicks = 600; // 默认30秒
            if (parts.length >= 2) {
                try { amplifier = Integer.parseInt(parts[1].trim()); }
                catch (Exception e) { CreativeGiveUtil.warn("效果等级解析错误: " + parts[1]); }
            }
            if (parts.length >= 3) {
                try {
                    int seconds = Integer.parseInt(parts[2].trim());
                    durationTicks = Math.min(seconds * 20, 72000); // 最大60分钟
                } catch (Exception e) { CreativeGiveUtil.warn("效果持续时间解析错误: " + parts[2]); }
            }

            try {
                Identifier effId = Identifier.tryParse(idStr.contains(":") ? idStr : "minecraft:" + idStr);
                if (effId == null) { CreativeGiveUtil.warn("效果ID错误: " + idStr); continue; }
                Optional<RegistryEntry.Reference<StatusEffect>> ref = statusEffectRegistry().getEntry(effId);
                if (ref.isEmpty()) { CreativeGiveUtil.warn("效果未找到: " + idStr); continue; }
                effects.add(new StatusEffectInstance(ref.get(), durationTicks, amplifier, false, true));
            } catch (Exception e) {
                CreativeGiveUtil.warn("效果失败 [" + idStr + "]: " + e.getMessage());
            }
        }

        if (effects.isEmpty()) {
            CreativeGiveUtil.warn("未添加任何效果!"); return;
        }

        // 组装 PotionContentsComponent (照搬 Wurst: Optional<potion>, Optional<customColor>, List<customEffects>, Optional<customName>)
        SettingColor sc = potionColor.get();
        // 转换: SettingColor (r,g,b) -> MC ARGB int (0xAARRGGBB), PotionContentsComponent 使用纯 RGB int
        int rgb = (sc.r << 16) | (sc.g << 8) | sc.b;
        Optional<Integer> color = customColor.get() ? Optional.of(rgb) : Optional.empty();
        PotionContentsComponent contents = new PotionContentsComponent(
            Optional.empty(),         // 不使用原版药水类型
            color,                    // 自定义颜色
            effects,                  // 自定义效果
            Optional.empty()          // 不使用自定义药水名
        );
        stack.set(DataComponentTypes.POTION_CONTENTS, contents);

        if (customName.get()) {
            stack.set(DataComponentTypes.CUSTOM_NAME, parseName(nameText.get()));
        }

        if (CreativeGiveUtil.give(stack)) {
            CreativeGiveUtil.info("已生成 " + type + " 药水 x" + stack.getCount() + " (效果数: " + effects.size() + ")");
        }
    }

    /** 解析 & 或 § 颜色代码为带样式的 Text (italic=false 确保显示) */
    private net.minecraft.text.Text parseName(String raw) {
        if (raw == null || raw.isEmpty()) return net.minecraft.text.Text.literal("");
        net.minecraft.text.MutableText out = net.minecraft.text.Text.empty();
        StringBuilder buf = new StringBuilder();
        net.minecraft.text.Style curStyle = net.minecraft.text.Style.EMPTY.withItalic(false);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if ((c == '&' || c == '§') && i + 1 < raw.length()) {
                if (buf.length() > 0) {
                    out.append(net.minecraft.text.Text.literal(buf.toString()).setStyle(curStyle));
                    buf.setLength(0);
                }
                char code = Character.toLowerCase(raw.charAt(++i));
                net.minecraft.util.Formatting f = net.minecraft.util.Formatting.byCode(code);
                if (f != null) {
                    if (f == net.minecraft.util.Formatting.BOLD) curStyle = curStyle.withBold(!curStyle.isBold());
                    else if (f == net.minecraft.util.Formatting.ITALIC) curStyle = curStyle.withItalic(!curStyle.isItalic());
                    else if (f == net.minecraft.util.Formatting.UNDERLINE) curStyle = curStyle.withUnderline(!curStyle.isUnderlined());
                    else if (f == net.minecraft.util.Formatting.STRIKETHROUGH) curStyle = curStyle.withStrikethrough(!curStyle.isStrikethrough());
                    else if (f == net.minecraft.util.Formatting.OBFUSCATED) curStyle = curStyle.withObfuscated(!curStyle.isObfuscated());
                    else if (f.isColor()) curStyle = curStyle.withColor(net.minecraft.text.TextColor.fromFormatting(f));
                }
            } else {
                buf.append(c);
            }
        }
        if (buf.length() > 0) {
            out.append(net.minecraft.text.Text.literal(buf.toString()).setStyle(curStyle));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Registry<StatusEffect> statusEffectRegistry() {
        return (Registry<StatusEffect>) (Registry<?>) mc.world.getRegistryManager()
            .getOptional(RegistryKeys.STATUS_EFFECT).orElseThrow();
    }
}
