package com.inventoryart.user;

import com.inventoryart.exception.BusinessException;
import java.util.Arrays;

public enum SupportedLocale {
    EN("en"), ZH_CN("zh-CN"), FR_FR("fr-FR");
    private final String tag;
    SupportedLocale(String tag) { this.tag = tag; }
    public String tag() { return tag; }
    public static SupportedLocale fromTag(String tag) {
        return Arrays.stream(values()).filter(v -> v.tag.equals(tag)).findFirst()
            .orElseThrow(() -> new BusinessException("UNSUPPORTED_LOCALE", "Supported locales are en, zh-CN and fr-FR"));
    }
}

