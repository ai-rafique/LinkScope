package com.linkscope.modules.http;

import com.linkscope.modules.http.HttpRequestSpec.AuthType;
import com.linkscope.modules.http.HttpRequestSpec.BodyType;
import com.linkscope.modules.http.HttpRequestSpec.Header;
import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpRequestSpecTest {

    private static HttpRequestSpec spec(String method, BodyType type, String body, AuthType auth, String user, String secret) {
        return new HttpRequestSpec(method, "http://localhost:1/x", List.of(), type, body, auth, user, secret, 1000, true);
    }

    @Test
    void parsesHeaderLinesAndSkipsCommentsAndBlanks() {
        List<Header> h = HttpRequestSpec.parseHeaders("Accept: application/json\n\n# note\nX-Trace:  1 \r\n");
        assertEquals(List.of(new Header("Accept", "application/json"), new Header("X-Trace", "1")), h);
        assertThrows(IllegalArgumentException.class, () -> HttpRequestSpec.parseHeaders("no colon here"));
        assertEquals("Accept: application/json\nX-Trace: 1\n", HttpRequestSpec.formatHeaders(h));
    }

    @Test
    void formEncodesLinesAndAmpersands() {
        assertEquals("a=1&b=two+words&c=x%25y%3Dz", HttpRequestSpec.formEncode("a=1\nb=two words&c=x%y=z"));
        assertEquals("", HttpRequestSpec.formEncode("  "));
    }

    @Test
    void bodyAndContentTypeFollowBodyTypeAndMethod() {
        HttpRequestSpec json = spec("POST", BodyType.JSON, "{\"a\":1}", AuthType.NONE, "", "");
        assertEquals("application/json", json.defaultContentType());
        assertEquals("{\"a\":1}", new String(json.bodyBytes()));

        HttpRequestSpec form = spec("PUT", BodyType.FORM, "k=v w", AuthType.NONE, "", "");
        assertEquals("application/x-www-form-urlencoded", form.defaultContentType());
        assertEquals("k=v+w", new String(form.bodyBytes()));

        HttpRequestSpec get = spec("GET", BodyType.RAW, "ignored", AuthType.NONE, "", "");
        assertFalse(get.hasBody());
        assertEquals(0, get.bodyBytes().length);
    }

    @Test
    void authorizationHeaderForBasicAndBearer() {
        assertEquals("Basic dXNlcjpwYXNz", spec("GET", BodyType.NONE, "", AuthType.BASIC, "user", "pass").authorizationHeader());
        assertEquals("Bearer t0k", spec("GET", BodyType.NONE, "", AuthType.BEARER, "", "t0k").authorizationHeader());
        assertEquals(null, spec("GET", BodyType.NONE, "", AuthType.NONE, "", "x").authorizationHeader());
    }

    @Test
    void literalSecretDetectionIgnoresPlaceholders() {
        assertTrue(spec("GET", BodyType.NONE, "", AuthType.BEARER, "", "abc").hasLiteralSecret());
        assertFalse(spec("GET", BodyType.NONE, "", AuthType.BEARER, "", "${API_TOKEN}").hasLiteralSecret());
        assertFalse(spec("GET", BodyType.NONE, "", AuthType.NONE, "", "abc").hasLiteralSecret());
    }

    @Test
    void buildAddsSchemeAndHeaders() {
        HttpRequestSpec s = new HttpRequestSpec("post", "example.test/api", List.of(new Header("X-A", "1")),
                BodyType.JSON, "{}", AuthType.BEARER, "", "tok", 500, false);
        HttpRequest req = HttpService.build(s);
        assertEquals("POST", req.method());
        assertEquals("http://example.test/api", req.uri().toString());
        assertEquals("1", req.headers().firstValue("X-A").orElseThrow());
        assertEquals("Bearer tok", req.headers().firstValue("Authorization").orElseThrow());
        assertEquals("application/json", req.headers().firstValue("Content-Type").orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> HttpService.build(
                new HttpRequestSpec("GET", "http://", List.of(), BodyType.NONE, "", AuthType.NONE, "", "", 500, true)));
    }
}
