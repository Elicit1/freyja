package com.astra.freyja.service.video;

import com.astra.freyja.common.BizException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.IntConsumer;

/** Retries only the immutable result URL, never a generation request. */
public final class VideoResultDownloader {
    private VideoResultDownloader() {}

    public static byte[] download(HttpClient client, String url, Duration timeout, IntConsumer onAttempt)
            throws IOException, InterruptedException {
        for (int attempt = 1; ; attempt++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("视频下载已取消");
            onAttempt.accept(attempt);
            try {
                var request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET().build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                int status = response.statusCode();
                if (status >= 200 && status < 300 && response.body().length > 0) return response.body();
                if (status >= 300 && status != 408 && status != 429 && status < 500)
                    throw new BizException(502,"视频下载失败 HTTP " + status);
                throw new IOException("视频下载失败 HTTP " + status);
            } catch (IOException error) {
                if (attempt >= 3) throw error;
                Thread.sleep(attempt * 1000L);
            }
        }
    }
}
