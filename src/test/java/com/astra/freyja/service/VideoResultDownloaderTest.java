package com.astra.freyja.service;

import com.astra.freyja.common.BizException;
import com.astra.freyja.service.video.VideoResultDownloader;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class VideoResultDownloaderTest {
    @Test void transientTransferFailureRetriesTheSameResultUrl() throws Exception {
        var calls=new AtomicInteger();var attempts=new ArrayList<Integer>();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/original.mp4",exchange->{
            int status=calls.incrementAndGet()==1?503:200;byte[] body="original-video".getBytes();
            exchange.sendResponseHeaders(status,body.length);
            try(var out=exchange.getResponseBody()){out.write(body);}
        });server.start();
        try(var client=HttpClient.newHttpClient()){
            byte[] result=VideoResultDownloader.download(client,"http://127.0.0.1:"+server.getAddress().getPort()+"/original.mp4",Duration.ofSeconds(3),attempts::add);
            assertEquals("original-video",new String(result));assertEquals(java.util.List.of(1,2),attempts);assertEquals(2,calls.get());
        }finally{server.stop(0);}
    }

    @Test void permanentFailureDoesNotRetry() throws Exception {
        var calls=new AtomicInteger();HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/missing.mp4",exchange->{calls.incrementAndGet();exchange.sendResponseHeaders(404,-1);exchange.close();});server.start();
        try(var client=HttpClient.newHttpClient()){
            assertThrows(BizException.class,()->VideoResultDownloader.download(client,"http://127.0.0.1:"+server.getAddress().getPort()+"/missing.mp4",Duration.ofSeconds(3),attempt->{}));
            assertEquals(1,calls.get());
        }finally{server.stop(0);}
    }

    @Test void repeatedTransferFailureStopsAfterThreeAttempts() throws Exception {
        var calls=new AtomicInteger();HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/result.mp4",exchange->{calls.incrementAndGet();exchange.sendResponseHeaders(503,-1);exchange.close();});server.start();
        try(var client=HttpClient.newHttpClient()){
            assertThrows(java.io.IOException.class,()->VideoResultDownloader.download(client,"http://127.0.0.1:"+server.getAddress().getPort()+"/result.mp4",Duration.ofSeconds(3),attempt->{}));
            assertEquals(3,calls.get());
        }finally{server.stop(0);}
    }

    @Test void cancellationStopsBeforeAnotherAttempt() throws Exception {
        try(var client=HttpClient.newHttpClient()){
            Thread.currentThread().interrupt();
            try{assertThrows(InterruptedException.class,()->VideoResultDownloader.download(client,"http://127.0.0.1:1/result.mp4",Duration.ofSeconds(3),attempt->fail("Cancelled transfer must not start")));}
            finally{Thread.interrupted();}
        }
    }
}
