package com.dawncourse.core.domain.model

/** 稳定名称用于持久化；色值仅作为生成整套主题的种子。 */
enum class ThemeColor(val argb: Long) {
    DEFAULT_GREEN(0xFF5CB67B), PINK(0xFFFF7299), RED(0xFFF44336),
    ORANGE(0xFFFF9800), AMBER(0xFFFFC107), YELLOW(0xFFFFEB3B),
    LIME(0xFFCDDC39), LIGHT_GREEN(0xFF8BC34A), GREEN(0xFF4CAF50),
    TEAL(0xFF009688), CYAN(0xFF00BCD4), LIGHT_BLUE(0xFF03A9F4),
    BLUE(0xFF2196F3), INDIGO(0xFF3F51B5), PURPLE(0xFF9C27B0),
    DEEP_PURPLE(0xFF673AB7), BLUE_GREY(0xFF607D8B), BROWN(0xFF795548), GREY(0xFF9E9E9E);

    companion object {
        fun fromStoredName(name: String?): ThemeColor = entries.firstOrNull { it.name == name } ?: DEFAULT_GREEN
    }
}
