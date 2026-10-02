package com.csdemo.tools

/**
 * 设备伪装用的内置数据。
 *
 * 芯片与机型均为真实面向市场的型号，属性值取自各家实际 build.prop 的常见取值。
 * platform / hardware 在不同 ROM 上可能略有差异，这里取最常见的一组。
 */
object SpoofData {

    /**
     * 芯片。
     * soc 用于展示；platform 对应 ro.board.platform；hardware 对应 ro.hardware。
     */
    data class Chip(
        val name: String,
        val vendor: String,
        val platform: String,
        val hardware: String,
        val abi: String,
        /** Android 12+ 官方芯片名，对应 ro.soc.model（设置里“处理器”读这个） */
        val socModel: String,
        /** 芯片厂商，对应 ro.soc.manufacturer */
        val socManufacturer: String
    )

    /**
     * 机型。
     * model -> ro.product.model
     * brand -> ro.product.brand
     * manufacturer -> ro.product.manufacturer
     * device -> ro.product.device
     * product -> ro.product.name
     * fingerprint 前缀 -> ro.build.fingerprint
     */
    data class Phone(
        val name: String,
        val model: String,
        val brand: String,
        val manufacturer: String,
        val device: String,
        val product: String,
        val chipName: String
    )

    // ---------------- 芯片 ----------------

    val CHIPS: List<Chip> = listOf(
        // ===== 高通骁龙（新→旧）=====
        Chip("骁龙 8 Elite Gen 5", "高通", "sun", "qcom", "arm64-v8a", "SM8850", "Qualcomm"),
        Chip("骁龙 8 Elite", "高通", "sun", "qcom", "arm64-v8a", "SM8750", "Qualcomm"),
        Chip("骁龙 8s Gen 4", "高通", "volcano", "qcom", "arm64-v8a", "SM8735", "Qualcomm"),
        Chip("骁龙 8 Gen 3", "高通", "kalama", "qcom", "arm64-v8a", "SM8650", "Qualcomm"),
        Chip("骁龙 8 Gen 2", "高通", "kalama", "qcom", "arm64-v8a", "SM8550", "Qualcomm"),
        Chip("骁龙 8+ Gen 1", "高通", "cape", "qcom", "arm64-v8a", "SM8475", "Qualcomm"),
        Chip("骁龙 8 Gen 1", "高通", "waipio", "qcom", "arm64-v8a", "SM8450", "Qualcomm"),
        Chip("骁龙 7+ Gen 3", "高通", "crow", "qcom", "arm64-v8a", "SM7675", "Qualcomm"),
        Chip("骁龙 7+ Gen 2", "高通", "crow", "qcom", "arm64-v8a", "SM7475", "Qualcomm"),
        Chip("骁龙 7 Gen 3", "高通", "parrot", "qcom", "arm64-v8a", "SM7550", "Qualcomm"),
        Chip("骁龙 6 Gen 3", "高通", "parrot", "qcom", "arm64-v8a", "SM6475", "Qualcomm"),
        Chip("骁龙 888", "高通", "lahaina", "qcom", "arm64-v8a", "SM8350", "Qualcomm"),
        Chip("骁龙 870", "高通", "kona", "qcom", "arm64-v8a", "SM8250-AC", "Qualcomm"),
        Chip("骁龙 865", "高通", "kona", "qcom", "arm64-v8a", "SM8250", "Qualcomm"),

        // ===== 联发科天玑（新→旧）=====
        Chip("天玑 9500", "联发科", "mt6991", "mt6991", "arm64-v8a", "MT6991", "MediaTek"),
        Chip("天玑 9400", "联发科", "mt6991", "mt6991", "arm64-v8a", "MT6991", "MediaTek"),
        Chip("天玑 9400e", "联发科", "mt6990", "mt6990", "arm64-v8a", "MT6990", "MediaTek"),
        Chip("天玑 9300+", "联发科", "mt6989", "mt6989", "arm64-v8a", "MT6989", "MediaTek"),
        Chip("天玑 9300", "联发科", "mt6989", "mt6989", "arm64-v8a", "MT6989", "MediaTek"),
        Chip("天玑 9200", "联发科", "mt6985", "mt6985", "arm64-v8a", "MT6985", "MediaTek"),
        Chip("天玑 8400", "联发科", "mt6899", "mt6899", "arm64-v8a", "MT6899", "MediaTek"),
        Chip("天玑 8300", "联发科", "mt6897", "mt6897", "arm64-v8a", "MT6897", "MediaTek"),
        Chip("天玑 8200", "联发科", "mt6896", "mt6896", "arm64-v8a", "MT6896", "MediaTek"),
        Chip("天玑 8100", "联发科", "mt6895", "mt6895", "arm64-v8a", "MT6895", "MediaTek"),
        Chip("天玑 9000", "联发科", "mt6983", "mt6983", "arm64-v8a", "MT6983", "MediaTek"),
        Chip("天玑 7200", "联发科", "mt6886", "mt6886", "arm64-v8a", "MT6886", "MediaTek"),

        // ===== 华为麒麟（新→旧）=====
        Chip("麒麟 9030", "华为", "kirin9030", "kirin9030", "arm64-v8a", "Kirin9030", "HiSilicon"),
        Chip("麒麟 9020", "华为", "kirin9020", "kirin9020", "arm64-v8a", "Kirin9020", "HiSilicon"),
        Chip("麒麟 9010", "华为", "kirin9010", "kirin9010", "arm64-v8a", "Kirin9010", "HiSilicon"),
        Chip("麒麟 9000S", "华为", "kirin9000s", "kirin9000s", "arm64-v8a", "Kirin9000S", "HiSilicon"),
        Chip("麒麟 9000", "华为", "kirin9000", "kirin9000", "arm64-v8a", "Kirin9000", "HiSilicon"),
        Chip("麒麟 990", "华为", "kirin990", "kirin990", "arm64-v8a", "Kirin990", "HiSilicon"),
        Chip("麒麟 985", "华为", "kirin985", "kirin985", "arm64-v8a", "Kirin985", "HiSilicon"),
        Chip("麒麟 820", "华为", "kirin820", "kirin820", "arm64-v8a", "Kirin820", "HiSilicon"),

        // ===== 三星 Exynos（新→旧）=====
        Chip("Exynos 2600", "三星", "s5e9965", "exynos", "arm64-v8a", "Exynos 2600", "Samsung"),
        Chip("Exynos 2500", "三星", "s5e9955", "exynos", "arm64-v8a", "Exynos 2500", "Samsung"),
        Chip("Exynos 2400", "三星", "s5e9945", "exynos", "arm64-v8a", "Exynos 2400", "Samsung"),
        Chip("Exynos 2200", "三星", "s5e9925", "exynos", "arm64-v8a", "Exynos 2200", "Samsung"),
        Chip("Exynos 2100", "三星", "s5e9840", "exynos", "arm64-v8a", "Exynos 2100", "Samsung"),
        Chip("Exynos 1380", "三星", "s5e8835", "exynos", "arm64-v8a", "Exynos 1380", "Samsung"),

        // ===== Google Tensor（新→旧）=====
        Chip("Google Tensor G5", "Google", "laguna", "tensor", "arm64-v8a", "Tensor G5", "Google"),
        Chip("Google Tensor G4", "Google", "zumapro", "tensor", "arm64-v8a", "Tensor G4", "Google"),
        Chip("Google Tensor G3", "Google", "zuma", "tensor", "arm64-v8a", "Tensor G3", "Google"),
        Chip("Google Tensor G2", "Google", "gs201", "tensor", "arm64-v8a", "Tensor G2", "Google"),
        Chip("Google Tensor G1", "Google", "gs101", "tensor", "arm64-v8a", "Tensor G1", "Google")
    )

    // ---------------- 机型 ----------------

    val PHONES: List<Phone> = listOf(
        // ===== 小米（新→旧）=====
        Phone("小米 15 Ultra", "25010PN30C", "Xiaomi", "Xiaomi", "xuanyuan", "xuanyuan", "骁龙 8 Elite"),
        Phone("小米 15 Pro", "25010PN39C", "Xiaomi", "Xiaomi", "haotian", "haotian", "骁龙 8 Elite"),
        Phone("小米 15", "25011PN4AC", "Xiaomi", "Xiaomi", "dada", "dada", "骁龙 8 Elite"),
        Phone("小米 14 Pro", "23116PN5BC", "Xiaomi", "Xiaomi", "shennong", "shennong", "骁龙 8 Gen 3"),
        Phone("小米 14", "23127PN0CC", "Xiaomi", "Xiaomi", "houji", "houji", "骁龙 8 Gen 3"),
        Phone("小米 13", "2211133C", "Xiaomi", "Xiaomi", "fuxi", "fuxi", "骁龙 8 Gen 2"),
        Phone("小米 13 Ultra", "2304FPN6DC", "Xiaomi", "Xiaomi", "ishtar", "ishtar", "骁龙 8 Gen 2"),
        Phone("小米 12S Ultra", "2203121C", "Xiaomi", "Xiaomi", "thor", "thor", "骁龙 8+ Gen 1"),

        // ===== Redmi（新→旧）=====
        Phone("Redmi K90 Pro", "25012RK54C", "Redmi", "Xiaomi", "haotian", "haotian", "骁龙 8 Elite Gen 5"),
        Phone("Redmi K90", "25011RK50C", "Redmi", "Xiaomi", "dada", "dada", "骁龙 8 Elite"),
        Phone("Redmi K80 Pro", "24117RK26C", "Redmi", "Xiaomi", "zorn", "zorn", "骁龙 8 Elite"),
        Phone("Redmi K80", "24117RK7BC", "Redmi", "Xiaomi", "fuxi", "fuxi", "骁龙 8 Gen 3"),
        Phone("Redmi K70 Pro", "23117RK66C", "Redmi", "Xiaomi", "manet", "manet", "骁龙 8 Gen 3"),
        Phone("Redmi K70 Ultra", "24013RK15C", "Redmi", "Xiaomi", "rothko", "rothko", "天玑 9300+"),
        Phone("Redmi K60", "23013RK75C", "Redmi", "Xiaomi", "mondrian", "mondrian", "骁龙 8+ Gen 1"),
        Phone("Redmi Note 14 Pro", "24096RADC", "Redmi", "Xiaomi", "zircon", "zircon", "骁龙 7s Gen 2"),
        Phone("Redmi Note 13 Pro", "2312DRA50C", "Redmi", "Xiaomi", "zircon", "zircon", "骁龙 7s Gen 2"),
        Phone("Redmi Note 12 Turbo", "23049RAD8C", "Redmi", "Xiaomi", "marble", "marble", "骁龙 7+ Gen 2"),

        // ===== 华为（新→旧）=====
        Phone("华为 Mate 80 Pro", "NLA-AL00", "HUAWEI", "HUAWEI", "NLA", "NLA-AL00", "麒麟 9030"),
        Phone("华为 Mate 80", "NLA-AL00", "HUAWEI", "HUAWEI", "NLA", "NLA-AL00", "麒麟 9030"),
        Phone("华为 Mate 70 Pro", "TNL-AL00", "HUAWEI", "HUAWEI", "TNL", "TNL-AL00", "麒麟 9020"),
        Phone("华为 Mate 60 Pro", "ALN-AL00", "HUAWEI", "HUAWEI", "ALN", "ALN-AL00", "麒麟 9000S"),
        Phone("华为 Mate 60", "BRA-AL00", "HUAWEI", "HUAWEI", "BRA", "BRA-AL00", "麒麟 9000S"),
        Phone("华为 Pura 80 Pro", "HBP-AL00", "HUAWEI", "HUAWEI", "HBP", "HBP-AL00", "麒麟 9020"),
        Phone("华为 Pura 70 Pro", "HBN-AL00", "HUAWEI", "HUAWEI", "HBN", "HBN-AL00", "麒麟 9010"),
        Phone("华为 P60 Pro", "MNA-AL00", "HUAWEI", "HUAWEI", "MNA", "MNA-AL00", "骁龙 8+ Gen 1"),

        // ===== 荣耀（新→旧）=====
        Phone("荣耀 Magic7 Pro", "BVL-N49", "HONOR", "HONOR", "BVL", "BVL-N49", "骁龙 8 Elite"),
        Phone("荣耀 Magic6 Pro", "BVL-AN00", "HONOR", "HONOR", "BVL", "BVL-AN00", "骁龙 8 Gen 3"),
        Phone("荣耀 Magic5 Pro", "PGT-AN00", "HONOR", "HONOR", "PGT", "PGT-AN00", "骁龙 8 Gen 2"),

        // ===== OPPO（新→旧）=====
        Phone("OPPO Find X8 Ultra", "PHY120", "OPPO", "OPPO", "PHY120", "PHY120", "骁龙 8 Elite"),
        Phone("OPPO Find X8 Pro", "PHY110", "OPPO", "OPPO", "PHY110", "PHY110", "天玑 9400"),
        Phone("OPPO Find X7 Ultra", "PHY110", "OPPO", "OPPO", "PHY110", "PHY110", "骁龙 8 Gen 3"),
        Phone("OPPO Find X6 Pro", "PGEM10", "OPPO", "OPPO", "PGEM10", "PGEM10", "骁龙 8 Gen 2"),

        // ===== vivo（新→旧）=====
        Phone("vivo X200 Pro", "V2413A", "vivo", "vivo", "PD2413", "PD2413", "天玑 9400"),
        Phone("vivo X100 Pro", "V2324A", "vivo", "vivo", "PD2324", "PD2324", "天玑 9300"),
        Phone("vivo X90 Pro+", "V2227A", "vivo", "vivo", "PD2227", "PD2227", "骁龙 8 Gen 2"),

        // ===== 一加（新→旧）=====
        Phone("一加 13", "PJZ110", "OnePlus", "OnePlus", "PJZ110", "PJZ110", "骁龙 8 Elite"),
        Phone("一加 12", "PJD110", "OnePlus", "OnePlus", "PJD110", "PJD110", "骁龙 8 Gen 3"),
        Phone("一加 11", "PHB110", "OnePlus", "OnePlus", "PHB110", "PHB110", "骁龙 8 Gen 2"),

        // ===== realme =====
        Phone("realme GT7 Pro", "RMX5010", "realme", "realme", "RMX5010", "RMX5010", "骁龙 8 Elite"),
        Phone("realme GT5 Pro", "RMX3888", "realme", "realme", "RMX3888", "RMX3888", "骁龙 8 Gen 3"),

        // ===== 三星（新→旧）=====
        Phone("三星 Galaxy S25 Ultra", "SM-S938B", "samsung", "samsung", "e3q", "e3q", "骁龙 8 Elite"),
        Phone("三星 Galaxy S24 Ultra", "SM-S928B", "samsung", "samsung", "e3q", "e3q", "骁龙 8 Gen 3"),
        Phone("三星 Galaxy S23 Ultra", "SM-S918B", "samsung", "samsung", "dm3q", "dm3q", "骁龙 8 Gen 2"),
        Phone("三星 Galaxy S22", "SM-S901B", "samsung", "samsung", "r0s", "r0s", "骁龙 8 Gen 1"),

        // ===== Google（新→旧）=====
        Phone("Pixel 10 Pro", "GLBW0", "google", "Google", "frankel", "frankel", "Google Tensor G5"),
        Phone("Pixel 9 Pro", "GR83Y", "google", "Google", "caiman", "caiman", "Google Tensor G4"),
        Phone("Pixel 8 Pro", "GC3VE", "google", "Google", "husky", "husky", "Google Tensor G3"),
        Phone("Pixel 8", "GKWS6", "google", "Google", "shiba", "shiba", "Google Tensor G3"),
        Phone("Pixel 7 Pro", "GP4BC", "google", "Google", "cheetah", "cheetah", "Google Tensor G2"),
        Phone("Pixel 6", "GB7N6", "google", "Google", "oriole", "oriole", "Google Tensor G1")
    )

    fun chipsByVendor(): Map<String, List<Chip>> = CHIPS.groupBy { it.vendor }

    fun chipByName(name: String): Chip? = CHIPS.firstOrNull { it.name == name }

    /** 需要写入的属性名列表（供 UI 展示） */
    val PROP_KEYS: List<String> = listOf(
        "ro.product.model",
        "ro.product.brand",
        "ro.product.manufacturer",
        "ro.product.device",
        "ro.product.name",
        "ro.product.board",
        "ro.board.platform",
        "ro.hardware",
        "ro.soc.model",
        "ro.soc.manufacturer"
    )

    /**
     * 每个属性在“设置-关于手机”里是否可见。
     * 用于给用户说实话，不夸大效果。
     */
    val PROP_VISIBILITY: Map<String, String> = mapOf(
        "ro.product.model" to "设置-关于手机-型号：通常可见",
        "ro.product.brand" to "设置-关于手机-品牌：部分 ROM 可见",
        "ro.product.manufacturer" to "制造商字段：部分 ROM 可见",
        "ro.product.device" to "设备代号：一般不在设置里显示",
        "ro.product.name" to "产品名：一般不在设置里显示",
        "ro.product.board" to "主板：只有部分 ROM 显示",
        "ro.board.platform" to "平台：通常不在设置里显示",
        "ro.hardware" to "硬件名：不在设置里显示，且极难修改",
        "ro.soc.model" to "设置-关于手机-处理器：Android 12+ 可见",
        "ro.soc.manufacturer" to "处理器厂商：Android 12+ 部分可见"
    )
}
