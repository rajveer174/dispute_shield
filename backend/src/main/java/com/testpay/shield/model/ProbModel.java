package com.testpay.shield.model;

import java.util.Map;

/** Anything that maps named features to a probability. */
public interface ProbModel {
    double predict(Map<String, Double> features);
}
