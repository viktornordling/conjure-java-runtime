/*
 * (c) Copyright 2020 Palantir Technologies Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.conjure.java.client.jaxrs;

import com.google.common.base.Strings;
import com.google.common.util.concurrent.Uninterruptibles;
import com.palantir.conjure.java.client.config.ClientConfiguration;
import com.palantir.conjure.java.okhttp.HostMetricsRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import org.slf4j.bridge.SLF4JBridgeHandler;

public class ReproClient extends TestBase {
    private static final int PORT = 8443;
    private static final int THREADS = 32;
    private static final AtomicLong requests = new AtomicLong();
    private static final AtomicLong sent = new AtomicLong();
    private static final AtomicLong success = new AtomicLong();
    private static final byte[] responseData = ('"' + Strings.repeat("Hello, World!", 1024) + '"')
            .getBytes(StandardCharsets.UTF_8);

    public static void main(String[] args) {
        if (!SLF4JBridgeHandler.isInstalled()) {
            SLF4JBridgeHandler.removeHandlersForRootLogger();
            SLF4JBridgeHandler.install();
        }
        Logger.getLogger("okhttp3.OkHttpClient").setLevel(Level.FINE);
        // Undertow server = Undertow.builder()
        //         .setServerOption(UndertowOptions.ENABLE_HTTP2, true)
        //         .addHttpsListener(PORT, null, SslSocketFactories.createSslContext(SslConfiguration.of(
        //                 Paths.get("src/test/resources/trustStore.jks"),
        //                 Paths.get("src/test/resources/keyStore.jks"),
        //                 "keystore")))
        //         .setHandler(new BlockingHandler(exchange -> {
        //             long current = requests.incrementAndGet();
        //             if (current % 1000 == 0) {
        //                 System.out.printf("Received %d requests\n", current);
        //             }
        //             if (!Protocols.HTTP_2_0.equals(exchange.getProtocol())) {
        //                 System.err.println("Bad protocol: " + exchange.getProtocol());
        //             }
        //             // Uninterruptibles.sleepUninterruptibly(Duration.ofMillis(1));
        //             // exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        //             // exchange.getOutputStream().write("1234".getBytes(StandardCharsets.UTF_8));
        //             // exchange.getOutputStream().write(responseData);
        //             ByteStreams.copy(exchange.getInputStream(), ByteStreams.nullOutputStream());
        //         }))
        //         .build();
        // server.start();

        SimpleService client = JaxRsClient.create(
                SimpleService.class,
                AGENT,
                new HostMetricsRegistry(),
                ClientConfiguration.builder()
                        .from(createTestConfig("https://localhost:" + PORT))
                        .enableGcmCipherSuites(true)
                        .backoffSlotSize(Duration.ZERO)
                        .maxNumRetries(0)
                        .clientQoS(ClientConfiguration.ClientQoS.DANGEROUS_DISABLE_SYMPATHETIC_CLIENT_QOS)
                        .build());

        ExecutorService executor = Executors.newCachedThreadPool();
        List<Thread> threads = new CopyOnWriteArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            executor.execute(() -> {
                threads.add(Thread.currentThread());
                while (true) {
                    sent.incrementAndGet();
                    // reset interruption
                    Thread.interrupted();
                    try {
                        client.ping(responseData);
                        success.incrementAndGet();
                    } catch (RuntimeException e) {
                        // if (ThreadLocalRandom.current().nextDouble() > .9D) {
                        //     e.printStackTrace();
                        // }
                        // ignored
                    }
                }
            });
        }
        int iterations = 0;
        while (true) {
            iterations++;
            if (iterations % 1000 == 0) {
                System.out.println("Total: " + sent.get() + " success: " + success.get());
            }
            long start = System.nanoTime();
            System.gc();
            Uninterruptibles.sleepUninterruptibly(Duration.ofNanos(Duration.ofMillis(20).toNanos() - (System.nanoTime() - start)));
            Thread randomThread = threads.get(ThreadLocalRandom.current().nextInt(threads.size()));
            randomThread.interrupt();
        }
    }

    @Path("/simple")
    @Produces("application/json")
    @Consumes("application/json")
    public interface SimpleService {

        @POST
        @Path("/ping")
        void ping(byte[] data);

    }
}
