package com.astra.freyja.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class McpAuthenticationFilterTest {
    @Test void startupRequiresCredentialAndValidStringProjectIds() {
        McpProperties properties = new McpProperties();
        properties.setEnabled(true);
        assertThrows(IllegalStateException.class, () -> new McpAuthenticationFilter(properties));
        properties.setToken("0123456789abcdef0123456789abcdef");
        properties.setAllowedProjectIds(Set.of("0"));
        assertThrows(com.astra.freyja.common.BizException.class, () -> new McpAuthenticationFilter(properties));
    }

    @Test void allowedOriginAndCredentialCreateImmutableScopedIdentity() throws Exception {
        McpProperties properties = new McpProperties(); properties.setToken("0123456789abcdef0123456789abcdef");
        properties.setEnabled(true);
        properties.setAllowedProjectIds(Set.of("2032095628944588801")); properties.setAllowedOrigins(Set.of("https://studio.example"));
        McpAuthenticationFilter filter = new McpAuthenticationFilter(properties);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp"); request.setServletPath("/mcp");
        request.addHeader("Authorization", "Bearer " + properties.getToken()); request.addHeader("Origin", "https://studio.example");
        AtomicBoolean called = new AtomicBoolean();
        filter.doFilter(request, new MockHttpServletResponse(), (r, s) -> called.set(true));
        assertTrue(called.get()); McpPrincipal principal = (McpPrincipal) request.getAttribute(McpPrincipal.CONTEXT_KEY);
        assertTrue(principal.canRead(2032095628944588801L)); assertFalse(principal.canRead(2032095628944588802L));
        assertThrows(UnsupportedOperationException.class, () -> principal.projects().add(1L));
    }

    @Test void browserPreflightDoesNotExposeToolsAndActualRequestStillRequiresAuthentication() throws Exception {
        McpProperties properties = new McpProperties(); properties.setToken("0123456789abcdef0123456789abcdef");
        properties.setEnabled(true);
        properties.setAllowedOrigins(Set.of("https://studio.example"));
        McpAuthenticationFilter filter = new McpAuthenticationFilter(properties);
        MockHttpServletRequest preflight = new MockHttpServletRequest("OPTIONS", "/mcp"); preflight.setServletPath("/mcp");
        preflight.addHeader("Origin", "https://studio.example"); preflight.addHeader("Access-Control-Request-Method", "POST");
        MockHttpServletResponse response = new MockHttpServletResponse(); AtomicBoolean called = new AtomicBoolean();
        filter.doFilter(preflight, response, (r, s) -> called.set(true));
        assertEquals(204, response.getStatus()); assertFalse(called.get());
        assertEquals("https://studio.example", response.getHeader("Access-Control-Allow-Origin"));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp"); request.setServletPath("/mcp");
        request.addHeader("Origin", "https://studio.example"); response = new MockHttpServletResponse();
        filter.doFilter(request, response, (r, s) -> called.set(true));
        assertEquals(401, response.getStatus()); assertFalse(called.get());
    }

    @Test void oversizedBodyIsRejectedEvenWithoutContentLength() throws Exception {
        McpProperties properties = new McpProperties(); properties.setToken("0123456789abcdef0123456789abcdef");
        properties.setEnabled(true);
        McpAuthenticationFilter filter = new McpAuthenticationFilter(properties);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp") {
            @Override public long getContentLengthLong() { return -1; }
        };
        request.setServletPath("/mcp"); request.addHeader("Authorization", "Bearer " + properties.getToken());
        request.setContent(new byte[2 * 1024 * 1024 + 1]);
        MockHttpServletResponse response = new MockHttpServletResponse(); AtomicBoolean called = new AtomicBoolean();
        filter.doFilter(request, response, (r, s) -> called.set(true));
        assertEquals(413, response.getStatus()); assertFalse(called.get());
    }

    @Test void boundedBufferPassesOriginalBodyToTransport() throws Exception {
        McpProperties properties = new McpProperties(); properties.setToken("0123456789abcdef0123456789abcdef");
        properties.setEnabled(true);
        McpAuthenticationFilter filter = new McpAuthenticationFilter(properties);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp"); request.setServletPath("/mcp");
        request.addHeader("Authorization", "Bearer " + properties.getToken()); byte[] body = "{\"业务\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        request.setContent(body); AtomicBoolean called = new AtomicBoolean();
        filter.doFilter(request, new MockHttpServletResponse(), (r, s) -> {
            assertArrayEquals(body, r.getInputStream().readAllBytes()); assertNotNull(r.getAttribute(McpPrincipal.CONTEXT_KEY)); called.set(true);
        });
        assertTrue(called.get());
    }

    @Test void onlyActualImageUploadCallsCanUseLargerBodyLimit() throws Exception {
        McpProperties properties = new McpProperties(); properties.setToken("0123456789abcdef0123456789abcdef"); properties.setEnabled(true);
        var filter = new McpAuthenticationFilter(properties);
        for (String name : java.util.List.of("upload_asset_image", "batch_upload_asset_images", "batch_create_records")) {
            var request = new MockHttpServletRequest("POST", "/mcp") { @Override public long getContentLengthLong() { return -1; } };
            request.setServletPath("/mcp"); request.addHeader("Authorization", "Bearer " + properties.getToken());
            request.addHeader("Mcp-Name", "upload_asset_image"); // A header cannot expand limits for a different tool.
            byte[] body = ("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"" + name + "\",\"arguments\":{\"dataBase64\":\"" + "A".repeat(2 * 1024 * 1024) + "\"}}}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            request.setContent(body); AtomicBoolean called = new AtomicBoolean(); var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (r, s) -> { assertArrayEquals(body, r.getInputStream().readAllBytes()); called.set(true); });
            boolean upload = name.equals("upload_asset_image") || name.equals("batch_upload_asset_images");
            assertEquals(upload, called.get()); assertEquals(upload ? 200 : 413, response.getStatus());
        }
    }

    @Test void imageUploadAbsoluteLimitCoversChunkedBodies() throws Exception {
        McpProperties properties = new McpProperties(); properties.setToken("0123456789abcdef0123456789abcdef"); properties.setEnabled(true);
        var request = new MockHttpServletRequest("POST", "/mcp") { @Override public long getContentLengthLong() { return -1; } };
        request.setServletPath("/mcp"); request.addHeader("Authorization", "Bearer " + properties.getToken());
        request.setContent(new byte[McpAuthenticationFilter.MAX_IMAGE_REQUEST_BYTES + 1]);
        var response = new MockHttpServletResponse();
        new McpAuthenticationFilter(properties).doFilter(request, response, (r, s) -> fail("oversized request reached transport"));
        assertEquals(413, response.getStatus());
    }

    @Test void batchLimitOnlyAppliesToActualBatchUploadAndPreservesChunkedPayload() throws Exception {
        McpProperties properties = new McpProperties(); properties.setToken("0123456789abcdef0123456789abcdef"); properties.setEnabled(true);
        for (String tool : java.util.List.of("batch_upload_asset_images", "upload_asset_image", "batch_create_records")) {
            var request = new MockHttpServletRequest("POST", "/mcp") { @Override public long getContentLengthLong() { return -1; } };
            request.setServletPath("/mcp"); request.addHeader("Authorization", "Bearer " + properties.getToken());
            request.addHeader("Mcp-Name", "batch_upload_asset_images");
            byte[] body = ("{\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool + "\",\"arguments\":{\"data\":\"" + "A".repeat(McpAuthenticationFilter.MAX_IMAGE_REQUEST_BYTES) + "\"}}}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            request.setContent(body); var response = new MockHttpServletResponse(); AtomicBoolean called = new AtomicBoolean();
            new McpAuthenticationFilter(properties).doFilter(request, response, (r, s) -> { assertArrayEquals(body, r.getInputStream().readAllBytes()); called.set(true); });
            assertEquals(tool.equals("batch_upload_asset_images"), called.get());
        }
        var request = new MockHttpServletRequest("POST", "/mcp") { @Override public long getContentLengthLong() { return -1; } };
        request.setServletPath("/mcp"); request.addHeader("Authorization", "Bearer " + properties.getToken());
        request.setContent(new byte[McpAuthenticationFilter.MAX_BATCH_IMAGE_REQUEST_BYTES + 1]); var response = new MockHttpServletResponse();
        new McpAuthenticationFilter(properties).doFilter(request, response, (r, s) -> fail("oversized batch reached transport")); assertEquals(413, response.getStatus());
    }
}
