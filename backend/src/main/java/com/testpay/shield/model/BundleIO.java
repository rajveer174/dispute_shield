package com.testpay.shield.model;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Reads and writes the model bundle as (optionally gzipped) JSON. */
public final class BundleIO {
    private BundleIO() {}

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);

    public static void write(ModelBundle bundle, Path path) throws IOException {
        if (path.getParent() != null) Files.createDirectories(path.getParent());
        try (OutputStream raw = new BufferedOutputStream(Files.newOutputStream(path));
             OutputStream out = path.toString().endsWith(".gz") ? new GZIPOutputStream(raw) : raw) {
            MAPPER.writeValue(out, bundle);
        }
    }

    public static ModelBundle read(Path path) throws IOException {
        try (InputStream raw = new BufferedInputStream(Files.newInputStream(path));
             InputStream in = path.toString().endsWith(".gz") ? new GZIPInputStream(raw) : raw) {
            return MAPPER.readValue(in, ModelBundle.class);
        }
    }
}
