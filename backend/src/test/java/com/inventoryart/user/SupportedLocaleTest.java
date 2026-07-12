package com.inventoryart.user;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SupportedLocaleTest {
  @Test
  void supportsExactlyThreeLocales() {
    assertThat(SupportedLocale.fromTag("en")).isEqualTo(SupportedLocale.EN);
    assertThat(SupportedLocale.fromTag("zh-CN")).isEqualTo(SupportedLocale.ZH_CN);
    assertThat(SupportedLocale.fromTag("fr-FR")).isEqualTo(SupportedLocale.FR_FR);
    assertThatThrownBy(() -> SupportedLocale.fromTag("de"))
        .hasMessageContaining("Supported locales");
  }
}
