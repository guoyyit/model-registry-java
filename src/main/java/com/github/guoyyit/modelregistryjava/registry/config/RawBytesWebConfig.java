package com.github.guoyyit.modelregistryjava.registry.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.util.List;

/**
 * Registers a byte-passthrough message converter for model content uploads.
 *
 * <p>Without it, a {@code @RequestBody byte[]} is handled by
 * {@code StringHttpMessageConverter}, which decodes/re-encodes the body using a
 * text charset and corrupts arbitrary binary content. This converter claims
 * only {@code application/octet-stream} and copies bytes verbatim.
 */
@Configuration
public class RawBytesWebConfig implements WebMvcConfigurer {

    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        converters.add(0, new OctetStreamByteArrayConverter());
    }

    static class OctetStreamByteArrayConverter implements HttpMessageConverter<byte[]> {

        private static final List<MediaType> SUPPORTED = List.of(
                MediaType.APPLICATION_OCTET_STREAM, MediaType.ALL);

        @Override
        public boolean canRead(Class<?> clazz, MediaType mediaType) {
            if (!byte[].class.equals(clazz)) {
                return false;
            }
            // claim only opaque binary content; leave JSON/text to other converters
            return mediaType != null
                    && "application".equalsIgnoreCase(mediaType.getType())
                    && "octet-stream".equalsIgnoreCase(mediaType.getSubtype());
        }

        @Override
        public boolean canWrite(Class<?> clazz, MediaType mediaType) {
            return false;
        }

        @Override
        public List<MediaType> getSupportedMediaTypes() {
            return SUPPORTED;
        }

        @Override
        public byte[] read(Class<? extends byte[]> clazz, HttpInputMessage inputMessage)
                throws IOException, HttpMessageNotReadableException {
            return inputMessage.getBody().readAllBytes();
        }

        @Override
        public void write(byte[] t, MediaType contentType, HttpOutputMessage outputMessage)
                throws IOException, HttpMessageNotWritableException {
            throw new UnsupportedOperationException();
        }
    }
}
