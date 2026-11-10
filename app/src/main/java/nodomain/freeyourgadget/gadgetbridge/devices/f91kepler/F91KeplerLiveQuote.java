/*  Copyright (C) 2026 Zebsi235

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.devices.f91kepler;

import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The price cards of the live picture: a crypto price from CoinGecko (no key)
 * or a stock price from Finnhub or Alpha Vantage (the user's own key). Same
 * services, parsing and layout as the iPhone app; plain Java, no network here.
 */
public final class F91KeplerLiveQuote {
    private F91KeplerLiveQuote() {
    }

    /** A price to draw. */
    public static final class Quote {
        public final String symbol;
        public final double price;
        /** Change against the previous close (stocks) or 24 h ago (crypto), in %. */
        @Nullable
        public final Double changePercent;
        /** Recent prices, oldest first, for the line. */
        public final double[] points;

        public Quote(final String symbol, final double price, @Nullable final Double changePercent,
                     final double[] points) {
            this.symbol = symbol;
            this.price = price;
            this.changePercent = changePercent;
            this.points = points;
        }
    }

    /** Why there is no price. */
    public enum Problem { UNKNOWN_SYMBOL, NO_PRICE, BAD_KEY, LIMITED }

    public static final class QuoteException extends Exception {
        public final Problem problem;

        public QuoteException(final Problem problem) {
            super(problem.name());
            this.problem = problem;
        }
    }

    // --- Card ---------------------------------------------------------------------

    /** Symbol and change on top, the price large, the day's line below. */
    public static F91KeplerLiveCanvas render(final Quote q) {
        final F91KeplerLiveCanvas c = new F91KeplerLiveCanvas();
        c.text(F91KeplerLiveCanvas.fit(q.symbol.toUpperCase(Locale.ROOT), 8), 0, 0);
        if (q.changePercent != null) {
            final String s = changeText(q.changePercent);
            c.text(s, F91KeplerLiveCanvas.WIDTH - F91KeplerLiveCanvas.textWidth(s) + 1, 0);
        }
        final String price = priceText(q.price);
        if (F91KeplerLiveCanvas.textWidth(price, 2) <= F91KeplerLiveCanvas.WIDTH + 2) {
            c.text(price, 0, 9, 2);
        } else {
            c.text(F91KeplerLiveCanvas.fit(price, 16), 0, 12);
        }
        c.sparkline(q.points, 0, 25, F91KeplerLiveCanvas.WIDTH, 14);
        return c;
    }

    /** "+1.2%", "-0.4%", "+12%". */
    public static String changeText(final double percent) {
        final String sign = percent >= 0 ? "+" : "-";
        final double a = Math.abs(percent);
        return sign + String.format(Locale.ROOT, a >= 10 ? "%.0f" : "%.1f", a) + "%";
    }

    /** Fewer decimals the larger the price, so it fits 7-8 large digits. */
    public static String priceText(final double p) {
        final double a = Math.abs(p);
        final String f;
        if (a >= 10_000) {
            f = "%.0f";
        } else if (a >= 100) {
            f = "%.1f";
        } else if (a >= 1) {
            f = "%.2f";
        } else if (a >= 0.01) {
            f = "%.4f";
        } else {
            f = "%.6f";
        }
        return String.format(Locale.ROOT, f, p);
    }

    // --- CoinGecko ----------------------------------------------------------------

    /** CoinGecko (crypto, no key): "BTC", "ETH-EUR" or a CoinGecko id ("bitcoin"). */
    public static final class Coin {
        public final String id;
        public final String label;
        public final String currency;

        Coin(final String id, final String label, final String currency) {
            this.id = id;
            this.label = label;
            this.currency = currency;
        }

        /** One call gives the last 24 h, so the change and the line are both 24 h. */
        public String url() {
            return "https://api.coingecko.com/api/v3/coins/" + id + "/market_chart?vs_currency="
                    + currency.toLowerCase(Locale.ROOT) + "&days=1";
        }
    }

    /** The common tickers; anything else is taken as a CoinGecko id. */
    private static final Map<String, String> COIN_IDS = new HashMap<>();
    static {
        final String[] pairs = {
                "BTC", "bitcoin", "ETH", "ethereum", "SOL", "solana", "XRP", "ripple",
                "ADA", "cardano", "DOGE", "dogecoin", "LTC", "litecoin", "DOT", "polkadot",
                "BNB", "binancecoin", "TRX", "tron", "AVAX", "avalanche-2", "LINK", "chainlink",
                "XMR", "monero", "USDT", "tether", "USDC", "usd-coin",
        };
        for (int i = 0; i < pairs.length; i += 2) {
            COIN_IDS.put(pairs[i], pairs[i + 1]);
        }
    }
    private static final List<String> CURRENCIES = Arrays.asList("USD", "EUR", "CHF", "GBP");

    /** The coin the user typed, or null when it cannot be one. */
    @Nullable
    public static Coin coin(@Nullable final String input) {
        if (input == null) {
            return null;
        }
        final List<String> parts = new ArrayList<>(Arrays.asList(input.trim().split("-", -1)));
        String currency = "USD";
        if (parts.size() > 1 && CURRENCIES.contains(parts.get(parts.size() - 1).toUpperCase(Locale.ROOT))) {
            currency = parts.remove(parts.size() - 1).toUpperCase(Locale.ROOT);
        }
        final String name = String.join("-", parts);
        if (name.isEmpty() || name.length() > 40 || !name.matches("[A-Za-z0-9-]+")) {
            return null;
        }
        final String upper = name.toUpperCase(Locale.ROOT);
        final String id = COIN_IDS.get(upper);
        if (id != null) {
            return new Coin(id, upper, currency);
        }
        return new Coin(name.toLowerCase(Locale.ROOT), upper.substring(0, Math.min(6, upper.length())), currency);
    }

    public static Quote decodeCoin(final String body, final Coin coin) throws QuoteException {
        final JsonObject root = object(body);
        if (root.has("error")) {
            throw new QuoteException(Problem.UNKNOWN_SYMBOL);
        }
        final List<Double> points = new ArrayList<>();
        final JsonElement prices = root.get("prices");
        if (prices != null && prices.isJsonArray()) {
            for (final JsonElement e : prices.getAsJsonArray()) {
                if (e.isJsonArray()) {
                    final JsonArray pair = e.getAsJsonArray();
                    if (pair.size() >= 2 && isNumber(pair.get(1))) {
                        points.add(pair.get(1).getAsDouble());
                    }
                }
            }
        }
        if (points.isEmpty()) {
            throw new QuoteException(Problem.NO_PRICE);
        }
        final double first = points.get(0);
        final double last = points.get(points.size() - 1);
        final double[] line = new double[points.size()];
        for (int i = 0; i < line.length; i++) {
            line[i] = points.get(i);
        }
        return new Quote(coin.label, last, first != 0 ? (last - first) / first * 100 : null, line);
    }

    // --- Stocks -------------------------------------------------------------------

    /** Stock prices need the user's own free key; the app ships none. */
    public enum StockService {
        FINNHUB("finnhub", "Finnhub", "https://finnhub.io/register", 15),
        ALPHA_VANTAGE("alpha_vantage", "Alpha Vantage", "https://www.alphavantage.co/support/#api-key", 60);

        public final String value;
        public final String title;
        public final String signUp;
        /** Alpha Vantage's free key allows 25 calls a day. */
        public final long minIntervalMs;

        StockService(final String value, final String title, final String signUp, final int minutes) {
            this.value = value;
            this.title = title;
            this.signUp = signUp;
            this.minIntervalMs = minutes * 60_000L;
        }

        public static StockService from(@Nullable final String value) {
            for (final StockService s : values()) {
                if (s.value.equals(value)) {
                    return s;
                }
            }
            return FINNHUB;
        }

        /** The request, or null without a usable symbol or key. */
        @Nullable
        public String url(@Nullable final String symbol, @Nullable final String key) {
            final String sym = cleanSymbol(symbol);
            final String k = key == null ? "" : key.trim();
            if (sym == null || k.isEmpty()) {
                return null;
            }
            if (this == FINNHUB) {
                return "https://finnhub.io/api/v1/quote?symbol=" + encode(sym);   // key: see headers()
            }
            return "https://www.alphavantage.co/query?function=GLOBAL_QUOTE&symbol=" + encode(sym)
                    + "&apikey=" + encode(k);
        }

        /** Finnhub takes the key as a header, so it stays out of the URL (and the logs). */
        public Map<String, String> headers(final String key) {
            final Map<String, String> h = new HashMap<>();
            h.put("Accept", "application/json");
            if (this == FINNHUB) {
                h.put("X-Finnhub-Token", key.trim());
            }
            return h;
        }

        public Quote decode(final String body, final String symbol, final int status) throws QuoteException {
            if (status == 401 || status == 403) {
                throw new QuoteException(Problem.BAD_KEY);
            }
            if (status == 429) {
                throw new QuoteException(Problem.LIMITED);
            }
            final String cleaned = cleanSymbol(symbol);
            final String sym = cleaned != null ? cleaned : symbol;
            final JsonObject o = object(body);
            if (this == FINNHUB) {
                if (o.has("error")) {
                    throw new QuoteException(Problem.BAD_KEY);
                }
                // An unknown symbol answers all zeros.
                final Double c = number(o, "c");
                if (c == null || c == 0) {
                    throw new QuoteException(Problem.UNKNOWN_SYMBOL);
                }
                final Double pc = number(o, "pc");
                return new Quote(sym, c, number(o, "dp"),
                        pc != null && pc != 0 ? new double[]{pc, c} : new double[0]);
            }
            JsonElement info = o.get("Information");
            if (info == null) {
                info = o.get("Note");
            }
            if (info != null && info.isJsonPrimitive()) {
                final String t = info.getAsString().toLowerCase(Locale.ROOT);
                throw new QuoteException(t.contains("apikey") && !t.contains("rate")
                        ? Problem.BAD_KEY : Problem.LIMITED);
            }
            if (o.has("Error Message")) {
                throw new QuoteException(Problem.BAD_KEY);
            }
            final JsonElement g = o.get("Global Quote");
            if (g == null || !g.isJsonObject() || g.getAsJsonObject().size() == 0) {
                throw new QuoteException(Problem.UNKNOWN_SYMBOL);
            }
            final JsonObject q = g.getAsJsonObject();
            final Double price = parse(q, "05. price");
            if (price == null) {
                throw new QuoteException(Problem.NO_PRICE);
            }
            final Double prev = parse(q, "08. previous close");
            final String ch = q.has("10. change percent") && q.get("10. change percent").isJsonPrimitive()
                    ? q.get("10. change percent").getAsString().replace("%", "") : null;
            return new Quote(sym, price, parseDouble(ch), prev != null ? new double[]{prev, price} : new double[0]);
        }
    }

    /** Upper case, letters, digits, '.' and '-', at most 20; else null. */
    @Nullable
    public static String cleanSymbol(@Nullable final String input) {
        if (input == null) {
            return null;
        }
        final String s = input.trim().toUpperCase(Locale.ROOT);
        return !s.isEmpty() && s.length() <= 20 && s.matches("[A-Z0-9.-]+") ? s : null;
    }

    // --- JSON helpers -------------------------------------------------------------

    private static JsonObject object(final String body) throws QuoteException {
        try {
            final JsonElement e = JsonParser.parseString(body);
            if (e != null && e.isJsonObject()) {
                return e.getAsJsonObject();
            }
        } catch (final RuntimeException ignored) {
            // falls through
        }
        throw new QuoteException(Problem.NO_PRICE);
    }

    private static boolean isNumber(final JsonElement e) {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber();
    }

    @Nullable
    private static Double number(final JsonObject o, final String key) {
        final JsonElement e = o.get(key);
        return isNumber(e) ? e.getAsDouble() : null;
    }

    @Nullable
    private static Double parse(final JsonObject o, final String key) {
        final JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? parseDouble(e.getAsString()) : null;
    }

    @Nullable
    private static Double parseDouble(@Nullable final String s) {
        if (s == null) {
            return null;
        }
        try {
            return Double.parseDouble(s.trim());
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    private static String encode(final String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (final UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
