package dev.dsh.phimc.core;

/**
 * 判定与计分。
 *
 * <p>窗口数值全部取自 {@code prpr/src/judge.rs:20-22}，不再使用任何「手感调参」：
 * {@code LIMIT_PERFECT = 0.08}、{@code LIMIT_GOOD = 0.16}、{@code LIMIT_BAD = 0.22}（秒）。
 * 单音符权重照 {@code judge.rs:215-217} 的准确率公式：{@code (Perfect + Good*0.65) / 音符数}，
 * 即 Good = 0.65、Bad = 0、Miss = 0。
 */
public final class Judgement {

    public enum Grade { PERFECT, GOOD, BAD, MISS }

    /** 判定窗口，单位秒。默认值 = prpr judge.rs:20-22。 */
    public static final class Config {
        public double perfect = 0.08;
        public double good = 0.16;
        public double bad = 0.22;
        /** 宽判模式：所有窗口放大一倍（本实现的额外开关，源码没有；默认关闭）。 */
        public boolean wide = false;
        /**
         * 玩家速度设置，judge.rs:418 {@code let spd = res.config.speed as f64;}：
         * 判定时间差要除以它（judge.rs:576/705/733）。Phira 的滑杆范围是 0.5~2.0（game.rs:670），
         * 默认 1.0（config.rs:121）。
         */
        public double speed = 1.0;

        public Config wide(boolean w) {
            this.wide = w;
            return this;
        }

        public double perfect() { return wide ? perfect * 2 : perfect; }
        public double good() { return wide ? good * 2 : good; }
        public double bad() { return wide ? bad * 2 : bad; }
    }

    public record Result(Grade grade, double dx, double timingError) {
        public boolean counts() {
            return grade != Grade.MISS;
        }
    }

    private Judgement() {
    }

    /** 按 judge.rs:20-22 的窗口定级（不含 judge.rs:580 的 EARLY_OFFSET，那条在 {@link Engine#hit} 里）。 */
    public static Grade gradeFor(double error, Config cfg) {
        double e = Math.abs(error);
        if (e <= cfg.perfect()) return Grade.PERFECT;
        if (e <= cfg.good()) return Grade.GOOD;
        if (e <= cfg.bad()) return Grade.BAD;
        return Grade.MISS;
    }

    /**
     * 单音符得分权重。judge.rs:215-217 的准确率 = {@code (counts[0] + counts[1]*0.65) / num_of_notes}，
     * 只有 Perfect 与 Good 计入，Bad / Miss 计 0。
     */
    public static double weight(Grade g) {
        return switch (g) {
            case PERFECT -> 1.0;
            case GOOD -> 0.65;
            case BAD, MISS -> 0.0;
        };
    }

    /** Phigros 风格评级（judge.rs:1035-1046 的 icon_index 阈值同源）。 */
    public static String rank(int score) {
        if (score >= 1000000) return "PHI";
        if (score >= 960000) return "V";
        if (score >= 920000) return "S";
        if (score >= 880000) return "A";
        if (score >= 820000) return "B";
        if (score >= 700000) return "C";
        return "F";
    }
}
