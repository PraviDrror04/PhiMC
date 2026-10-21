package dev.dsh.phimc.core;

import java.util.Locale;

/**
 * 缓动表的硬核对：把 {@code prpr/src/parse.rs:75-94}（{@code RPE_TWEEN_MAP}）和
 * {@code prpr/src/core/tween.rs:99-113}（{@code TWEEN_FUNCTIONS}）里的数字<b>从源码文本展开</b>后
 * 写死在这里，逐项断言 {@link RpeParser#tweenId} / {@link RpeParser#tween}。
 *
 * <p>为什么需要这个自测：上一版把 {@code RPE_TWEEN_MAP} 的数组整体错位了一位
 * （把 {@code easingType=2} 当成线性，实际是 SineOut），结果 3 个样本里所有 easingType ≥ 2
 * 的移动/旋转/透明度都用了「下一条曲线」，而当时的「对照 prpr 手算」探针用的是同一张错表，
 * 所以自测显示 1e-16 却仍然是错的。现在参照值直接来自源码文本（Python 展开），不再由实现反推。
 */
public final class EasingCheck {

    /**
     * {@code RPE_TWEEN_MAP}：下标 = RPE 的 {@code easingType}（0 位不用，1 位线性）。
     * 展开自 {@code parse.rs:75-94}：
     * {@code [2, 2, e(Sine,Out), e(Sine,In), e(Quad,Out), e(Quad,In), e(Sine,InOut), e(Quad,InOut),
     * e(Cubic,Out), e(Cubic,In), e(Quart,Out), e(Quart,In), e(Cubic,InOut), e(Quart,InOut),
     * e(Quint,Out), e(Quint,In), e(Expo,Out), e(Expo,In), e(Circ,Out), e(Circ,In), e(Back,Out),
     * e(Back,In), e(Circ,InOut), e(Back,InOut), e(Elastic,Out), e(Elastic,In),
     * e(Bounce,Out), e(Bounce,In), e(Bounce,InOut), e(Elastic,InOut)]}，
     * 其中 {@code e(major, minor) = major*3 + minor}（{@code tween.rs:535-537}），
     * major 依次是 Plain=0, Sine=1, Quad=2, Cubic=3, Quart=4, Quint=5, Expo=6, Circ=7, Back=8,
     * Elastic=9, Bounce=10，minor 是 In=0, Out=1, InOut=2。
     */
    private static final int[] MAP = {
            2, 2,                       // 0(不用), 1 Linear
            4, 3,                       // 2 SineOut,      3 SineIn
            7, 6,                       // 4 QuadOut,      5 QuadIn
            5, 8,                       // 6 SineInOut,    7 QuadInOut
            10, 9,                      // 8 CubicOut,     9 CubicIn
            13, 12,                     // 10 QuartOut,    11 QuartIn
            11, 14,                     // 12 CubicInOut,  13 QuartInOut
            16, 15,                     // 14 QuintOut,    15 QuintIn
            19, 18,                     // 16 ExpoOut,     17 ExpoIn
            22, 21,                     // 18 CircOut,     19 CircIn
            25, 24,                     // 20 BackOut,     21 BackIn
            23, 26,                     // 22 CircInOut,   23 BackInOut
            28, 27,                     // 24 ElasticOut,  25 ElasticIn
            31, 30,                     // 26 BounceOut,   27 BounceIn
            32, 29,                     // 28 BounceInOut, 29 ElasticInOut
    };

    private static final String[] NAME = {
            "Linear", "Linear", "SineOut", "SineIn", "QuadOut", "QuadIn", "SineInOut", "QuadInOut",
            "CubicOut", "CubicIn", "QuartOut", "QuartIn", "CubicInOut", "QuartInOut",
            "QuintOut", "QuintIn", "ExpoOut", "ExpoIn", "CircOut", "CircIn",
            "BackOut", "BackIn", "CircInOut", "BackInOut", "ElasticOut", "ElasticIn",
            "BounceOut", "BounceIn", "BounceInOut", "ElasticInOut",
    };

    /**
     * {@code TWEEN_FUNCTIONS} 的参考值：{@code {id, f(0.25), f(0.5), f(0.75)}}。
     * 由 `tween.rs:33-113` 的 10 个基函数 + `f2!`(Out = 1-f(1-x)) + `f3!`(InOut) 独立算出来。
     */
    private static final double[][] EXPECT = {
            {2, 0.250000000, 0.500000000, 0.750000000},   // linear
            {3, 0.076120467, 0.292893219, 0.617316568},   // SineIn
            {4, 0.382683432, 0.707106781, 0.923879533},   // SineOut
            {5, 0.146446609, 0.500000000, 0.853553391},   // SineInOut
            {6, 0.062500000, 0.250000000, 0.562500000},   // QuadIn
            {7, 0.437500000, 0.750000000, 0.937500000},   // QuadOut
            {8, 0.125000000, 0.500000000, 0.875000000},   // QuadInOut
            {9, 0.015625000, 0.125000000, 0.421875000},   // CubicIn
            {10, 0.578125000, 0.875000000, 0.984375000},  // CubicOut
            {11, 0.062500000, 0.500000000, 0.937500000},  // CubicInOut
            {12, 0.003906250, 0.062500000, 0.316406250},  // QuartIn
            {13, 0.683593750, 0.937500000, 0.996093750},  // QuartOut
            {14, 0.031250000, 0.500000000, 0.968750000},  // QuartInOut
            {15, 0.000976562, 0.031250000, 0.237304688},  // QuintIn
            {16, 0.762695312, 0.968750000, 0.999023438},  // QuintOut
            {17, 0.015625000, 0.500000000, 0.984375000},  // QuintInOut
            {18, 0.005524272, 0.031250000, 0.176776695},  // ExpoIn
            {19, 0.823223305, 0.968750000, 0.994475728},  // ExpoOut
            {20, 0.015625000, 0.500000000, 0.984375000},  // ExpoInOut
            {21, 0.031754163, 0.133974596, 0.338562172},  // CircIn
            {22, 0.661437828, 0.866025404, 0.968245837},  // CircOut
            {23, 0.066987298, 0.500000000, 0.933012702},  // CircInOut
            {24, -0.064136563, -0.087697500, 0.182590312},// BackIn
            {25, 0.817409688, 1.087697500, 1.064136563},  // BackOut
            {26, -0.043848750, 0.500000000, 1.043848750}, // BackInOut
            {27, -0.005524272, -0.015625000, 0.088388348},// ElasticIn
            {28, 0.911611652, 1.015625000, 1.005524272},  // ElasticOut
            {29, -0.007812500, 0.500000000, 1.007812500}, // ElasticInOut
            {30, 0.027343750, 0.234375000, 0.527343750},  // BounceIn
            {31, 0.472656250, 0.765625000, 0.972656250},  // BounceOut
            {32, 0.117187500, 0.500000000, 0.882812500},  // BounceInOut
    };

    public static void main(String[] args) {
        int fail = 0;

        // ① easingType → TweenId 一位对一位
        System.out.println("== easingType → TweenId（RPE_TWEEN_MAP）==");
        int[] got = RpeParser.easingTable();
        for (int k = 0; k < MAP.length; k++) {
            boolean ok = got[k] == MAP[k];
            if (!ok) fail++;
            System.out.printf(Locale.ROOT, "  %-2s easingType %2d → %2d  %-12s %s%n",
                    ok ? "✔" : "✗", k, got[k], NAME[k], ok ? "" : "期望 " + MAP[k]);
        }

        // ② 33 条曲线的几个采样点
        System.out.println("\n== TWEEN_FUNCTIONS 采样（f(0.25) / f(0.5) / f(0.75)）==");
        double[] xs = {0.25, 0.5, 0.75};
        for (double[] row : EXPECT) {
            int id = (int) row[0];
            StringBuilder sb = new StringBuilder();
            boolean ok = true;
            for (int i = 0; i < 3; i++) {
                double v = RpeParser.tween(id, xs[i]);
                boolean e = Math.abs(v - row[i + 1]) < 1e-8;
                if (!e) ok = false;
                sb.append(String.format(Locale.ROOT, "%10.6f", v));
            }
            if (!ok) fail++;
            System.out.printf(Locale.ROOT, "  %-2s id %2d %-12s %s%s%n",
                    ok ? "✔" : "✗", id, NAME_OF_ID(id), sb, ok ? "" : "  ← 与源码不符");
        }

        // ③ 端点：f(0)≈0、f(1)≈1。
        // 注意 expo（2^(10(x-1))）和 elastic 在端点上本来就差 2^-10≈9.8e-4，不是 bug
        // （easings.net 的 expo/elastic 定义也是这样），所以容差给 2e-3。
        System.out.println("\n== 端点检查（容差 2e-3：expo/elastic 端点本身差 2^-10）==");
        double worst = 0;
        int worstId = -1;
        for (int id = 2; id <= 32; id++) {
            double e0 = Math.abs(RpeParser.tween(id, 0.0));
            double e1 = Math.abs(RpeParser.tween(id, 1.0) - 1.0);
            double e = Math.max(e0, e1);
            if (e > worst) {
                worst = e;
                worstId = id;
            }
            if (e > 2e-3) {
                fail++;
                System.out.printf(Locale.ROOT, "  ✗ id %d f(0)=%.9f f(1)=%.9f%n",
                        id, RpeParser.tween(id, 0.0), RpeParser.tween(id, 1.0));
            }
        }
        System.out.printf(Locale.ROOT, "  ✔ 31 条曲线端点最大偏差 %.3e（id %d）%n", worst, worstId);

        System.out.println(fail == 0 ? "\n全部缓动检查通过 ✅" : "\n有 " + fail + " 项不符 ✗");
        if (fail != 0) System.exit(1);
    }

    /** TweenId → 名字（id 17/20 = QuintInOut/ExpoInOut 在 RPE 表里没用到，但曲线本身存在）。 */
    private static String NAME_OF_ID(int id) {
        if (id == 2) return "Linear";
        String[] majors = {"Sine", "Quad", "Cubic", "Quart", "Quint", "Expo", "Circ", "Back", "Elastic", "Bounce"};
        String[] minors = {"In", "Out", "InOut"};
        int major = (id - 3) / 3;
        int minor = (id - 3) % 3;
        if (major < 0 || major >= majors.length) return "?";
        return majors[major] + minors[minor];
    }
}
