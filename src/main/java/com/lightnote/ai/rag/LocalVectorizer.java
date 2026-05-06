package com.lightnote.ai.rag;

import com.lightnote.config.AiFeatureProperties;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class LocalVectorizer {

    @Resource
    private AiFeatureProperties aiFeatureProperties;

    public List<Double> embed(String text) {
        int dimension = Math.max(16, aiFeatureProperties.getRag().getVectorDimension());
        double[] vector = new double[dimension];
        List<String> tokens = tokenize(text);
        if (tokens.isEmpty()) {
            return toList(vector);
        }

        for (String token : tokens) {
            int index = Math.floorMod(token.hashCode(), dimension);
            vector[index] += 1D;
        }

        double norm = 0D;
        for (double value : vector) {
            norm += value * value;
        }
        norm = Math.sqrt(norm);
        if (norm == 0D) {
            return toList(vector);
        }
        for (int i = 0; i < vector.length; i++) {
            vector[i] = vector[i] / norm;
        }
        return toList(vector);
    }

    public double cosine(List<Double> left, List<Double> right) {
        if (left == null || right == null || left.isEmpty() || right.isEmpty()) {
            return 0D;
        }
        int size = Math.min(left.size(), right.size());
        double sum = 0D;
        for (int i = 0; i < size; i++) {
            sum += left.get(i) * right.get(i);
        }
        return sum;
    }

    private List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null) {
            return tokens;
        }
        String normalized = text.toLowerCase(Locale.ROOT).trim();
        if (normalized.isEmpty()) {
            return tokens;
        }

        StringBuilder asciiBuffer = new StringBuilder();
        for (int i = 0; i < normalized.length(); i++) {
            char current = normalized.charAt(i);
            if (Character.isLetterOrDigit(current)) {
                asciiBuffer.append(current);
            } else {
                flushAsciiToken(tokens, asciiBuffer);
                if (isChinese(current)) {
                    tokens.add(String.valueOf(current));
                    if (i < normalized.length() - 1) {
                        char next = normalized.charAt(i + 1);
                        if (isChinese(next)) {
                            tokens.add("" + current + next);
                        }
                    }
                }
            }
        }
        flushAsciiToken(tokens, asciiBuffer);
        return tokens;
    }

    private void flushAsciiToken(List<String> tokens, StringBuilder asciiBuffer) {
        if (asciiBuffer.length() == 0) {
            return;
        }
        tokens.add(asciiBuffer.toString());
        asciiBuffer.setLength(0);
    }

    private boolean isChinese(char current) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(current);
        return Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS.equals(block)
                || Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A.equals(block)
                || Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B.equals(block);
    }

    private List<Double> toList(double[] vector) {
        List<Double> result = new ArrayList<>(vector.length);
        for (double value : vector) {
            result.add(value);
        }
        return result;
    }
}
