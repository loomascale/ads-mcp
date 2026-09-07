package com.loomascale.googleads.client;

import java.math.BigDecimal;
import java.math.RoundingMode;

// Money crosses the MCP boundary as a major-unit decimal plus a currency code,
// never as a bare minor-unit integer: ChatGPT once read a "dailyBudgetCents"
// of 80000 on a UAH account and told the user "80 000 UAH/день" for a budget of
// 800 UAH. Inside the backend money stays in minor units (Google micros are
// normalized to cents in GoogleAdsApiClient, Meta returns cents already);
// this class is the only place that converts for display.
public final class Money {

  private static final BigDecimal MINOR_UNITS_PER_MAJOR = BigDecimal.valueOf(100);

  private Money() {}

  // Minor units to a scale-2 decimal, so Jackson writes 800.00 rather than 800.0.
  public static BigDecimal majorUnits(long minorUnits) {
    return BigDecimal.valueOf(minorUnits, 2);
  }

  // Fractional minor units (Google reports average CPC with sub-cent precision).
  public static BigDecimal majorUnits(double minorUnits) {
    return BigDecimal.valueOf(minorUnits).divide(MINOR_UNITS_PER_MAJOR, 2, RoundingMode.HALF_UP);
  }

  // Major units as typed by the user or the model, back to minor units for the API.
  public static long minorUnits(double majorUnits) {
    return Math.round(majorUnits * 100.0);
  }

  // Human-readable amount for tool text and error messages. The currency code is
  // omitted rather than guessed when the account currency could not be resolved.
  public static String display(long minorUnits, String currency) {
    String amount = String.format("%.2f", minorUnits / 100.0);
    return currency == null || currency.isBlank() ? amount : amount + " " + currency;
  }

  public static String display(double minorUnits, String currency) {
    String amount = String.format("%.2f", minorUnits / 100.0);
    return currency == null || currency.isBlank() ? amount : amount + " " + currency;
  }
}
