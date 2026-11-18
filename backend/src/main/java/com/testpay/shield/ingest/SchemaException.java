package com.testpay.shield.ingest;

import java.util.List;

/** Raised when an uploaded file does not match the expected format. Carries every problem found. */
public class SchemaException extends RuntimeException {
    private final List<String> problems;

    public SchemaException(List<String> problems) {
        super(String.join("; ", problems));
        this.problems = List.copyOf(problems);
    }

    public SchemaException(String problem) {
        this(List.of(problem));
    }

    public List<String> getProblems() {
        return problems;
    }
}
