/*
 * Copyright (c) 2025 Contributors to the Eclipse Foundation.
 * Copyright (c) 2017, 2018 Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2016 AsyncHttpClient Project. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at
 *     http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */

package com.ning.http.client.async;

import static com.ning.http.client.Realm.AuthScheme.BASIC;

import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.ProxyServer;
import com.ning.http.client.Realm;
import com.ning.http.client.Request;
import com.ning.http.client.RequestBuilder;
import com.ning.http.client.Response;

import java.io.IOException;
import java.net.UnknownHostException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * Test that validates that when having an HTTP proxy and trying to access an HTTP through the proxy the
 * proxy credentials should be passed after it gets a 407 response.
 */
public abstract class BasicHttpProxyToHttpTest extends AbstractBasicTest {

    private Server server2;

    public static class ProxyHTTPHandler extends Handler.Abstract {

        @Override
        public boolean handle(org.eclipse.jetty.server.Request request, org.eclipse.jetty.server.Response response,
                              Callback callback) throws Exception {
            final HttpFields requestHeaders = request.getHeaders();
            final HttpFields.Mutable responseHeaders = response.getHeaders();

            final String authorization = requestHeaders.get(HttpHeader.AUTHORIZATION);
            final String proxyAuthorization = requestHeaders.get(HttpHeader.PROXY_AUTHORIZATION);
            if (proxyAuthorization == null) {
                response.setStatus(HttpStatus.PROXY_AUTHENTICATION_REQUIRED_407);
                responseHeaders.put(HttpHeader.PROXY_AUTHENTICATE, "Basic realm=\"Fake Realm\"");
            } else if (proxyAuthorization
                .equals("Basic am9obmRvZTpwYXNz") && authorization != null && authorization.equals("Basic dXNlcjpwYXNzd2Q=")) {
                responseHeaders.put("target", request.getHttpURI().getPath());
                response.setStatus(HttpStatus.OK_200);
            } else {
                response.setStatus(HttpStatus.UNAUTHORIZED_401);
                responseHeaders.put(HttpHeader.WWW_AUTHENTICATE, "Basic realm=\"Fake Realm\"");
            }
            Content.Sink.asOutputStream(response).flush();
            Content.Sink.asOutputStream(response).close();
            callback.succeeded();
            return true;
        }
    }

    @AfterClass(alwaysRun = true)
    public void tearDownGlobal() throws Exception {
        server.stop();
        server2.stop();
    }

    @BeforeClass(alwaysRun = true)
    public void setUpGlobal() throws Exception {
        // HTTP Server
        server = new Server();
        // HTTP Proxy Server
        server2 = new Server();

        port1 = findFreePort();
        port2 = findFreePort();

        // HTTP Server
        ServerConnector listener = new ServerConnector(server);

        listener.setHost("127.0.0.1");
        listener.setPort(port1);
        server.addConnector(listener);
        server.setHandler(new EchoHandler());
        server.start();

        listener = new ServerConnector(server2);

        // Proxy Server configuration
        listener.setHost("127.0.0.1");
        listener.setPort(port2);
        server2.addConnector(listener);
        server2.setHandler(configureHandler());
        server2.start();
        log.info("Local HTTP Server (" + port1 + "), Proxy HTTP Server (" + port2 + ") started successfully");
    }


    @Override
    public Handler.Abstract configureHandler() throws Exception {
        return new ProxyHTTPHandler();
    }

    @Test
    public void httpProxyToHttpTargetUsePreemptiveAuthTest() throws IOException, InterruptedException, ExecutionException {
        doTest(true);
    }

    @Test
    public void httpProxyToHttpTargetTest() throws IOException, InterruptedException, ExecutionException {
        doTest(false);
    }

    private void doTest(boolean usePreemptiveAuth) throws UnknownHostException, InterruptedException, ExecutionException
    {
        try (AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().build())) {
            Request request = new RequestBuilder("GET").setProxyServer(basicProxy()).setUrl(getTargetUrl()).setRealm(
                new Realm.RealmBuilder().setPrincipal("user").setPassword("passwd").setScheme(BASIC).setUsePreemptiveAuth(usePreemptiveAuth).build()).build();
            Future<Response> responseFuture = client.executeRequest(request);
            Response response = responseFuture.get();
            Assert.assertEquals(response.getStatusCode(), 200);
            Assert.assertTrue(getTargetUrl().endsWith(response.getHeader("target")));
        }
    }

    private ProxyServer basicProxy() throws UnknownHostException {
        ProxyServer proxyServer = new ProxyServer("127.0.0.1", port2, "johndoe", "pass");
        proxyServer.setScheme(BASIC);
        return proxyServer;
    }
}
