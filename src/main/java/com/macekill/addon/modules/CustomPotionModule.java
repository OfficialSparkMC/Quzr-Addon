package com.macekill.addon.modules;

import com.macekill.addon.MaceKillAddon;
import com.macekill.addon.utils.CreativeGiveUtil;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;

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
    private final Minecraft mc = Minecraft.getInstance();

    public enum PotionType {
        REGULAR, SPLASH, LINGERING
    }

    public CustomPotionModule() {
        super(MaceKillAddon.CATEGORY, "PotionGiver",
            "Creative-mode custom potion spawner\n" +
            "Effect list: one per line, format effectID|level|durationSeconds\n" +
            "Example: instant_health|125|1   (Instant Health lvl 125)\n" +
            "minecraft: prefix optional, duration capped at 60 minutes");
    }

    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<PotionType> potionType = sg.add(new EnumSetting.Builder<PotionType>()
        .name("Potion Type")
        .defaultValue(PotionType.SPLASH).build()
    );

    private final Setting<Integer> count = sg.add(new IntSetting.Builder()
        .name("Count").defaultValue(1).min(1).max(64).sliderMax(64).build()
    );

    private final Setting<List<String>> potionEffects = sg.add(new StringListSetting.Builder()
        .name("Effect List")
        .description("One per line, format: effectID|level (0-255)|duration (seconds)\n" +
                     "minecraft: prefix optional\n" +
                     "Examples:\n" +
                     "  instant_health|125|1    (Instant Health lvl 125)\n" +
                     "  strength|10|300        (Strength 10 for 5 min)\n" +
                     "  regeneration|5|30      (Regeneration 5 for 30s)\n" +
                     "  speed|2|600            (Speed 2 for 10 min)\n" +
                     "Common: instant_health, instant_damage, strength, regeneration, " +
                     "speed, slowness, jump_boost, resistance, fire_resistance, " +
                     "invisibility, night_vision, water_breathing, absorption, " +
                     "health_boost, saturation, glowing, levitation, slow_falling, " +
                     "luck, unluck, bad_omen, hero_of_the_village, darkness, " +
                     "weaving, oozing, infested, wind_charged, raid_omen, trial_omen")
        .defaultValue(List.of("instant_health|125|1"))
        .build()
    );

    private final Setting<Boolean> customColor = sg.add(new BoolSetting.Builder()
        .name("Custom Color").defaultValue(false).build()
    );

    private final Setting<SettingColor> potionColor = sg.add(new ColorSetting.Builder()
        .name("Potion Color")
        .description("Click to open the HSV color picker; applied as the potion particle color")
        .defaultValue(new SettingColor(255, 0, 0)) // 默认红色
        .build()
    );

    private final Setting<Boolean> customName = sg.add(new BoolSetting.Builder()
        .name("Custom Name").defaultValue(false).build()
    );

    private final Setting<String> nameText = sg.add(new StringSetting.Builder()
        .name("Name Text")
        .description("Supports color codes: &6gold &cred &agreen &blight blue &9blue &dpink &eyellow &fwhite &0black etc.\n" +
                     "&l=bold &o=italic &n=underline &m=strikethrough &k=obfuscated")
        .defaultValue("&c&lQazr1234 &6&lKill Potion").build()
    );

    private final Setting<Boolean> continuous = sg.add(new BoolSetting.Builder()
        .name("Continuous").defaultValue(false).build()
    );

    @Override
    public void onActivate() {
        if (mc.player == null || mc.level == null) { toggle(); return; }
        if (!mc.player.getAbilities().instabuild) {
            CreativeGiveUtil.warn("Creative mode required!"); toggle(); return;
        }
        CreativeGiveUtil.resetError();
        generateAndGive();
        if (!continuous.get()) toggle();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!continuous.get() || mc.player == null) return;
        if (!mc.player.getAbilities().instabuild) { toggle(); return; }
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
        List<MobEffectInstance> effects = new ArrayList<>();
        for (String entry : potionEffects.get()) {
            if (entry == null) continue;
            String s = entry.trim();
            if (s.isEmpty()) continue;
            String[] parts = s.split("\\|");
            if (parts.length < 1) { CreativeGiveUtil.warn("Invalid effect format: " + s + " (expected id|level|seconds)"); continue; }

            String idStr = parts[0].trim();
            int amplifier = 0;
            int durationTicks = 600; // 默认30秒
            if (parts.length >= 2) {
                try { amplifier = Integer.parseInt(parts[1].trim()); }
                catch (Exception e) { CreativeGiveUtil.warn("Effect level parse error: " + parts[1]); }
            }
            if (parts.length >= 3) {
                try {
                    int seconds = Integer.parseInt(parts[2].trim());
                    durationTicks = Math.min(seconds * 20, 72000); // 最大60分钟
                } catch (Exception e) { CreativeGiveUtil.warn("Effect duration parse error: " + parts[2]); }
            }

            try {
                Identifier effId = Identifier.tryParse(idStr.contains(":") ? idStr : "minecraft:" + idStr);
                if (effId == null) { CreativeGiveUtil.warn("Unknown effect ID: " + idStr); continue; }
                Optional<Holder.Reference<MobEffect>> ref = statusEffectRegistry().get(effId);
                if (ref.isEmpty()) { CreativeGiveUtil.warn("Effect not found: " + idStr); continue; }
                effects.add(new MobEffectInstance(ref.get(), durationTicks, amplifier, false, true));
            } catch (Exception e) {
                CreativeGiveUtil.warn("Effect failed [" + idStr + "]: " + e.getMessage());
            }
        }

        if (effects.isEmpty()) {
            CreativeGiveUtil.warn("No effects added!"); return;
        }

        // 组装 PotionContents (照搬 Wurst: Optional<potion>, Optional<customColor>, List<customEffects>, Optional<customName>)
        SettingColor sc = potionColor.get();
        // 转换: SettingColor (r,g,b) -> MC ARGB int (0xAARRGGBB), PotionContents 使用纯 RGB int
        int rgb = (sc.r << 16) | (sc.g << 8) | sc.b;
        Optional<Integer> color = customColor.get() ? Optional.of(rgb) : Optional.empty();
        PotionContents contents = new PotionContents(
            Optional.empty(),         // 不使用原版药水类型
            color,                    // 自定义颜色
            effects,                  // 自定义效果
            Optional.empty()          // 不使用自定义药水名
        );
        stack.set(DataComponents.POTION_CONTENTS, contents);

        if (customName.get()) {
            stack.set(DataComponents.CUSTOM_NAME, parseName(nameText.get()));
        }

        if (CreativeGiveUtil.give(stack)) {
            CreativeGiveUtil.info("Given " + type + " potion x" + stack.getCount() + " (effects: " + effects.size() + ")");
        }
    }

    /** 解析 & 或 § 颜色代码为带样式的 Text (italic=false 确保显示) */
    private net.minecraft.network.chat.Component parseName(String raw) {
        if (raw == null || raw.isEmpty()) return net.minecraft.network.chat.Component.literal("");
        net.minecraft.network.chat.MutableComponent out = net.minecraft.network.chat.Component.empty();
        StringBuilder buf = new StringBuilder();
        net.minecraft.network.chat.Style curStyle = net.minecraft.network.chat.Style.EMPTY.withItalic(false);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if ((c == '&' || c == '§') && i + 1 < raw.length()) {
                if (buf.length() > 0) {
                    out.append(net.minecraft.network.chat.Component.literal(buf.toString()).setStyle(curStyle));
                    buf.setLength(0);
                }
                char code = Character.toLowerCase(raw.charAt(++i));
                net.minecraft.ChatFormatting f = net.minecraft.ChatFormatting.getByCode(code);
                if (f != null) {
                    if (f == net.minecraft.ChatFormatting.BOLD) curStyle = curStyle.withBold(!curStyle.isBold());
                    else if (f == net.minecraft.ChatFormatting.ITALIC) curStyle = curStyle.withItalic(!curStyle.isItalic());
                    else if (f == net.minecraft.ChatFormatting.UNDERLINE) curStyle = curStyle.withUnderlined(!curStyle.isUnderlined());
                    else if (f == net.minecraft.ChatFormatting.STRIKETHROUGH) curStyle = curStyle.withStrikethrough(!curStyle.isStrikethrough());
                    else if (f == net.minecraft.ChatFormatting.OBFUSCATED) curStyle = curStyle.withObfuscated(!curStyle.isObfuscated());
                    else if (net.minecraft.network.chat.TextColor.fromLegacyFormat(f) != null) curStyle = curStyle.withColor(net.minecraft.network.chat.TextColor.fromLegacyFormat(f));
                }
            } else {
                buf.append(c);
            }
        }
        if (buf.length() > 0) {
            out.append(net.minecraft.network.chat.Component.literal(buf.toString()).setStyle(curStyle));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Registry<MobEffect> statusEffectRegistry() {
        return mc.level.registryAccess().lookupOrThrow(Registries.MOB_EFFECT);
    }
}
