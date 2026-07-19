package com.patolojiatlasi.qupath;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

/** Best-effort DZI URL reachability check. Blocks; call from a background thread. Never throws. */
final class LinkCheck {

    private LinkCheck() {}

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    static boolean reachable(int statusCode) {
        if (statusCode == 405) return true;
        return statusCode >= 200 && statusCode < 400;
    }

    static Map<String, Boolean> checkAll(List<AtlasCase> cases, IntConsumer progress) {
        // Distinct URLs, preserve first-seen order for a stable footer listing.
        Map<String, Boolean> result = new LinkedHashMap<>();
        for (AtlasCase c : cases) result.putIfAbsent(c.getDziUrl(), Boolean.TRUE);
        List<String> urls = List.copyOf(result.keySet());
        Map<String, Boolean> out = new ConcurrentHashMap<>();
        AtomicInteger done = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(6, Math.max(1, urls.size())), r -> {
            Thread t = new Thread(r, "atlas-linkcheck");
            t.setDaemon(true);
            return t;
        });
        try {
            for (String url : urls) {
                pool.submit(() -> {
                    out.put(url, probe(url));
                    if (progress != null) progress.accept(done.incrementAndGet());
                });
            }
            pool.shutdown();
            pool.awaitTermination(2, TimeUnit.MINUTES);
        } catch (Exception e) {
            // best-effort: any URL not probed stays as its default below
        } finally {
            pool.shutdownNow();
        }
        // Preserve insertion order; unprobed (e.g. interrupted) default to false.
        Map<String, Boolean> ordered = new LinkedHashMap<>();
        for (String url : urls) ordered.put(url, out.getOrDefault(url, Boolean.FALSE));
        return ordered;
    }

    private static boolean probe(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(8))
                    .build();
            HttpResponse<Void> resp = HTTP.send(req, HttpResponse.BodyHandlers.discarding());
            return reachable(resp.statusCode());
        } catch (Exception e) {
            return false;
        }
    }
}
