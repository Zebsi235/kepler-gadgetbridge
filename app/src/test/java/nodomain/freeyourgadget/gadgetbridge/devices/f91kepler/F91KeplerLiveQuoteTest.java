package nodomain.freeyourgadget.gadgetbridge.devices.f91kepler;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import nodomain.freeyourgadget.gadgetbridge.devices.f91kepler.F91KeplerLiveQuote.Problem;
import nodomain.freeyourgadget.gadgetbridge.devices.f91kepler.F91KeplerLiveQuote.StockService;

public class F91KeplerLiveQuoteTest {

    @Test
    public void changeAndPriceText() {
        assertEquals("+1.2%", F91KeplerLiveQuote.changeText(1.234));
        assertEquals("-0.4%", F91KeplerLiveQuote.changeText(-0.4));
        assertEquals("+12%", F91KeplerLiveQuote.changeText(12.3));
        assertEquals("64213", F91KeplerLiveQuote.priceText(64213.4));
        assertEquals("187.4", F91KeplerLiveQuote.priceText(187.42));
        assertEquals("1.08", F91KeplerLiveQuote.priceText(1.0812));
        assertEquals("0.0712", F91KeplerLiveQuote.priceText(0.07123));
        assertEquals("0.000012", F91KeplerLiveQuote.priceText(0.0000123));
    }

    @Test
    public void coinPicks() {
        final F91KeplerLiveQuote.Coin btc = F91KeplerLiveQuote.coin(" btc ");
        assertEquals("bitcoin", btc.id);
        assertEquals("BTC", btc.label);
        assertEquals("USD", btc.currency);
        final F91KeplerLiveQuote.Coin eth = F91KeplerLiveQuote.coin("ETH-eur");
        assertEquals("ethereum", eth.id);
        assertEquals("EUR", eth.currency);
        assertEquals("https://api.coingecko.com/api/v3/coins/ethereum/market_chart?vs_currency=eur&days=1",
                eth.url());
        final F91KeplerLiveQuote.Coin other = F91KeplerLiveQuote.coin("shiba-inu");
        assertEquals("shiba-inu", other.id);
        assertEquals("SHIBA-", other.label);
        assertNull(F91KeplerLiveQuote.coin(""));
        assertNull(F91KeplerLiveQuote.coin("a/b"));
        assertNull(F91KeplerLiveQuote.coin("-EUR"));
    }

    @Test
    public void coinGeckoDecodes() throws Exception {
        final F91KeplerLiveQuote.Quote q = F91KeplerLiveQuote.decodeCoin(
                "{\"prices\":[[1,100.0],[2,105.0],[3,110.0]]}", F91KeplerLiveQuote.coin("BTC"));
        assertEquals(110.0, q.price, 1e-9);
        assertEquals(10.0, q.changePercent, 1e-9);
        assertArrayEquals(new double[]{100, 105, 110}, q.points, 1e-9);
        expect(Problem.UNKNOWN_SYMBOL, () -> F91KeplerLiveQuote.decodeCoin("{\"error\":\"coin not found\"}",
                F91KeplerLiveQuote.coin("nope")));
        expect(Problem.NO_PRICE, () -> F91KeplerLiveQuote.decodeCoin("{\"prices\":[]}",
                F91KeplerLiveQuote.coin("BTC")));
        expect(Problem.NO_PRICE, () -> F91KeplerLiveQuote.decodeCoin("<html>", F91KeplerLiveQuote.coin("BTC")));
    }

    @Test
    public void stockUrls() {
        assertEquals("https://finnhub.io/api/v1/quote?symbol=BRK.B", StockService.FINNHUB.url(" brk.b ", "k&y"));
        assertEquals("k&y", StockService.FINNHUB.headers(" k&y ").get("X-Finnhub-Token"));
        assertNull(StockService.ALPHA_VANTAGE.headers("k").get("X-Finnhub-Token"));
        assertEquals("https://www.alphavantage.co/query?function=GLOBAL_QUOTE&symbol=AAPL&apikey=K",
                StockService.ALPHA_VANTAGE.url("aapl", "K"));
        assertNull(StockService.FINNHUB.url("AAPL", " "));
        assertNull(StockService.FINNHUB.url("A A", "k"));
        assertEquals(StockService.FINNHUB, StockService.from("nonsense"));
        assertEquals(60 * 60_000L, StockService.ALPHA_VANTAGE.minIntervalMs);
    }

    @Test
    public void finnhubDecodes() throws Exception {
        final F91KeplerLiveQuote.Quote q = StockService.FINNHUB.decode(
                "{\"c\":190.5,\"dp\":1.5,\"pc\":187.7}", "aapl", 200);
        assertEquals("AAPL", q.symbol);
        assertEquals(190.5, q.price, 1e-9);
        assertEquals(1.5, q.changePercent, 1e-9);
        assertArrayEquals(new double[]{187.7, 190.5}, q.points, 1e-9);
        expect(Problem.UNKNOWN_SYMBOL, () -> StockService.FINNHUB.decode("{\"c\":0,\"dp\":null,\"pc\":0}", "X", 200));
        expect(Problem.BAD_KEY, () -> StockService.FINNHUB.decode("{\"error\":\"Invalid API key\"}", "X", 200));
        expect(Problem.BAD_KEY, () -> StockService.FINNHUB.decode("", "X", 401));
        expect(Problem.LIMITED, () -> StockService.FINNHUB.decode("", "X", 429));
    }

    @Test
    public void alphaVantageDecodes() throws Exception {
        final F91KeplerLiveQuote.Quote q = StockService.ALPHA_VANTAGE.decode("{\"Global Quote\":{"
                + "\"05. price\":\"190.50\",\"08. previous close\":\"187.70\",\"10. change percent\":\"1.4917%\"}}",
                "AAPL", 200);
        assertEquals(190.5, q.price, 1e-9);
        assertEquals(1.4917, q.changePercent, 1e-9);
        expect(Problem.LIMITED, () -> StockService.ALPHA_VANTAGE.decode(
                "{\"Information\":\"Our standard API rate limit is 25 requests per day.\"}", "X", 200));
        expect(Problem.BAD_KEY, () -> StockService.ALPHA_VANTAGE.decode(
                "{\"Information\":\"The demo API key is for demo purposes only. Please claim your free apikey\"}",
                "X", 200));
        expect(Problem.BAD_KEY, () -> StockService.ALPHA_VANTAGE.decode("{\"Error Message\":\"Invalid\"}", "X", 200));
        expect(Problem.UNKNOWN_SYMBOL, () -> StockService.ALPHA_VANTAGE.decode("{\"Global Quote\":{}}", "X", 200));
    }

    @Test
    public void cardDrawsSymbolChangePriceAndLine() {
        final F91KeplerLiveCanvas c = F91KeplerLiveQuote.render(new F91KeplerLiveQuote.Quote(
                "BTC", 64213, 2.5, new double[]{1, 3, 2, 5}));
        assertTrue(lit(c, 0, 0, 30, 7) > 0);       // symbol
        assertTrue(lit(c, 66, 0, 30, 7) > 0);      // change, right
        assertTrue(lit(c, 0, 9, 96, 14) > 0);      // price, large
        assertTrue(lit(c, 0, 25, 96, 14) > 0);     // the line
    }

    @Test
    public void longPriceFallsBackToSmallText() {
        final F91KeplerLiveCanvas c = F91KeplerLiveQuote.render(new F91KeplerLiveQuote.Quote(
                "X", 123456789, null, new double[0]));
        assertEquals(0, lit(c, 0, 9, 96, 3));       // nothing large from row 9
        assertTrue(lit(c, 0, 12, 96, 7) > 0);
    }

    private interface Call {
        void run() throws Exception;
    }

    private static void expect(final Problem problem, final Call call) {
        try {
            call.run();
            fail("expected " + problem);
        } catch (final F91KeplerLiveQuote.QuoteException e) {
            assertEquals(problem, e.problem);
        } catch (final Exception e) {
            fail("unexpected " + e);
        }
    }

    private static int lit(final F91KeplerLiveCanvas c, final int x, final int y, final int w, final int h) {
        int n = 0;
        for (int yy = y; yy < y + h; yy++) {
            for (int xx = x; xx < x + w; xx++) {
                if (c.isLit(xx, yy)) {
                    n++;
                }
            }
        }
        return n;
    }
}
