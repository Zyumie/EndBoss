package fr.zyumie.EndBoss.IA;

/**
 * Capacités que le cerveau du boss peut choisir. Le cooldown est exprimé en ticks
 * et multiplié par le coefficient de la phase en cours.
 */
public enum Ability {
    LEAP(80),
    DASH_STRIKE(45),
    SWORD_THROW(90),
    SHIELD_UP(160),
    BLINK(150),
    MACE_SMASH(180),
    SHOCKWAVE(240),
    SUMMON(500),
    METEORS(320),
    BLOCK_BARRAGE(220),
    SKY_SLAM(240),
    ITEM_THEFT(1800),
    SKY_RAPTURE(2400),
    INVENTORY_SCRAMBLE(1200),
    WHIRLPOOL(260);

    public final int cooldown;

    Ability(int cooldown) {
        this.cooldown = cooldown;
    }

    public static Ability parse(String name) {
        if (name == null) return null;
        try {
            return valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
