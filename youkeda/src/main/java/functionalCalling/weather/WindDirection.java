package functionalCalling.weather;

/**
 * 风向枚举（16 方位 + 无风/风向不定），与和风天气 wind.compass 代码对应。
 */
public enum WindDirection {
    N("北风"), NNE("北东北风"), NE("东北风"), ENE("东东北风"),
    E("东风"), ESE("东东南风"), SE("东南风"), SSE("南东南风"),
    S("南风"), SSW("南西南风"), SW("西南风"), WSW("西西南风"),
    W("西风"), WNW("西西北风"), NW("西北风"), NNW("北西北风"),
    NONE("无风"), VRB("风向不定");

    private final String zhName;

    WindDirection(String zhName) {
        this.zhName = zhName;
    }

    public String getZhName() {
        return zhName;
    }

    /** 根据和风天气 compass 代码返回中文风向名；无法识别时返回原值。 */
    public static String zhName(String compass) {
        if (compass == null || compass.isEmpty()) {
            return "";
        }
        for (WindDirection d : values()) {
            if (d.name().equalsIgnoreCase(compass)) {
                return d.zhName;
            }
        }
        return compass;
    }
}
