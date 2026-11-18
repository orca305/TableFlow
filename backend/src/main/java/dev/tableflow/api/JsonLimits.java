package dev.tableflow.api;

import org.springframework.core.MethodParameter;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;
import java.io.*;
import java.lang.reflect.Type;

@ControllerAdvice
public class JsonLimits extends RequestBodyAdviceAdapter {
    @Override public boolean supports(MethodParameter parameter, Type type, Class<? extends HttpMessageConverter<?>> converter) { return true; }
    @Override public HttpInputMessage beforeBodyRead(HttpInputMessage message, MethodParameter parameter,
        Type type, Class<? extends HttpMessageConverter<?>> converter) throws IOException {
        InputStream bounded = new FilterInputStream(message.getBody()) {
            long count = 0;
            private void consumed(int length) {
                if (length > 0 && (count += length) > 32L * 1024 * 1024) throw new PayloadTooLarge();
            }
            @Override public int read() throws IOException { int value = in.read(); consumed(value == -1 ? 0 : 1); return value; }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                int read = in.read(bytes, offset, Math.min(length, 8192)); consumed(read); return read;
            }
        };
        return new HttpInputMessage() {
            @Override public InputStream getBody() { return bounded; }
            @Override public HttpHeaders getHeaders() { return message.getHeaders(); }
        };
    }
    public static class PayloadTooLarge extends RuntimeException {}
}
